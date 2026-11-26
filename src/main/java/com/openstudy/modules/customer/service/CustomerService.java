package com.openstudy.modules.customer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.exception.OperationsConflict;
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
import com.openstudy.modules.customer.dto.response.CustomerDetail;
import com.openstudy.modules.customer.dto.response.CustomerListItem;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.dto.response.ExactCustomerMatch;
import com.openstudy.modules.customer.entity.Customer;
import com.openstudy.modules.customer.entity.CustomerInteractionSource;
import com.openstudy.modules.customer.entity.CustomerServiceInteraction;
import com.openstudy.modules.customer.entity.CustomerStatus;
import com.openstudy.modules.customer.entity.CustomerStatusEvent;
import com.openstudy.modules.customer.repository.CustomerRepository;
import com.openstudy.modules.customer.repository.CustomerSearchRepository;
import com.openstudy.modules.customer.repository.CustomerServiceInteractionRepository;
import com.openstudy.modules.customer.repository.CustomerStatusEventRepository;

/**
 * Customer ledger (FR-MEM-01/02/04/05/06). A customer is visible to a branch where it registered or paid (M4-D05);
 * reads need only a valid membership there, writes need members:write in any one of those branches. Audit rows carry
 * field names and statuses, not the personal values themselves, so the append-only log does not copy contact data.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {

    private static final String CREATE_OPERATION = "customers:create";
    private static final String AUDIT_ENTITY_TYPE = "customers";
    private static final String ALL_SCOPE = "all";
    private static final Set<String> IMMUTABLE = Set.of("memberNo", "registeredBranchId", "status", "statusReason");
    private static final char[] MEMBER_NO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final String MEMBER_NO_PREFIX = "M-";
    private static final int MEMBER_NO_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_STATUS_LENGTH = 30;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED_STATUS = 201;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);
    /** A PATCH body needs the version plus at least one field to change. */
    private static final int MIN_UPDATE_FIELDS = 2;

    private final CustomerRepository customerRepository;
    private final CustomerStatusEventRepository customerStatusEventRepository;
    private final CustomerServiceInteractionRepository customerServiceInteractionRepository;
    private final CustomerSearchRepository customerSearchRepository;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItem> list(long actor, String scope, ListQuery query, String status) {
        CustomerStatus statusFilter = status == null ? null : CustomerInputs.toStatus(status, "status");
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        CustomerSearchRepository.Sort sort = CustomerSearchRepository.Sort.fromKey(query.sort());
        CustomerSearchRepository.Filter filter = new CustomerSearchRepository.Filter(
                selected,
                includesUnassigned(state, scope),
                statusFilter,
                query.q(),
                CustomerInputs.toPhoneFragment(query.q())
        );
        boolean descending = query.direction() == ListQuery.Direction.DESC;

        CustomerSearchRepository.Page page = customerSearchRepository.find(
                filter,
                sort,
                descending,
                query.offset(),
                query.pageSize()
        );
        List<CustomerListItem> items = page.rows()
                .stream()
                .map(CustomerService::toListItem)
                .toList();

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public CustomerDetail get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);

        return toDetail(findVisibleCustomer(state, id, scope), state);
    }

    public CustomerDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("registeredBranchId", "name", "phone", "email", "memo");
        long branch = input.id("registeredBranchId");
        String name = CustomerInputs.readName(input);
        String phone = CustomerInputs.readPhone(body);
        String email = CustomerInputs.readEmail(body);
        String memo = CustomerInputs.readMemo(body);
        requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always registeredBranchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(branch, name, phone, email, memo)));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.WRITE_MEMBERS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE_OPERATION, scopeKey, key);

            if (prior.isPresent()) {
                return replay(prior.get(), fingerprint);
            }

            if (!"ACTIVE".equals(state.branches().get(branch))) {
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에는 고객을 등록할 수 없습니다.");
            }

            Customer customer = customerRepository.saveAndFlush(
                    new Customer(createMemberNo(), branch, name, phone, email, memo)
            );
            String after = toJson("memberNo", customer.getMemberNo(), "status", customer.getStatus().name());
            recordChange(actor, branch, customer.getId(), "CREATED", null, after, requestId);

            CustomerDetail result = toDetail(customer, state);
            idempotencyStore.complete(
                    actor,
                    CREATE_OPERATION,
                    scopeKey,
                    key,
                    fingerprint,
                    CREATED_STATUS,
                    objectMapper.writeValueAsString(result),
                    clock.instant().plus(KEY_RETENTION)
            );
            return result;
        });
    }

    public CustomerDetail update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        boolean touchesImmutable = body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains);

        if (touchesImmutable) {
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "회원번호·등록 지점·상태는 이 요청으로 바꿀 수 없습니다.");
        }

        StrictInput input = new StrictInput(body).only("version", "name", "phone", "email", "memo");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String name = input.has("name") ? CustomerInputs.readName(input) : null;
        String phone = input.has("phone") ? CustomerInputs.readPhone(body) : null;
        String email = CustomerInputs.readEmail(body);
        String memo = CustomerInputs.readMemo(body);
        findVisibleCustomer(authorizationService.current(actor), id, scope);

        return authorizedCommands.inAnyBranch(
                actor,
                BranchAction.WRITE_MEMBERS,
                () -> customerRepository.lock(id),
                () -> findRelatedBranches(id),
                state -> {
                    Customer customer = findCustomer(id);
                    requireNotMerged(customer);
                    ApiVersion.requireCurrent(version, customer.getVersion(), toReloadPath(id));

                    String newName = name == null ? customer.getName() : name;
                    String newPhone = phone == null ? customer.getPhone() : phone;
                    String newEmail = input.has("email") ? email : customer.getEmail();
                    String newMemo = input.has("memo") ? memo : customer.getMemo();
                    List<String> changed = findChangedFields(customer, newName, newPhone, newEmail, newMemo);

                    // An identical request is not a change: no version bump, audit row or event.
                    if (changed.isEmpty()) {
                        return toDetail(customer, state);
                    }

                    customer.change(newName, newPhone, newEmail, newMemo);
                    flushChange(customer);
                    String after = objectMapper.writeValueAsString(Map.of("changedFields", changed));
                    recordChange(actor, customer.getRegisteredBranchId(), id, "UPDATED", null, after, requestId);

                    return toDetail(customer, state);
                }
        );
    }

    public CustomerDetail changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        CustomerStatus next = CustomerInputs.toStatus(input.text("status", MAX_STATUS_LENGTH), "status");

        // 병합됨 is set only by a merge (M13-D03), never by a status change.
        if (next == CustomerStatus.MERGED) {
            throw ApiException.validation("status", "병합은 고객 병합으로만 할 수 있습니다.");
        }

        String reason = input.text("reason", MAX_REASON_LENGTH);
        findVisibleCustomer(authorizationService.current(actor), id, scope);

        return authorizedCommands.inAnyBranch(
                actor,
                BranchAction.WRITE_MEMBERS,
                () -> customerRepository.lock(id),
                () -> findRelatedBranches(id),
                state -> {
                    Customer customer = findCustomer(id);
                    requireNotMerged(customer);
                    ApiVersion.requireCurrent(version, customer.getVersion(), toReloadPath(id));
                    CustomerStatus previous = customer.getStatus();

                    if (previous == next) {
                        return toDetail(customer, state);
                    }

                    customerStatusEventRepository.save(new CustomerStatusEvent(id, previous, next, reason, actor));
                    // Payments, passes and visits are left untouched whatever the new status is (FR-MEM-06).
                    customer.changeStatus(next, reason);
                    flushChange(customer);
                    recordChange(
                            actor,
                            customer.getRegisteredBranchId(),
                            id,
                            "STATUS_CHANGED",
                            toJson("status", previous.name()),
                            toJson("status", next.name()),
                            requestId
                    );

                    return toDetail(customer, state);
                }
        );
    }

    /**
     * Gate for another module's view of one customer: the general lookup scope (M4-D05), 404 outside it. An exact-match
     * lookup or a stay at the branch does not count, so this must stay separate from the check-in entry points.
     */
    @Transactional(readOnly = true)
    public CustomerSummary requireVisible(AuthorizationState state, long id, String scope) {
        return toSummary(findVisibleCustomer(state, id, scope));
    }

    /**
     * Target check for an inquiry registered by proxy (M7-D02). The customer must be in the requester's general lookup
     * scope (M4-D05), or the requester must have found the customer by exact match at this same branch (the
     * customer_service_interactions row of that lookup is the proof; the client's claim is never trusted). A cross-branch
     * summary's row is no proof: it takes any ID and withholds the identity on purpose. Anything else is a 404, so an ID is
     * not an existence oracle. The check grants nothing: the general scope is unchanged afterwards. An OWNER without a
     * membership row leaves no interaction row, but every customer is inside the OWNER's scope.
     */
    @Transactional(readOnly = true)
    public CustomerSummary requireInquiryTarget(AuthorizationState state, long id, long branch) {
        Set<Long> selected = authorizationService.scope(state, ALL_SCOPE, BranchAction.READ);
        boolean inScope = customerSearchRepository.isVisible(id, selected, includesUnassigned(state, ALL_SCOPE));

        if (!inScope && !wasLookedUpAt(id, branch, state.employeeId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return toSummary(findCustomer(id));
    }

    /**
     * Entry point for the sale flow, inside the payment transaction. The customer must be in the actor's lookup scope
     * (404 otherwise, POL-08 cross-branch sales are M5) and NORMAL; the shared row lock holds the status until commit.
     */
    @Transactional
    public CustomerSummary requirePurchasable(AuthorizationState state, long id) {
        customerRepository.lockForShare(id);
        Customer customer = findVisibleCustomer(state, id, ALL_SCOPE);

        // A merged customer points the screen to its target (M13-D07).
        if (customer.getStatus() == CustomerStatus.MERGED) {
            throw OperationsConflict.of(
                    "CUSTOMER_NOT_ACTIVE",
                    "병합된 고객입니다. 병합 대상 고객으로 판매해 주세요.",
                    "mergedIntoCustomerId",
                    customer.getMergedIntoCustomerId().toString()
            );
        }

        if (customer.getStatus() != CustomerStatus.NORMAL) {
            throw ApiException.conflict("CUSTOMER_NOT_ACTIVE", "정상 상태가 아닌 고객에게는 판매할 수 없습니다.");
        }

        return toSummary(customer);
    }

    /**
     * Check-in, step one, inside its transaction: a shared lock holds the customer's status until commit. The customer
     * need not be in the actor's lookup scope (M5-D05), and an unknown id locks nothing and fails on the pass check.
     */
    @Transactional
    public void lockForCheckIn(long id) {
        customerRepository.lockForShare(id);
    }

    /** Check-in, step two, after the pass is confirmed to be this customer's (the contract's check order). */
    @Transactional(readOnly = true)
    public void requireCheckInAllowed(long id) {
        Customer customer = findCustomer(id);

        if (customer.getStatus() == CustomerStatus.MERGED) {
            throw OperationsConflict.of(
                    "CUSTOMER_NOT_ACTIVE",
                    "병합된 고객입니다. 병합 대상 고객으로 입실해 주세요.",
                    "mergedIntoCustomerId",
                    customer.getMergedIntoCustomerId().toString()
            );
        }

        if (customer.getStatus() != CustomerStatus.NORMAL) {
            throw ApiException.conflict("CUSTOMER_NOT_ACTIVE", "정상 상태가 아닌 고객은 입실할 수 없습니다.");
        }
    }

    /** The phone form of the exact-match lookup: formatting removed, 9~11 digits (M5-D05). */
    public String normalizedPhone(Map<String, Object> body) {
        return CustomerInputs.readPhone(body);
    }

    /**
     * Exact matches only, for the check-in lookup (M5-D05). The database comparison ignores case and accents, so the
     * value is compared again in full: "m-abc" must not find "M-ABC".
     */
    @Transactional(readOnly = true)
    public List<ExactCustomerMatch> matchMemberNo(String memberNo) {
        return customerRepository.findByMemberNo(memberNo)
                .filter(customer -> memberNo.equals(customer.getMemberNo()))
                .map(this::resolveMergeTarget)
                .map(CustomerService::toMatch)
                .stream()
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ExactCustomerMatch> matchPhone(String digits) {
        // A merged customer is found as its target (M13-D07), once.
        Set<Long> seen = new HashSet<>();

        return customerRepository.findTop10ByPhoneOrderByMemberNoAsc(digits)
                .stream()
                .filter(customer -> digits.equals(customer.getPhone()))
                .map(this::resolveMergeTarget)
                .filter(customer -> seen.add(customer.getId()))
                .map(CustomerService::toMatch)
                .toList();
    }

    /** The access trail of an exact-match result (M5-D07); needs a membership row of (branch, employee). */
    @Transactional
    public void recordLookup(long customerId, long branch, long employee, String reason, Instant at) {
        recordInteraction(customerId, branch, employee, reason, CustomerInteractionSource.CHECK_IN_LOOKUP, at);
    }

    /** The access trail of a cross-branch summary (M6-D06); needs a membership row of (branch, employee). */
    @Transactional
    public void recordCrossBranchSummary(long customerId, long branch, long employee, String reason, Instant at) {
        recordInteraction(customerId, branch, employee, reason, CustomerInteractionSource.CROSS_BRANCH_SUMMARY, at);
    }

    /** Identification for payment and pass views; the caller has already authorized the rows that name them. */
    @Transactional(readOnly = true)
    public Map<Long, CustomerSummary> summaries(Collection<Long> ids) {
        Map<Long, CustomerSummary> result = new HashMap<>();
        customerRepository.findAllById(Set.copyOf(ids))
                .forEach(customer -> result.put(customer.getId(), toSummary(customer)));

        return result;
    }

    private CustomerDetail replay(IdempotencyRecord prior, byte[] fingerprint) {
        if (!MessageDigest.isEqual(fingerprint, prior.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return objectMapper.readValue(prior.getResponseJson(), CustomerDetail.class);
    }

    private void recordInteraction(
            long customerId,
            long branch,
            long employee,
            String reason,
            CustomerInteractionSource source,
            Instant at
    ) {
        customerServiceInteractionRepository.save(
                new CustomerServiceInteraction(customerId, branch, employee, reason, source, at)
        );
    }

    private boolean wasLookedUpAt(long id, long branch, long employee) {
        return customerServiceInteractionRepository.existsByCustomerIdAndBranchIdAndEmployeeIdAndSource(
                id,
                branch,
                employee,
                CustomerInteractionSource.CHECK_IN_LOOKUP
        );
    }

    private void recordChange(
            long actor,
            Long branch,
            long id,
            String action,
            String before,
            String after,
            String requestId
    ) {
        auditTrail.record(AuditEvent.change(actor, branch, AUDIT_ENTITY_TYPE, id, action, before, after, requestId));
    }

    /** The customer itself, or the target a merged customer was merged into (M13-D07). */
    private Customer resolveMergeTarget(Customer customer) {
        if (customer.getStatus() == CustomerStatus.MERGED) {
            return findCustomer(customer.getMergedIntoCustomerId());
        }

        return customer;
    }

    private void flushChange(Customer customer) {
        long before = customer.getVersion();

        try {
            customerRepository.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw ApiException.versionConflict(ApiVersion.fromDatabase(before + 1), toReloadPath(customer.getId()));
        }
    }

    /** Branches that put the customer in scope: registration plus every branch with a payment. */
    private Set<Long> findRelatedBranches(long id) {
        Set<Long> branches = new TreeSet<>();
        customerRepository.findById(id)
                .map(Customer::getRegisteredBranchId)
                .ifPresent(branches::add);
        customerRepository.findPaymentBranchIds(id)
                .forEach(branch -> branches.add(branch.longValue()));

        return branches;
    }

    private Customer findCustomer(long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 404 hides customers outside the reader's scope, so IDs are not an existence oracle. */
    private Customer findVisibleCustomer(AuthorizationState state, long id, String scope) {
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        if (!customerSearchRepository.isVisible(id, selected, includesUnassigned(state, scope))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return findCustomer(id);
    }

    private CustomerDetail toDetail(Customer customer, AuthorizationState state) {
        boolean editable = authorizationService.permitsAny(
                state,
                findRelatedBranches(customer.getId()),
                BranchAction.WRITE_MEMBERS
        );
        Long mergedIntoId = customer.getMergedIntoCustomerId();
        CustomerSummary mergedInto = mergedIntoId == null ? null : toSummary(findCustomer(mergedIntoId));

        return new CustomerDetail(
                Long.toString(customer.getId()),
                ApiVersion.fromDatabase(customer.getVersion()),
                customer.getMemberNo(),
                customer.getName(),
                customer.getPhone(),
                customer.getEmail(),
                customer.getMemo(),
                CustomerInputs.toLabel(customer.getStatus()),
                customer.getStatusReason(),
                toBranchId(customer),
                customer.getRegisteredAt(),
                customer.getUpdatedAt(),
                editable,
                mergedInto,
                customerRepository.countByMergedIntoCustomerId(customer.getId())
        );
    }

    private String toJson(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();

        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }

        return objectMapper.writeValueAsString(values);
    }

    /** Customers without a registration branch (pre-V2 rows) are OWNER-only and not tied to any single branch scope. */
    private static boolean includesUnassigned(AuthorizationState state, String scope) {
        return state.owner() && (scope == null || ALL_SCOPE.equals(scope));
    }

    /** A merged customer is only a record now: it is not edited (M13-D03). */
    private static void requireNotMerged(Customer customer) {
        if (customer.getStatus() == CustomerStatus.MERGED) {
            throw OperationsConflict.of(
                    "CUSTOMER_ALREADY_MERGED",
                    "병합된 고객은 바꿀 수 없습니다.",
                    "mergedIntoCustomerId",
                    customer.getMergedIntoCustomerId().toString()
            );
        }
    }

    private static List<String> findChangedFields(
            Customer customer,
            String newName,
            String newPhone,
            String newEmail,
            String newMemo
    ) {
        List<String> changed = new ArrayList<>();

        if (!newName.equals(customer.getName())) {
            changed.add("name");
        }

        if (!Objects.equals(newPhone, customer.getPhone())) {
            changed.add("phone");
        }

        if (!Objects.equals(newEmail, customer.getEmail())) {
            changed.add("email");
        }

        if (!Objects.equals(newMemo, customer.getMemo())) {
            changed.add("memo");
        }

        return changed;
    }

    private static ExactCustomerMatch toMatch(Customer customer) {
        String status = CustomerInputs.toLabel(customer.getStatus());

        return new ExactCustomerMatch(customer.getId(), toSummary(customer), status);
    }

    private static CustomerSummary toSummary(Customer customer) {
        return new CustomerSummary(Long.toString(customer.getId()), customer.getMemberNo(), customer.getName());
    }

    private static CustomerListItem toListItem(Customer customer) {
        return new CustomerListItem(
                Long.toString(customer.getId()),
                customer.getMemberNo(),
                customer.getName(),
                customer.getPhone(),
                CustomerInputs.toLabel(customer.getStatus()),
                toBranchId(customer),
                customer.getRegisteredAt()
        );
    }

    private static String toBranchId(Customer customer) {
        return customer.getRegisteredBranchId() == null ? null : customer.getRegisteredBranchId().toString();
    }

    /** Opaque, server-made and immutable; the format is not part of the contract. */
    private static String createMemberNo() {
        StringBuilder value = new StringBuilder(MEMBER_NO_PREFIX);

        for (int index = 0; index < MEMBER_NO_LENGTH; index++) {
            value.append(MEMBER_NO[RANDOM.nextInt(MEMBER_NO.length)]);
        }

        return value.toString();
    }

    private static String toReloadPath(long id) {
        return "/api/customers/" + id;
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
}
