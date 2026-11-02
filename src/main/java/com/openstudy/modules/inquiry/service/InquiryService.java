package com.openstudy.modules.inquiry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
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
    private static final String CREATE = "inquiries:create";
    private final InquiryRepository repository;
    private final InquiryEventRepository events;
    private final InquirySearchRepository search;
    private final CustomerService customers;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<InquiryListItem> list(long actor, String scope, ListQuery query, InquiryFilters filters) {
        var state = policy.current(actor);
        var sort = "updatedAt".equals(query.sort()) ? InquirySearchRepository.Sort.UPDATED_AT : InquirySearchRepository.Sort.CREATED_AT;
        var page = search.find(filter(policy.scope(state, scope, BranchAction.READ), query.q(), filters), sort,
                query.direction() == ListQuery.Direction.DESC, query.offset(), query.pageSize());
        return new PageResponse<>(items(page.rows(), state), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public InquirySummary summary(long actor, String scope, String q, InquiryFilters filters) {
        var state = policy.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        // ListQuery applies the same trim and length rule to q as the list.
        String text = ListQuery.parse(1, 1, q, null, null, Set.of("id"), "id").q();
        Map<Long, InquirySearchRepository.BranchCounts> found = new HashMap<>();
        search.counts(filter(selected, text, filters)).forEach(row -> found.put(row.branchId(), row));
        var branches = new ArrayList<InquirySummary.BranchCounts>();
        var totals = new long[4];
        for (long branch : new TreeSet<>(selected)) {
            var row = found.getOrDefault(branch, new InquirySearchRepository.BranchCounts(branch, 0, 0, 0, 0));
            branches.add(new InquirySummary.BranchCounts(Long.toString(branch), row.total(), row.received(), row.inProgress(),
                    row.answered(), row.received() + row.inProgress()));
            totals[0] += row.total(); totals[1] += row.received(); totals[2] += row.inProgress(); totals[3] += row.answered();
        }
        return new InquirySummary(now, new InquirySummary.Counts(totals[0], totals[1], totals[2], totals[3], totals[1] + totals[2]), branches);
    }

    @Transactional(readOnly = true)
    public InquiryDetail get(long actor, long id, String scope) {
        var state = policy.current(actor);
        return detail(visible(state, id, scope), state);
    }

    @Transactional(readOnly = true)
    public PageResponse<InquiryEventView> history(long actor, long id, String scope, ListQuery query) {
        visible(policy.current(actor), id, scope);
        var rows = search.events(id, query.offset(), query.pageSize());
        var names = employees.names(rows.stream().map(InquiryEvent::getActorEmployeeId).toList());
        return new PageResponse<>(rows.stream().map(row -> event(row, names)).toList(),
                new PageResponse.Meta(query.page(), query.pageSize(), events.countByInquiryId(id)));
    }

    public InquiryDetail create(long actor, String scope, String key, Map<String, Object> body) {
        var input = new StrictInput(body).only("branchId", "customerId", "subject", "body", "source", "assigneeId", "status");
        long branch = input.id("branchId");
        long customerId = input.id("customerId");
        String subject = input.text("subject", 200);
        String content = input.text("body", 20000);
        var source = InquiryInputs.source(input.text("source", 10), "source");
        Long assignee = InquiryInputs.optionalId(body, "assigneeId");
        InquiryStatus status = body.get("status") == null ? InquiryStatus.RECEIVED
                : InquiryInputs.unresolvedStatus(input.text("status", 10), "status");
        validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, customerId, subject, content, source.name(),
                assignee, status.name())));
        return commands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard. The customer is checked before a stored result
            // may be replayed, so a retry never returns a customer the requester can no longer name.
            CustomerSummary customer = customers.requireInquiryTarget(state, customerId, branch);
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), InquiryDetail.class);
                }
                requests.discard(prior.get());
            }
            if (assignee != null) requireAssignable(assignee, branch);
            var inquiry = repository.saveAndFlush(new Inquiry(branch, customerId, subject, content, source, status, assignee, actor));
            String message = "문의를 등록했습니다. 접수 경로: " + InquiryInputs.label(source) + (assignee == null ? ""
                    : ", 담당자: " + employees.names(List.of(assignee)).getOrDefault(assignee, ""));
            events.save(new InquiryEvent(inquiry.getId(), actor, null, status, message));
            outbox.invalidate(branch, "inquiries", inquiry.getId());
            var result = detail(inquiry, state, customer);
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    public InquiryDetail changeAssignee(long actor, long id, String scope, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "assigneeId");
        long version = input.version("version");
        if (!input.has("assigneeId")) throw ApiException.validation("assigneeId", "담당자 ID 또는 null을 입력해 주세요.");
        Long next = InquiryInputs.optionalId(body, "assigneeId");
        return edit(actor, id, scope, version, this::assigneeChangeable, (state, inquiry) -> {
            Long previous = inquiry.getAssignedEmployeeId();
            if (Objects.equals(previous, next)) return detail(inquiry, state);
            if (next != null) requireAssignable(next, inquiry.getBranchId());
            var ids = new ArrayList<Long>();
            if (previous != null) ids.add(previous);
            if (next != null) ids.add(next);
            var names = employees.names(ids);
            events.save(new InquiryEvent(id, actor, inquiry.getStatus(), inquiry.getStatus(),
                    "담당자: " + name(previous, names) + " → " + name(next, names)));
            inquiry.assign(next);
            return saved(inquiry, state);
        });
    }

    public InquiryDetail answer(long actor, long id, String scope, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "answer");
        long version = input.version("version");
        String answer = input.text("answer", 5000);
        return edit(actor, id, scope, version, (state, inquiry) -> true, (state, inquiry) -> {
            // A re-answer replaces the stored answer; the replaced one stays readable in the history.
            events.save(new InquiryEvent(id, actor, inquiry.getStatus(), InquiryStatus.ANSWERED, inquiry.getAnswerBody()));
            inquiry.answer(answer, actor, clock.instant().truncatedTo(ChronoUnit.MICROS));
            return saved(inquiry, state);
        });
    }

    public InquiryDetail changeStatus(long actor, long id, String scope, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        InquiryStatus next = InquiryInputs.unresolvedStatus(input.text("status", 10), "status");
        return edit(actor, id, scope, version, (state, inquiry) -> true, (state, inquiry) -> {
            InquiryStatus previous = inquiry.getStatus();
            if (previous == next) return detail(inquiry, state);
            // Moving back from 답변 완료 keeps the answer: only the status changes.
            events.save(new InquiryEvent(id, actor, previous, next, null));
            inquiry.changeStatus(next);
            return saved(inquiry, state);
        });
    }

    /** Shared command frame: scope, parent lock, current permission, then the version, in that order. */
    private InquiryDetail edit(long actor, long id, String scope, long version, BiPredicate<AuthorizationState, Inquiry> permitted,
            BiFunction<AuthorizationState, Inquiry, InquiryDetail> change) {
        long branch = visible(policy.current(actor), id, scope).getBranchId();
        return commands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> repository.lock(id), state -> {
            var inquiry = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!permitted.test(state, inquiry)) throw AuthorizationService.forbidden();
            ApiVersion.requireCurrent(version, inquiry.getVersion(), "/api/inquiries/" + id);
            return change.apply(state, inquiry);
        });
    }

    private InquiryDetail saved(Inquiry inquiry, AuthorizationState state) {
        var result = repository.saveAndFlush(inquiry);
        outbox.invalidate(result.getBranchId(), "inquiries", result.getId());
        return detail(result, state);
    }

    /** 404 hides inquiries outside the reader's scope, so IDs are not an existence oracle. */
    private Inquiry visible(AuthorizationState state, long id, String scope) {
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        return repository.findById(id).filter(inquiry -> selected.contains(inquiry.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** The current assignee or the branch's MANAGER (OWNER included); with no assignee only the MANAGER. */
    private boolean assigneeChangeable(AuthorizationState state, Inquiry inquiry) {
        return policy.permits(state, inquiry.getBranchId(), BranchAction.READ)
                && (Objects.equals(inquiry.getAssignedEmployeeId(), state.employeeId())
                || policy.permits(state, inquiry.getBranchId(), BranchAction.MANAGE_COLLABORATION));
    }

    private void requireAssignable(long employee, long branch) {
        if (!employees.assignable(employee, branch))
            throw ApiException.unprocessable("INVALID_ASSIGNEE", "해당 지점의 활성 직원만 담당자로 지정할 수 있습니다.");
    }

    private static String name(Long employee, Map<Long, String> names) {
        return employee == null ? "없음" : names.getOrDefault(employee, "");
    }

    private static InquirySearchRepository.Filter filter(Set<Long> selected, String q, InquiryFilters filters) {
        return new InquirySearchRepository.Filter(selected, q, filters.status(), filters.assignee(), filters.customer());
    }

    private List<InquiryListItem> items(List<Inquiry> rows, AuthorizationState state) {
        if (rows.isEmpty()) return List.of();
        var people = people(rows);
        var customerIds = rows.stream().map(Inquiry::getCustomerId).toList();
        // The rows were selected by branch scope, which is what authorizes naming their customers.
        var named = customers.summaries(customerIds);
        return rows.stream().map(row -> new InquiryListItem(row.getId().toString(), Long.toString(row.getBranchId()),
                named.get(row.getCustomerId()), row.getSubject(), InquiryInputs.label(row.getSource()), InquiryInputs.label(row.getStatus()),
                actor(row.getAssignedEmployeeId(), people), actor(row.getCreatedBy(), people), row.getAnsweredAt(),
                assigneeChangeable(state, row), ApiVersion.fromDatabase(row.getVersion()), row.getCreatedAt(), row.getUpdatedAt())).toList();
    }

    private InquiryDetail detail(Inquiry inquiry, AuthorizationState state) {
        return detail(inquiry, state, customers.summaries(List.of(inquiry.getCustomerId())).get(inquiry.getCustomerId()));
    }

    private InquiryDetail detail(Inquiry row, AuthorizationState state, CustomerSummary customer) {
        var people = people(List.of(row));
        return new InquiryDetail(row.getId().toString(), Long.toString(row.getBranchId()), customer, row.getSubject(),
                InquiryInputs.label(row.getSource()), InquiryInputs.label(row.getStatus()), actor(row.getAssignedEmployeeId(), people),
                actor(row.getCreatedBy(), people), row.getAnsweredAt(), assigneeChangeable(state, row),
                ApiVersion.fromDatabase(row.getVersion()), row.getCreatedAt(), row.getUpdatedAt(), row.getBody(),
                row.getAnswerBody(), actor(row.getAnsweredBy(), people));
    }

    private Map<Long, String> people(List<Inquiry> rows) {
        return employees.names(rows.stream().flatMap(row -> Stream.of(row.getAssignedEmployeeId(), row.getCreatedBy(), row.getAnsweredBy()))
                .filter(Objects::nonNull).toList());
    }

    private static InquiryEventView event(InquiryEvent row, Map<Long, String> names) {
        return new InquiryEventView(row.getId().toString(), actor(row.getActorEmployeeId(), names),
                row.getOldStatus() == null ? null : InquiryInputs.label(row.getOldStatus()), InquiryInputs.label(row.getNewStatus()),
                row.getMessage(), row.getCreatedAt());
    }

    private static ActorSummary actor(Long id, Map<Long, String> names) {
        return id == null ? null : new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
