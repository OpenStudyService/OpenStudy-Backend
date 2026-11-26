package com.openstudy.modules.inquiry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.inquiry.dto.response.ActorSummary;
import com.openstudy.modules.inquiry.dto.response.InquiryDetail;
import com.openstudy.modules.inquiry.dto.response.InquiryEventView;
import com.openstudy.modules.inquiry.dto.response.InquiryListItem;
import com.openstudy.modules.inquiry.dto.response.InquirySummary;
import com.openstudy.modules.inquiry.entity.Inquiry;
import com.openstudy.modules.inquiry.entity.InquiryEvent;
import com.openstudy.modules.inquiry.entity.InquirySource;
import com.openstudy.modules.inquiry.entity.InquiryStatus;
import com.openstudy.modules.inquiry.repository.InquiryEventRepository;
import com.openstudy.modules.inquiry.repository.InquiryRepository;
import com.openstudy.modules.inquiry.repository.InquirySearchRepository;

/**
 * Customer inquiries registered by proxy (FR-INQ-01~04, ADR-M7-001 M7-D02). Any valid member of the inquiry's branch
 * reads, registers, answers and changes the status; only the current assignee or the branch's MANAGER changes the
 * assignee. The customer is exposed as a {@code CustomerSummary} and only after {@link CustomerService#requireInquiryTarget}
 * has shown the requester may name that customer. Every change writes one inquiry_events row in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    private static final String CREATE_OPERATION = "inquiries:create";
    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 20000;
    private static final int MAX_ANSWER_LENGTH = 5000;
    private static final int MAX_LABEL_LENGTH = 10;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED_STATUS = 201;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final Set<String> SUMMARY_SORTS = Set.of("id");
    private static final String NO_ASSIGNEE = "없음";

    private final InquiryRepository inquiryRepository;
    private final InquiryEventRepository inquiryEventRepository;
    private final InquirySearchRepository inquirySearchRepository;
    private final CustomerService customerService;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<InquiryListItem> list(long actor, String scope, ListQuery query, InquiryFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        InquirySearchRepository.Sort sort = "updatedAt".equals(query.sort())
                ? InquirySearchRepository.Sort.UPDATED_AT
                : InquirySearchRepository.Sort.CREATED_AT;
        InquirySearchRepository.Filter filter = toFilter(
                authorizationService.scope(state, scope, BranchAction.READ),
                query.q(),
                filters
        );
        boolean descending = query.direction() == ListQuery.Direction.DESC;
        InquirySearchRepository.Page page = inquirySearchRepository.find(
                filter,
                sort,
                descending,
                query.offset(),
                query.pageSize()
        );

        return new PageResponse<>(
                toListItems(page.rows(), state),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total())
        );
    }

    @Transactional(readOnly = true)
    public InquirySummary summary(long actor, String scope, String q, InquiryFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        // ListQuery applies the same trim and length rule to q as the list.
        String text = ListQuery.parse(1, 1, q, null, null, SUMMARY_SORTS, "id").q();

        Map<Long, InquirySearchRepository.BranchCounts> found = new HashMap<>();
        inquirySearchRepository.countByBranch(toFilter(selected, text, filters))
                .forEach(row -> found.put(row.branchId(), row));

        List<InquirySummary.BranchCounts> branches = new ArrayList<>();
        long total = 0;
        long received = 0;
        long inProgress = 0;
        long answered = 0;

        for (long branch : new TreeSet<>(selected)) {
            InquirySearchRepository.BranchCounts row = found.getOrDefault(
                    branch,
                    new InquirySearchRepository.BranchCounts(branch, 0, 0, 0, 0)
            );
            branches.add(new InquirySummary.BranchCounts(
                    Long.toString(branch),
                    row.total(),
                    row.received(),
                    row.inProgress(),
                    row.answered(),
                    row.received() + row.inProgress()
            ));
            total += row.total();
            received += row.received();
            inProgress += row.inProgress();
            answered += row.answered();
        }

        InquirySummary.Counts totals = new InquirySummary.Counts(
                total,
                received,
                inProgress,
                answered,
                received + inProgress
        );

        return new InquirySummary(now, totals, branches);
    }

    @Transactional(readOnly = true)
    public InquiryDetail get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);

        return toDetail(findVisibleInquiry(state, id, scope), state);
    }

    @Transactional(readOnly = true)
    public PageResponse<InquiryEventView> listEvents(long actor, long id, String scope, ListQuery query) {
        findVisibleInquiry(authorizationService.current(actor), id, scope);

        List<InquiryEvent> rows = inquirySearchRepository.findEvents(id, query.offset(), query.pageSize());
        List<Long> actorIds = rows.stream()
                .map(InquiryEvent::getActorEmployeeId)
                .toList();
        Map<Long, String> names = employeeAccountService.names(actorIds);
        List<InquiryEventView> items = rows.stream()
                .map(row -> toEventView(row, names))
                .toList();
        long total = inquiryEventRepository.countByInquiryId(id);

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), total));
    }

    public InquiryDetail create(long actor, String scope, String key, Map<String, Object> body) {
        StrictInput input = new StrictInput(body)
                .only("branchId", "customerId", "subject", "body", "source", "assigneeId", "status");
        long branch = input.id("branchId");
        long customerId = input.id("customerId");
        String subject = input.text("subject", MAX_SUBJECT_LENGTH);
        String content = input.text("body", MAX_BODY_LENGTH);
        InquirySource source = InquiryInputs.toSource(input.text("source", MAX_LABEL_LENGTH), "source");
        Long assignee = InquiryInputs.parseOptionalId(body, "assigneeId");
        InquiryStatus status = body.get("status") == null
                ? InquiryStatus.RECEIVED
                : InquiryInputs.toUnresolvedStatus(input.text("status", MAX_LABEL_LENGTH), "status");
        requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(
                branch,
                customerId,
                subject,
                content,
                source.name(),
                assignee,
                status.name()
        );
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard. The customer is checked before a stored result
            // may be replayed, so a retry never returns a customer the requester can no longer name.
            CustomerSummary customer = customerService.requireInquiryTarget(state, customerId, branch);
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE_OPERATION, scopeKey, key);
            Instant now = clock.instant();

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    return replay(prior.get(), fingerprint);
                }

                idempotencyStore.discard(prior.get());
            }

            if (assignee != null) {
                requireAssignable(assignee, branch);
            }

            Inquiry inquiry = inquiryRepository.saveAndFlush(
                    new Inquiry(branch, customerId, subject, content, source, status, assignee, actor)
            );
            inquiryEventRepository.save(
                    new InquiryEvent(inquiry.getId(), actor, null, status, createRegistrationMessage(source, assignee))
            );

            InquiryDetail result = toDetail(inquiry, state, customer);
            idempotencyStore.complete(
                    actor,
                    CREATE_OPERATION,
                    scopeKey,
                    key,
                    fingerprint,
                    CREATED_STATUS,
                    objectMapper.writeValueAsString(result),
                    now.plus(KEY_RETENTION)
            );
            return result;
        });
    }

    public InquiryDetail changeAssignee(long actor, long id, String scope, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "assigneeId");
        long version = input.version("version");

        if (!input.has("assigneeId")) {
            throw ApiException.validation("assigneeId", "담당자 ID 또는 null을 입력해 주세요.");
        }

        Long next = InquiryInputs.parseOptionalId(body, "assigneeId");

        return applyChange(actor, id, scope, version, this::canChangeAssignee, (state, inquiry) -> {
            Long previous = inquiry.getAssignedEmployeeId();

            if (Objects.equals(previous, next)) {
                return toDetail(inquiry, state);
            }

            if (next != null) {
                requireAssignable(next, inquiry.getBranchId());
            }

            List<Long> ids = new ArrayList<>();

            if (previous != null) {
                ids.add(previous);
            }

            if (next != null) {
                ids.add(next);
            }

            Map<Long, String> names = employeeAccountService.names(ids);
            String message = "담당자: " + toName(previous, names) + " → " + toName(next, names);
            inquiryEventRepository.save(new InquiryEvent(id, actor, inquiry.getStatus(), inquiry.getStatus(), message));
            inquiry.assign(next);

            return saveAndDescribe(inquiry, state);
        });
    }

    public InquiryDetail answer(long actor, long id, String scope, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "answer");
        long version = input.version("version");
        String answer = input.text("answer", MAX_ANSWER_LENGTH);

        return applyChange(actor, id, scope, version, (state, inquiry) -> true, (state, inquiry) -> {
            // A re-answer replaces the stored answer; the replaced one stays readable in the history.
            InquiryEvent event = new InquiryEvent(
                    id,
                    actor,
                    inquiry.getStatus(),
                    InquiryStatus.ANSWERED,
                    inquiry.getAnswerBody()
            );
            inquiryEventRepository.save(event);
            inquiry.answer(answer, actor, clock.instant().truncatedTo(ChronoUnit.MICROS));

            return saveAndDescribe(inquiry, state);
        });
    }

    public InquiryDetail changeStatus(long actor, long id, String scope, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        InquiryStatus next = InquiryInputs.toUnresolvedStatus(input.text("status", MAX_LABEL_LENGTH), "status");

        return applyChange(actor, id, scope, version, (state, inquiry) -> true, (state, inquiry) -> {
            InquiryStatus previous = inquiry.getStatus();

            if (previous == next) {
                return toDetail(inquiry, state);
            }

            // Moving back from 답변 완료 keeps the answer: only the status changes.
            inquiryEventRepository.save(new InquiryEvent(id, actor, previous, next, null));
            inquiry.changeStatus(next);

            return saveAndDescribe(inquiry, state);
        });
    }

    /** Shared command frame: scope, parent lock, current permission, then the version, in that order. */
    private InquiryDetail applyChange(
            long actor,
            long id,
            String scope,
            long version,
            BiPredicate<AuthorizationState, Inquiry> permitted,
            BiFunction<AuthorizationState, Inquiry, InquiryDetail> change
    ) {
        long branch = findVisibleInquiry(authorizationService.current(actor), id, scope).getBranchId();

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.READ,
                () -> inquiryRepository.lock(id),
                state -> {
                    Inquiry inquiry = inquiryRepository.findById(id).orElseThrow(InquiryService::notFound);

                    if (!permitted.test(state, inquiry)) {
                        throw AuthorizationService.forbidden();
                    }

                    ApiVersion.requireCurrent(version, inquiry.getVersion(), "/api/inquiries/" + id);

                    return change.apply(state, inquiry);
                }
        );
    }

    private InquiryDetail saveAndDescribe(Inquiry inquiry, AuthorizationState state) {
        Inquiry saved = inquiryRepository.saveAndFlush(inquiry);

        return toDetail(saved, state);
    }

    private InquiryDetail replay(IdempotencyRecord prior, byte[] fingerprint) {
        if (!MessageDigest.isEqual(fingerprint, prior.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return objectMapper.readValue(prior.getResponseJson(), InquiryDetail.class);
    }

    private String createRegistrationMessage(InquirySource source, Long assignee) {
        String message = "문의를 등록했습니다. 접수 경로: " + InquiryInputs.toLabel(source);

        if (assignee == null) {
            return message;
        }

        String assigneeName = employeeAccountService.names(List.of(assignee)).getOrDefault(assignee, "");

        return message + ", 담당자: " + assigneeName;
    }

    /** 404 hides inquiries outside the reader's scope, so IDs are not an existence oracle. */
    private Inquiry findVisibleInquiry(AuthorizationState state, long id, String scope) {
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        return inquiryRepository.findById(id)
                .filter(inquiry -> selected.contains(inquiry.getBranchId()))
                .orElseThrow(InquiryService::notFound);
    }

    /** The current assignee or the branch's MANAGER (OWNER included); with no assignee only the MANAGER. */
    private boolean canChangeAssignee(AuthorizationState state, Inquiry inquiry) {
        boolean member = authorizationService.permits(state, inquiry.getBranchId(), BranchAction.READ);

        return member
                && (Objects.equals(inquiry.getAssignedEmployeeId(), state.employeeId())
                || authorizationService.permits(state, inquiry.getBranchId(), BranchAction.MANAGE_COLLABORATION));
    }

    private void requireAssignable(long employee, long branch) {
        if (!employeeAccountService.assignable(employee, branch)) {
            throw ApiException.unprocessable("INVALID_ASSIGNEE", "해당 지점의 활성 직원만 담당자로 지정할 수 있습니다.");
        }
    }

    private List<InquiryListItem> toListItems(List<Inquiry> rows, AuthorizationState state) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, String> people = findPeopleNames(rows);
        List<Long> customerIds = rows.stream()
                .map(Inquiry::getCustomerId)
                .toList();
        // The rows were selected by branch scope, which is what authorizes naming their customers.
        Map<Long, CustomerSummary> named = customerService.summaries(customerIds);

        return rows.stream()
                .map(row -> toListItem(row, named.get(row.getCustomerId()), people, state))
                .toList();
    }

    private InquiryListItem toListItem(
            Inquiry row,
            CustomerSummary customer,
            Map<Long, String> people,
            AuthorizationState state
    ) {
        return new InquiryListItem(
                row.getId().toString(),
                Long.toString(row.getBranchId()),
                customer,
                row.getSubject(),
                InquiryInputs.toLabel(row.getSource()),
                InquiryInputs.toLabel(row.getStatus()),
                toActor(row.getAssignedEmployeeId(), people),
                toActor(row.getCreatedBy(), people),
                row.getAnsweredAt(),
                canChangeAssignee(state, row),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private InquiryDetail toDetail(Inquiry inquiry, AuthorizationState state) {
        CustomerSummary customer = customerService.summaries(List.of(inquiry.getCustomerId()))
                .get(inquiry.getCustomerId());

        return toDetail(inquiry, state, customer);
    }

    private InquiryDetail toDetail(Inquiry row, AuthorizationState state, CustomerSummary customer) {
        Map<Long, String> people = findPeopleNames(List.of(row));

        return new InquiryDetail(
                row.getId().toString(),
                Long.toString(row.getBranchId()),
                customer,
                row.getSubject(),
                InquiryInputs.toLabel(row.getSource()),
                InquiryInputs.toLabel(row.getStatus()),
                toActor(row.getAssignedEmployeeId(), people),
                toActor(row.getCreatedBy(), people),
                row.getAnsweredAt(),
                canChangeAssignee(state, row),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getBody(),
                row.getAnswerBody(),
                toActor(row.getAnsweredBy(), people)
        );
    }

    private Map<Long, String> findPeopleNames(List<Inquiry> rows) {
        List<Long> ids = rows.stream()
                .flatMap(row -> Stream.of(row.getAssignedEmployeeId(), row.getCreatedBy(), row.getAnsweredBy()))
                .filter(Objects::nonNull)
                .toList();

        return employeeAccountService.names(ids);
    }

    private static InquirySearchRepository.Filter toFilter(Set<Long> selected, String q, InquiryFilters filters) {
        return new InquirySearchRepository.Filter(
                selected,
                q,
                filters.status(),
                filters.assignee(),
                filters.customer()
        );
    }

    private static InquiryEventView toEventView(InquiryEvent row, Map<Long, String> names) {
        String oldStatus = row.getOldStatus() == null ? null : InquiryInputs.toLabel(row.getOldStatus());

        return new InquiryEventView(
                row.getId().toString(),
                toActor(row.getActorEmployeeId(), names),
                oldStatus,
                InquiryInputs.toLabel(row.getNewStatus()),
                row.getMessage(),
                row.getCreatedAt()
        );
    }

    private static String toName(Long employee, Map<Long, String> names) {
        return employee == null ? NO_ASSIGNEE : names.getOrDefault(employee, "");
    }

    private static ActorSummary toActor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static void requireValidKey(String key) {
        boolean valid = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!valid) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
