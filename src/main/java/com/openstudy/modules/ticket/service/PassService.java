package com.openstudy.modules.ticket.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.ticket.dto.CrossBranchPass;
import com.openstudy.modules.ticket.dto.IssuedPass;
import com.openstudy.modules.ticket.dto.PassLedger;
import com.openstudy.modules.ticket.dto.PassSettlement;
import com.openstudy.modules.ticket.dto.PassUsage;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.dto.SaleTerms;
import com.openstudy.modules.ticket.dto.SessionPass;
import com.openstudy.modules.ticket.dto.UsablePass;
import com.openstudy.modules.ticket.entity.Pass;
import com.openstudy.modules.ticket.entity.PassBalanceEvent;
import com.openstudy.modules.ticket.entity.PassStatus;
import com.openstudy.modules.ticket.entity.ProductKind;
import com.openstudy.modules.ticket.repository.PassBalanceEventRepository;
import com.openstudy.modules.ticket.repository.PassRepository;
import com.openstudy.modules.ticket.repository.PassSearchRepository;

/**
 * Issued passes. Issuing happens only inside the payment transaction (FR-PAY-08); reads are limited to passes bought
 * at a branch the reader belongs to, whatever the role or grants, because front-desk work needs them (contract
 * "지점 범위"). The response status is computed at the request instant, so an unexpired-looking stored ACTIVE row past
 * its expiry is reported as expired (NFR-DATA-05).
 */
@Service
@RequiredArgsConstructor
public class PassService {
    private static final String ACTIVE = "활성";
    private static final String EXHAUSTED = "소진";
    private static final String EXPIRED = "만료";
    private static final String REFUNDED = "환불";
    private static final Set<PassStatus> HELD = Set.of(PassStatus.ACTIVE, PassStatus.EXHAUSTED, PassStatus.EXPIRED, PassStatus.REFUNDED);
    private final PassRepository repository;
    private final PassSearchRepository search;
    private final PassBalanceEventRepository ledger;
    private final CustomerService customers;
    private final AuthorizationService policy;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final Clock clock;

    /** Call inside the payment transaction, after the payment row exists; a failure here rolls the payment back too. */
    @Transactional
    public long issue(long actor, long customerId, long branchId, long paymentId, SaleTerms sale, Instant issuedAt,
            String requestId) {
        var snapshot = sale.snapshot();
        var columns = ProductViews.columns(snapshot.terms());
        var pass = repository.saveAndFlush(Pass.issue(customerId, sale.productId(), paymentId, branchId, sale.revisionId(),
                new Pass.Snapshot(ProductViews.kind(snapshot.terms().kind()), snapshot.priceKrw(), snapshot.name(),
                        snapshot.categoryCode(), snapshot.categoryName(), columns.includedMinutes(), columns.periodDays(),
                        columns.creditKrw(), columns.hourlyRateKrw(), columns.validDays()), issuedAt));
        var after = new java.util.LinkedHashMap<String, Object>();
        after.put("purchasePaymentId", Long.toString(paymentId));
        after.put("purchaseProductRevisionId", Long.toString(sale.revisionId()));
        after.put("status", pass.getStatus().name());
        after.put("startedAt", String.valueOf(pass.getStartedAt()));
        after.put("expiresAt", String.valueOf(pass.getExpiresAt()));
        audit.record(AuditEvent.change(actor, branchId, "passes", pass.getId(), "ISSUED", null, json.writeValueAsString(after), requestId));
        outbox.invalidate(branchId, "passes", pass.getId());
        return pass.getId();
    }

    /**
     * The pass half of a refund, inside the refund transaction after the payment row lock. Usage is judged from
     * history and expiry from {@code now}, never from the stored status (ADR-M4-002 "미사용·만료 판정"), so a stored
     * ACTIVE pass past its expiry is refused too. A refusal rolls the whole refund back.
     */
    @Transactional
    public long refund(long actor, long paymentId, Instant now, String requestId) {
        repository.lockByPayment(paymentId);
        var pass = repository.findByPurchasePaymentId(paymentId)
                .orElseThrow(() -> new IllegalStateException("Approved payment " + paymentId + " has no pass"));
        if (!usedIds(List.of(pass)).isEmpty())
            throw ApiException.conflict("PASS_ALREADY_USED", "사용 이력이 있는 이용권은 환불할 수 없습니다.");
        if (expired(pass, now)) throw ApiException.conflict("PASS_EXPIRED", "만료된 이용권은 환불할 수 없습니다.");
        String before = pass.getStatus().name();
        pass.refund();
        repository.saveAndFlush(pass);
        audit.record(AuditEvent.change(actor, pass.getPurchaseBranchId(), "passes", pass.getId(), "REFUNDED",
                json.writeValueAsString(Map.of("status", before)),
                json.writeValueAsString(Map.of("status", pass.getStatus().name())), requestId));
        outbox.invalidate(pass.getPurchaseBranchId(), "passes", pass.getId());
        return pass.getId();
    }

    /**
     * Check-in: locks the pass for the rest of the transaction. Passes work at every active branch (M5-D01), so the only
     * ownership test is the customer; another customer's pass answers the same 404 as a missing one.
     */
    @Transactional
    public void lockOwned(long id, long customerId) {
        repository.locked(id).filter(pass -> pass.getCustomerId() == customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Check-in, on a pass held by {@link #lockOwned}. Refunded > expired > exhausted, the contract's order. Expiry is
     * read from the clock even while the stored status is ACTIVE; a pass without expiry never fails it (FR-PASS-10).
     */
    @Transactional
    public void requireUsable(long id, Instant now) {
        var pass = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (pass.getStatus() == PassStatus.REFUNDED) throw ApiException.conflict("PASS_REFUNDED", "환불된 이용권입니다.");
        if (expired(pass, now)) throw ApiException.conflict("PASS_EXPIRED", "만료된 이용권입니다.");
        if (pass.getStatus() == PassStatus.EXHAUSTED || pass.balanceUsedUp())
            throw ApiException.conflict("PASS_EXHAUSTED", "잔량이 소진된 이용권입니다.");
        // NOT_STARTED survives only in the V1 enum (ADR-M4-002); the contract has no code for it because none is issued.
        if (pass.getStatus() != PassStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT);
    }

    /**
     * Check-out, after the session row lock: locks the pass, computes what the stay cost from the pass as it stands now,
     * writes the single USE row when something was deducted and lowers the balance. Should a USE row exist already, the
     * unique key rolls everything back: a session is never charged twice, whatever got past the checks above it.
     */
    @Transactional
    public PassSettlement settle(long actor, long id, long sessionId, Instant in, Instant out, String reason) {
        var pass = repository.locked(id).orElseThrow(() -> new IllegalStateException("Seat session " + sessionId + " has no pass"));
        var settled = PassSettlementCalculator.settle(pass.getKind(), in, out, pass.getExpiresAt(), pass.getRemainingMinutes(),
                pass.getRemainingCreditKrw(), pass.getSnapshotHourlyRateKrw());
        if (settled.deductedMinutes() + settled.deductedKrw() > 0) recordUse(actor, id, sessionId, settled, reason, out);
        pass.deduct(settled.deductedMinutes(), settled.deductedKrw());
        repository.saveAndFlush(pass);
        // The purchase branch's screens show this balance, wherever the pass was used (seat-usage-api.md).
        outbox.invalidate(pass.getPurchaseBranchId(), "passes", id);
        return settled;
    }

    private void recordUse(long actor, long id, long sessionId, PassSettlement settled, String reason, Instant at) {
        try {
            DuplicateKeys.translate(() -> ledger.saveAndFlush(PassBalanceEvent.use(id, sessionId, settled.deductedMinutes(),
                    settled.deductedKrw(), actor, reason, at)));
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("SESSION_ALREADY_CLOSED", "이미 차감된 이용 기록입니다.");
        }
    }

    /** The pass behind a seat session, for a caller that has already authorized the session (M5-D06). */
    @Transactional(readOnly = true)
    public SessionPass sessionPass(long id) {
        return sessionPass(repository.findById(id).orElseThrow(() -> new IllegalStateException("Pass " + id + " is missing")));
    }

    private static SessionPass sessionPass(Pass pass) {
        return new SessionPass(Long.toString(pass.getId()), ProductViews.apiKind(pass.getKind()), pass.getSnapshotProductName(),
                Long.toString(pass.getPurchaseBranchId()), pass.getRemainingMinutes(), pass.getRemainingCreditKrw());
    }

    /** The same for a page of sessions, in one read. */
    @Transactional(readOnly = true)
    public Map<Long, SessionPass> sessionPasses(Collection<Long> ids) {
        Map<Long, SessionPass> result = new HashMap<>();
        repository.findAllById(Set.copyOf(ids)).forEach(pass -> result.put(pass.getId(), sessionPass(pass)));
        return result;
    }

    /**
     * Passes that could check in at {@code now}, by customer, for the exact-match lookup. This reaches beyond the purchase
     * branch scope of {@link #list} on purpose (M5-D01·D05), so it returns only the minimal {@link UsablePass}.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<UsablePass>> usableBy(Collection<Long> customerIds, Instant now) {
        Map<Long, List<UsablePass>> result = new LinkedHashMap<>();
        for (var pass : repository.findByCustomerIdInAndStatusOrderByIdAsc(Set.copyOf(customerIds), PassStatus.ACTIVE)) {
            if (expired(pass, now) || pass.balanceUsedUp()) continue;
            result.computeIfAbsent(pass.getCustomerId(), customer -> new ArrayList<>()).add(new UsablePass(Long.toString(pass.getId()),
                    ProductViews.apiKind(pass.getKind()), pass.getSnapshotProductName(), pass.getRemainingMinutes(),
                    pass.getRemainingCreditKrw(), pass.getExpiresAt(), Long.toString(pass.getPurchaseBranchId())));
        }
        return result;
    }

    /**
     * Every pass the customer holds, wherever it was bought, for the cross-branch summary (M6-D06). This reaches beyond
     * the purchase branch scope of {@link #list} on purpose, so it returns only the minimal {@link CrossBranchPass}.
     * NOT_STARTED is left out: no such pass is issued (M4-D01) and the contract has no label for one.
     */
    @Transactional(readOnly = true)
    public List<CrossBranchPass> heldBy(long customerId, Instant now) {
        return repository.findByCustomerIdAndStatusInOrderByIdDesc(customerId, HELD).stream().map(pass ->
                new CrossBranchPass(ProductViews.apiKind(pass.getKind()), pass.getSnapshotProductName(), status(pass, now), pass.getExpiresAt())).toList();
    }

    /** Refund state of the passes bought by these payments, keyed by payment id. */
    @Transactional(readOnly = true)
    public Map<Long, PassUsage> byPayments(Collection<Long> paymentIds, Instant now) {
        if (paymentIds.isEmpty()) return Map.of();
        var passes = repository.findByPurchasePaymentIdIn(Set.copyOf(paymentIds));
        Set<Long> used = usedIds(passes);
        Map<Long, PassUsage> result = new HashMap<>();
        for (var pass : passes) {
            result.put(pass.getPurchasePaymentId(), new PassUsage(pass.getId(), pass.getPurchaseBranchId(),
                    used.contains(pass.getId()), expired(pass, now), pass.getStatus() == PassStatus.REFUNDED));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<IssuedPass> list(long actor, String scope, ListQuery query, String customerId, String status, String kind) {
        Long customer = customerId == null ? null : ProductInputs.id(customerId, "customerId");
        String effective = status == null ? null : switch (status) {
            case ACTIVE -> "ACTIVE";
            case EXHAUSTED -> "EXHAUSTED";
            case EXPIRED -> "EXPIRED";
            case REFUNDED -> "REFUNDED";
            default -> throw ApiException.validation("status", "올바른 이용권 상태를 선택해 주세요.");
        };
        if (kind != null && !Set.of(ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE).contains(kind))
            throw ApiException.validation("kind", "올바른 이용권 유형을 선택해 주세요.");
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        Instant now = clock.instant();
        var sort = "expiresAt".equals(query.sort()) ? PassSearchRepository.Sort.EXPIRES_AT : PassSearchRepository.Sort.CREATED_AT;
        var page = search.find(new PassSearchRepository.Filter(selected, customer, effective,
                kind == null ? null : ProductViews.kind(kind), now), sort, query.direction() == ListQuery.Direction.DESC,
                query.offset(), query.pageSize());
        return new PageResponse<>(views(page.rows(), state, now), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides passes bought outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public IssuedPass get(long actor, long id, String scope) {
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var pass = repository.findById(id).filter(found -> selected.contains(found.getPurchaseBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return views(List.of(pass), state, clock.instant()).getFirst();
    }

    /**
     * The customer's passes bought at these branches with their whole ledger, whatever period a history asks for: the
     * reconciliation issued + ledger = remaining only holds over every row (FR-PASS-09). The rows name who changed the
     * balance, so the caller must withhold them for branches it may not show.
     */
    @Transactional(readOnly = true)
    public List<PassLedger> ledgers(long customerId, Set<Long> purchaseBranches, Instant now) {
        if (purchaseBranches.isEmpty()) return List.of();
        var owned = repository.findByCustomerIdAndPurchaseBranchIdInOrderByIdAsc(customerId, purchaseBranches);
        if (owned.isEmpty()) return List.of();
        Map<Long, List<PassBalanceEvent>> rows = ledger.findByPassIdInOrderByIdAsc(owned.stream().map(Pass::getId).toList())
                .stream().collect(Collectors.groupingBy(PassBalanceEvent::getPassId));
        return owned.stream().map(pass -> ledger(pass, rows.getOrDefault(pass.getId(), List.of()), now)).toList();
    }

    private static PassLedger ledger(Pass pass, List<PassBalanceEvent> rows, Instant now) {
        var entries = rows.stream().map(row -> new PassLedger.Entry(row.getKind() == PassBalanceEvent.Kind.ADJUST, row.getSeatSessionId(),
                row.getDeltaMinutes(), row.getDeltaKrw(), row.getActorEmployeeId(), row.getReason(), row.getCreatedAt())).toList();
        return new PassLedger(pass.getId(), ProductViews.apiKind(pass.getKind()), pass.getSnapshotProductName(), status(pass, now),
                pass.getPurchaseBranchId(), pass.getPurchasePaymentId(), pass.getSnapshotMinutes(), pass.getSnapshotCreditKrw(),
                rows.stream().mapToLong(PassBalanceEvent::getDeltaMinutes).sum(), rows.stream().mapToLong(PassBalanceEvent::getDeltaKrw).sum(),
                pass.getRemainingMinutes(), pass.getRemainingCreditKrw(), entries);
    }

    /** One pass as the API shows it to {@code state}; for a caller that has authorized the pass already. */
    public IssuedPass view(Pass pass, AuthorizationState state, Instant now) {
        return views(List.of(pass), state, now).getFirst();
    }

    private List<IssuedPass> views(List<Pass> passes, AuthorizationState state, Instant now) {
        Set<Long> used = usedIds(passes);
        Map<Long, CustomerSummary> names = customers.summaries(passes.stream().map(Pass::getCustomerId).toList());
        return passes.stream().map(pass -> {
            String status = status(pass, now);
            boolean wasUsed = used.contains(pass.getId());
            boolean canRefund = ACTIVE.equals(status) && !wasUsed
                    && policy.permits(state, pass.getPurchaseBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);
            return new IssuedPass(Long.toString(pass.getId()), ApiVersion.fromDatabase(pass.getVersion()),
                    names.get(pass.getCustomerId()), ProductViews.apiKind(pass.getKind()), Long.toString(pass.getProductId()),
                    pass.getSnapshotProductName(), pass.getSnapshotCategoryName(), terms(pass), pass.getRemainingMinutes(),
                    pass.getRemainingCreditKrw(), status, pass.getStartedAt(), pass.getExpiresAt(),
                    Long.toString(pass.getPurchaseBranchId()), Long.toString(pass.getPurchasePaymentId()), pass.getCreatedAt(),
                    wasUsed, canRefund);
        }).toList();
    }

    private Set<Long> usedIds(List<Pass> passes) {
        if (passes.isEmpty()) return Set.of();
        Set<Long> result = new HashSet<>();
        repository.usedIds(passes.stream().map(Pass::getId).toList()).forEach(id -> result.add(id.longValue()));
        return result;
    }

    /** Refunded > expired > exhausted > active (FR-PASS-11); the boundary is [startedAt, expiresAt). */
    private static String status(Pass pass, Instant now) {
        if (pass.getStatus() == PassStatus.REFUNDED) return REFUNDED;
        if (expired(pass, now)) return EXPIRED;
        if (pass.getStatus() == PassStatus.EXHAUSTED) return EXHAUSTED;
        return ACTIVE;
    }

    private static boolean expired(Pass pass, Instant now) {
        return pass.getStatus() == PassStatus.EXPIRED || (pass.getExpiresAt() != null && !now.isBefore(pass.getExpiresAt()));
    }

    private static ProductTerms terms(Pass pass) {
        return switch (pass.getKind()) {
            case TIME -> ProductTerms.Time.of(pass.getSnapshotMinutes(), pass.getSnapshotValidDays());
            case PERIOD -> ProductTerms.Period.of(pass.getSnapshotPeriodDays());
            case STORED_VALUE -> ProductTerms.StoredValue.of(pass.getSnapshotCreditKrw(), pass.getSnapshotHourlyRateKrw(),
                    pass.getSnapshotValidDays());
        };
    }
}
