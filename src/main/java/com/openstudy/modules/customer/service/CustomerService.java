package com.openstudy.modules.customer.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.openstudy.modules.customer.dto.response.CustomerDetail;
import com.openstudy.modules.customer.dto.response.CustomerListItem;
import com.openstudy.modules.customer.entity.Customer;
import com.openstudy.modules.customer.entity.CustomerStatus;
import com.openstudy.modules.customer.entity.CustomerStatusEvent;
import com.openstudy.modules.customer.repository.CustomerRepository;
import com.openstudy.modules.customer.repository.CustomerSearchRepository;
import com.openstudy.modules.customer.repository.CustomerStatusEventRepository;

/**
 * Customer ledger (FR-MEM-01/02/04/05/06). A customer is visible to a branch where it registered or paid (M4-D05);
 * reads need only a valid membership there, writes need members:write in any one of those branches. Audit rows carry
 * field names and statuses, not the personal values themselves, so the append-only log does not copy contact data.
 */
@Service
@RequiredArgsConstructor
public class CustomerService {
    private static final String CREATE = "customers:create";
    private static final Set<String> IMMUTABLE = Set.of("memberNo", "registeredBranchId", "status", "statusReason");
    private static final char[] MEMBER_NO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CustomerRepository repository;
    private final CustomerStatusEventRepository statusEvents;
    private final CustomerSearchRepository search;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<CustomerListItem> list(long actor, String scope, ListQuery query, String status) {
        CustomerStatus filter = status == null ? null : CustomerInputs.status(status, "status");
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var sort = "name".equals(query.sort()) ? CustomerSearchRepository.Sort.NAME : CustomerSearchRepository.Sort.REGISTERED_AT;
        var page = search.find(new CustomerSearchRepository.Filter(selected, unassigned(state, scope), filter, query.q(),
                CustomerInputs.phoneFragment(query.q())), sort, query.direction() == ListQuery.Direction.DESC,
                query.offset(), query.pageSize());
        return new PageResponse<>(page.rows().stream().map(CustomerService::item).toList(),
                new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public CustomerDetail get(long actor, long id, String scope) {
        var state = policy.current(actor);
        return detail(visibleCustomer(state, id, scope), state);
    }

    public CustomerDetail create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("registeredBranchId", "name", "phone", "email", "memo");
        long branch = input.id("registeredBranchId");
        String name = CustomerInputs.name(input);
        String phone = CustomerInputs.phone(body);
        String email = CustomerInputs.email(body);
        String memo = CustomerInputs.memo(body);
        validKey(key);
        // scope only has to be well-formed and readable; the target is always registeredBranchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, name, phone, email, memo)));
        return commands.inBranches(actor, Set.of(branch), BranchAction.WRITE_MEMBERS, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            if (prior.isPresent()) {
                if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.get().getResponseJson(), CustomerDetail.class);
            }
            if (!"ACTIVE".equals(state.branches().get(branch)))
                throw ApiException.conflict("BRANCH_NOT_ACTIVE", "운영 중지된 지점에는 고객을 등록할 수 없습니다.");
            var customer = repository.saveAndFlush(new Customer(memberNo(), branch, name, phone, email, memo));
            audit.record(AuditEvent.change(actor, branch, "customers", customer.getId(), "CREATED", null,
                    summary("memberNo", customer.getMemberNo(), "status", customer.getStatus().name()), requestId));
            invalidate(customer);
            var result = detail(customer, state);
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    public CustomerDetail update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "회원번호·등록 지점·상태는 이 요청으로 바꿀 수 없습니다.");
        var input = new StrictInput(body).only("version", "name", "phone", "email", "memo");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String name = input.has("name") ? CustomerInputs.name(input) : null;
        String phone = input.has("phone") ? CustomerInputs.phone(body) : null;
        String email = CustomerInputs.email(body);
        String memo = CustomerInputs.memo(body);
        visibleCustomer(policy.current(actor), id, scope);
        return commands.inAnyBranch(actor, BranchAction.WRITE_MEMBERS, () -> repository.lock(id), () -> related(id), state -> {
            var customer = lockedCustomer(id);
            ApiVersion.requireCurrent(version, customer.getVersion(), reload(id));
            String newName = name == null ? customer.getName() : name;
            String newPhone = phone == null ? customer.getPhone() : phone;
            String newEmail = input.has("email") ? email : customer.getEmail();
            String newMemo = input.has("memo") ? memo : customer.getMemo();
            List<String> changed = new ArrayList<>();
            if (!newName.equals(customer.getName())) changed.add("name");
            if (!Objects.equals(newPhone, customer.getPhone())) changed.add("phone");
            if (!Objects.equals(newEmail, customer.getEmail())) changed.add("email");
            if (!Objects.equals(newMemo, customer.getMemo())) changed.add("memo");
            // An identical request is not a change: no version bump, audit row or event.
            if (changed.isEmpty()) return detail(customer, state);
            customer.change(newName, newPhone, newEmail, newMemo);
            flush(customer);
            audit.record(AuditEvent.change(actor, customer.getRegisteredBranchId(), "customers", id, "UPDATED", null,
                    json.writeValueAsString(Map.of("changedFields", changed)), requestId));
            invalidate(customer);
            return detail(customer, state);
        });
    }

    public CustomerDetail changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "status", "reason");
        long version = input.version("version");
        CustomerStatus next = CustomerInputs.status(input.text("status", 30), "status");
        String reason = input.text("reason", 500);
        visibleCustomer(policy.current(actor), id, scope);
        return commands.inAnyBranch(actor, BranchAction.WRITE_MEMBERS, () -> repository.lock(id), () -> related(id), state -> {
            var customer = lockedCustomer(id);
            ApiVersion.requireCurrent(version, customer.getVersion(), reload(id));
            CustomerStatus previous = customer.getStatus();
            if (previous == next) return detail(customer, state);
            statusEvents.save(new CustomerStatusEvent(id, previous, next, reason, actor));
            // Payments, passes and visits are left untouched whatever the new status is (FR-MEM-06).
            customer.changeStatus(next, reason);
            flush(customer);
            audit.record(AuditEvent.change(actor, customer.getRegisteredBranchId(), "customers", id, "STATUS_CHANGED",
                    summary("status", previous.name()), summary("status", next.name()), requestId));
            invalidate(customer);
            return detail(customer, state);
        });
    }

    private void flush(Customer customer) {
        long before = customer.getVersion();
        try {
            repository.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw ApiException.versionConflict(ApiVersion.fromDatabase(before + 1), reload(customer.getId()));
        }
    }

    /** Branches that put the customer in scope: registration plus every branch with a payment. */
    private Set<Long> related(long id) {
        Set<Long> branches = new TreeSet<>();
        repository.findById(id).map(Customer::getRegisteredBranchId).ifPresent(branches::add);
        repository.paymentBranchIds(id).forEach(branch -> branches.add(branch.longValue()));
        return branches;
    }

    /** The event goes to the registration branch only; an unassigned customer (OWNER-only) has none to notify. */
    private void invalidate(Customer customer) {
        if (customer.getRegisteredBranchId() != null)
            outbox.invalidate(customer.getRegisteredBranchId(), "customers", customer.getId());
    }

    private Customer lockedCustomer(long id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 404 hides customers outside the reader's scope, so IDs are not an existence oracle. */
    private Customer visibleCustomer(AuthorizationState state, long id, String scope) {
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        if (!search.visible(id, selected, unassigned(state, scope))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return lockedCustomer(id);
    }

    /** Customers without a registration branch (pre-V2 rows) are OWNER-only and not tied to any single branch scope. */
    private static boolean unassigned(AuthorizationState state, String scope) {
        return state.owner() && (scope == null || "all".equals(scope));
    }

    private CustomerDetail detail(Customer customer, AuthorizationState state) {
        boolean editable = policy.permitsAny(state, related(customer.getId()), BranchAction.WRITE_MEMBERS);
        return new CustomerDetail(Long.toString(customer.getId()), ApiVersion.fromDatabase(customer.getVersion()),
                customer.getMemberNo(), customer.getName(), customer.getPhone(), customer.getEmail(), customer.getMemo(),
                CustomerInputs.label(customer.getStatus()), customer.getStatusReason(), branchId(customer),
                customer.getRegisteredAt(), customer.getUpdatedAt(), editable);
    }

    private static CustomerListItem item(Customer customer) {
        return new CustomerListItem(Long.toString(customer.getId()), customer.getMemberNo(), customer.getName(),
                customer.getPhone(), CustomerInputs.label(customer.getStatus()), branchId(customer), customer.getRegisteredAt());
    }

    private static String branchId(Customer customer) {
        return customer.getRegisteredBranchId() == null ? null : customer.getRegisteredBranchId().toString();
    }

    private String summary(String... pairs) {
        var values = new LinkedHashMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) values.put(pairs[index], pairs[index + 1]);
        return json.writeValueAsString(values);
    }

    /** Opaque, server-made and immutable; the format is not part of the contract. */
    private static String memberNo() {
        var value = new StringBuilder("M-");
        for (int index = 0; index < 10; index++) value.append(MEMBER_NO[RANDOM.nextInt(MEMBER_NO.length)]);
        return value.toString();
    }

    private static String reload(long id) { return "/api/customers/" + id; }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
