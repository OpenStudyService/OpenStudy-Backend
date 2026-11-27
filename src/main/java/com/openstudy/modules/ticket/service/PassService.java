package com.openstudy.modules.ticket.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import com.openstudy.modules.ticket.dto.ProductSnapshot;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.dto.SaleTerms;
import com.openstudy.modules.ticket.dto.SessionPass;
import com.openstudy.modules.ticket.dto.UsablePass;
import com.openstudy.modules.ticket.entity.Pass;
import com.openstudy.modules.ticket.entity.PassBalanceEvent;
import com.openstudy.modules.ticket.entity.PassStatus;
import com.openstudy.modules.ticket.entity.Product;
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
    private static final Set<PassStatus> HELD = Set.of(
            PassStatus.ACTIVE,
            PassStatus.EXHAUSTED,
            PassStatus.EXPIRED,
            PassStatus.REFUNDED
    );
    private static final Set<String> KINDS = Set.of(ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE);
    private static final String AUDIT_TABLE = "passes";

    private final PassRepository passRepository;
    private final PassSearchRepository passSearchRepository;
    private final PassBalanceEventRepository passBalanceEventRepository;
    private final CustomerService customerService;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final Clock clock;

    /** Call inside the payment transaction, after the payment row exists; a failure here rolls the payment back too. */
    @Transactional
    public long issue(
            long actor,
            long customerId,
            long branchId,
            long paymentId,
            SaleTerms sale,
            Instant issuedAt,
            String requestId
    ) {
        ProductSnapshot snapshot = sale.snapshot();
        Product.Terms columns = ProductViews.toColumns(snapshot.terms());
        Pass.Snapshot terms = new Pass.Snapshot(
                ProductViews.toKind(snapshot.terms().kind()),
                snapshot.priceKrw(),
                snapshot.name(),
                snapshot.categoryCode(),
                snapshot.categoryName(),
                columns.includedMinutes(),
                columns.periodDays(),
                columns.creditKrw(),
                columns.hourlyRateKrw(),
                columns.validDays()
        );
        Pass newPass = Pass.issue(
                customerId,
                sale.productId(),
                paymentId,
                branchId,
                sale.revisionId(),
                terms,
                issuedAt
        );
        Pass pass = passRepository.saveAndFlush(newPass);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("purchasePaymentId", Long.toString(paymentId));
        after.put("purchaseProductRevisionId", Long.toString(sale.revisionId()));
        after.put("status", pass.getStatus().name());
        after.put("startedAt", String.valueOf(pass.getStartedAt()));
        after.put("expiresAt", String.valueOf(pass.getExpiresAt()));
        auditTrail.record(AuditEvent.change(actor, branchId, AUDIT_TABLE, pass.getId(), "ISSUED", null,
                objectMapper.writeValueAsString(after), requestId));

        return pass.getId();
    }

    /**
     * The pass half of a refund, inside the refund transaction after the payment row lock. Usage is judged from
     * history and expiry from {@code now}, never from the stored status (ADR-M4-002 "미사용·만료 판정"), so a stored
     * ACTIVE pass past its expiry is refused too. A refusal rolls the whole refund back.
     */
    @Transactional
    public long refund(long actor, long paymentId, Instant now, String requestId) {
        passRepository.lockByPayment(paymentId);
        Pass pass = passRepository.findByPurchasePaymentId(paymentId)
                .orElseThrow(() -> new IllegalStateException("Approved payment " + paymentId + " has no pass"));

        if (!findUsedIds(List.of(pass)).isEmpty()) {
            throw ApiException.conflict("PASS_ALREADY_USED", "사용 이력이 있는 이용권은 환불할 수 없습니다.");
        }

        if (isExpired(pass, now)) {
            throw ApiException.conflict("PASS_EXPIRED", "만료된 이용권은 환불할 수 없습니다.");
        }

        String before = pass.getStatus().name();
        pass.refund();
        passRepository.saveAndFlush(pass);
        auditTrail.record(AuditEvent.change(actor, pass.getPurchaseBranchId(), AUDIT_TABLE, pass.getId(), "REFUNDED",
                objectMapper.writeValueAsString(Map.of("status", before)),
                objectMapper.writeValueAsString(Map.of("status", pass.getStatus().name())), requestId));

        return pass.getId();
    }

    /**
     * Check-in: locks the pass for the rest of the transaction. Passes work at every active branch (M5-D01), so the only
     * ownership test is the customer; another customer's pass answers the same 404 as a missing one.
     */
    @Transactional
    public void lockOwned(long id, long customerId) {
        passRepository.findLocked(id)
                .filter(pass -> isOwnedBy(pass, customerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Check-in, on a pass held by {@link #lockOwned}. Refunded > expired > exhausted, the contract's order. Expiry is
     * read from the clock even while the stored status is ACTIVE; a pass without expiry never fails it (FR-PASS-10).
     */
    @Transactional
    public void requireUsable(long id, Instant now) {
        Pass pass = passRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (pass.getStatus() == PassStatus.REFUNDED) {
            throw ApiException.conflict("PASS_REFUNDED", "환불된 이용권입니다.");
        }

        if (isExpired(pass, now)) {
            throw ApiException.conflict("PASS_EXPIRED", "만료된 이용권입니다.");
        }

        if (pass.getStatus() == PassStatus.EXHAUSTED || pass.balanceUsedUp()) {
            throw ApiException.conflict("PASS_EXHAUSTED", "잔량이 소진된 이용권입니다.");
        }

        // NOT_STARTED survives only in the V1 enum (ADR-M4-002); the contract has no code for it because none is issued.
        if (pass.getStatus() != PassStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    /**
     * Check-out, after the session row lock: locks the pass, computes what the stay cost from the pass as it stands now,
     * writes the single USE row when something was deducted and lowers the balance. Should a USE row exist already, the
     * unique key rolls everything back: a session is never charged twice, whatever got past the checks above it.
     */
    @Transactional
    public PassSettlement settle(long actor, long id, long sessionId, Instant in, Instant out, String reason) {
        Pass pass = passRepository.findLocked(id)
                .orElseThrow(() -> new IllegalStateException("Seat session " + sessionId + " has no pass"));
        PassSettlement settled = PassSettlementCalculator.settle(
                pass.getKind(),
                in,
                out,
                pass.getExpiresAt(),
                pass.getRemainingMinutes(),
                pass.getRemainingCreditKrw(),
                pass.getSnapshotHourlyRateKrw()
        );

        if (settled.deductedMinutes() + settled.deductedKrw() > 0) {
            recordUse(actor, id, sessionId, settled, reason, out);
        }

        pass.deduct(settled.deductedMinutes(), settled.deductedKrw());
        passRepository.saveAndFlush(pass);

        // The purchase branch's screens show this balance, wherever the pass was used (seat-usage-api.md).
        return settled;
    }

    /** The pass behind a seat session, for a caller that has already authorized the session (M5-D06). */
    @Transactional(readOnly = true)
    public SessionPass sessionPass(long id) {
        Pass pass = passRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Pass " + id + " is missing"));

        return toSessionPass(pass);
    }

    /** The same for a page of sessions, in one read. */
    @Transactional(readOnly = true)
    public Map<Long, SessionPass> sessionPasses(Collection<Long> ids) {
        Map<Long, SessionPass> result = new HashMap<>();
        passRepository.findAllById(Set.copyOf(ids)).forEach(pass -> result.put(pass.getId(), toSessionPass(pass)));

        return result;
    }

    /**
     * Passes that could check in at {@code now}, by customer, for the exact-match lookup. This reaches beyond the purchase
     * branch scope of {@link #list} on purpose (M5-D01·D05), so it returns only the minimal {@link UsablePass}.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<UsablePass>> usableBy(Collection<Long> customerIds, Instant now) {
        Map<Long, List<UsablePass>> result = new LinkedHashMap<>();

        // An absorbed customer's passes are listed under the target that may use them (#114).
        for (Object[] row : passRepository.findUsableByCustomerFamilies(Set.copyOf(customerIds), PassStatus.ACTIVE)) {
            Pass pass = (Pass) row[0];
            long holder = ((Number) row[1]).longValue();

            if (isExpired(pass, now) || pass.balanceUsedUp()) {
                continue;
            }

            result.computeIfAbsent(holder, customer -> new ArrayList<>()).add(toUsablePass(pass));
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
        return passRepository.findHeldByCustomerFamily(customerId, HELD)
                .stream()
                .map(pass -> new CrossBranchPass(
                        ProductViews.toApiKind(pass.getKind()),
                        pass.getSnapshotProductName(),
                        toStatusLabel(pass, now),
                        pass.getExpiresAt()
                ))
                .toList();
    }

    /** Refund state of the passes bought by these payments, keyed by payment id. */
    @Transactional(readOnly = true)
    public Map<Long, PassUsage> byPayments(Collection<Long> paymentIds, Instant now) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }

        List<Pass> passes = passRepository.findByPurchasePaymentIdIn(Set.copyOf(paymentIds));
        Set<Long> used = findUsedIds(passes);
        Map<Long, PassUsage> result = new HashMap<>();

        for (Pass pass : passes) {
            PassUsage usage = new PassUsage(
                    pass.getId(),
                    pass.getPurchaseBranchId(),
                    used.contains(pass.getId()),
                    isExpired(pass, now),
                    pass.getStatus() == PassStatus.REFUNDED
            );
            result.put(pass.getPurchasePaymentId(), usage);
        }

        return result;
    }

    @Transactional(readOnly = true)
    public PageResponse<IssuedPass> list(
            long actor,
            String scope,
            ListQuery query,
            String customerId,
            String status,
            String kind
    ) {
        Long customer = customerId == null ? null : ProductInputs.parseId(customerId, "customerId");
        String effective = status == null ? null : parseEffectiveStatus(status);

        if (kind != null && !KINDS.contains(kind)) {
            throw ApiException.validation("kind", "올바른 이용권 유형을 선택해 주세요.");
        }

        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        Instant now = clock.instant();
        PassSearchRepository.Sort sort = "expiresAt".equals(query.sort())
                ? PassSearchRepository.Sort.EXPIRES_AT
                : PassSearchRepository.Sort.CREATED_AT;
        PassSearchRepository.Filter filter = new PassSearchRepository.Filter(
                selected,
                customer,
                effective,
                kind == null ? null : ProductViews.toKind(kind),
                now
        );
        boolean descending = query.direction() == ListQuery.Direction.DESC;
        PassSearchRepository.Page page = passSearchRepository.find(
                filter,
                sort,
                descending,
                query.offset(),
                query.pageSize()
        );
        List<IssuedPass> items = toIssuedPasses(page.rows(), state, now);

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    /** 404 hides passes bought outside the reader's scope, so IDs are not an existence oracle. */
    @Transactional(readOnly = true)
    public IssuedPass get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        Pass pass = passRepository.findById(id)
                .filter(found -> selected.contains(found.getPurchaseBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return toIssuedPasses(List.of(pass), state, clock.instant()).getFirst();
    }

    /**
     * The customer's passes bought at these branches with their whole ledger, whatever period a history asks for: the
     * reconciliation issued + ledger = remaining only holds over every row (FR-PASS-09). The rows name who changed the
     * balance, so the caller must withhold them for branches it may not show.
     */
    @Transactional(readOnly = true)
    public List<PassLedger> ledgers(long customerId, Set<Long> purchaseBranches, Instant now) {
        if (purchaseBranches.isEmpty()) {
            return List.of();
        }

        List<Pass> owned = passRepository.findByCustomerFamily(customerId, purchaseBranches);

        if (owned.isEmpty()) {
            return List.of();
        }

        List<Long> passIds = owned.stream()
                .map(Pass::getId)
                .toList();
        Map<Long, List<PassBalanceEvent>> rows = passBalanceEventRepository.findByPassIdInOrderByIdAsc(passIds)
                .stream()
                .collect(Collectors.groupingBy(PassBalanceEvent::getPassId));

        return owned.stream()
                .map(pass -> toLedger(pass, rows.getOrDefault(pass.getId(), List.of()), now))
                .toList();
    }

    /** One pass as the API shows it to {@code state}; for a caller that has authorized the pass already. */
    public IssuedPass view(Pass pass, AuthorizationState state, Instant now) {
        return toIssuedPasses(List.of(pass), state, now).getFirst();
    }

    /** The pass of a customer merged into this one is this customer's to use (2026-11-10 user decision, #114). */
    private boolean isOwnedBy(Pass pass, long customerId) {
        return pass.getCustomerId() == customerId
                || passRepository.findMergedInto(pass.getCustomerId())
                        .filter(target -> target == customerId)
                        .isPresent();
    }

    private void recordUse(long actor, long id, long sessionId, PassSettlement settled, String reason, Instant at) {
        try {
            DuplicateKeys.translate(() -> passBalanceEventRepository.saveAndFlush(PassBalanceEvent.use(
                    id,
                    sessionId,
                    settled.deductedMinutes(),
                    settled.deductedKrw(),
                    actor,
                    reason,
                    at
            )));
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("SESSION_ALREADY_CLOSED", "이미 차감된 이용 기록입니다.");
        }
    }

    private List<IssuedPass> toIssuedPasses(List<Pass> passes, AuthorizationState state, Instant now) {
        Set<Long> used = findUsedIds(passes);
        List<Long> customerIds = passes.stream()
                .map(Pass::getCustomerId)
                .toList();
        Map<Long, CustomerSummary> customers = customerService.summaries(customerIds);

        return passes.stream()
                .map(pass -> toIssuedPass(pass, used, customers, state, now))
                .toList();
    }

    private IssuedPass toIssuedPass(
            Pass pass,
            Set<Long> used,
            Map<Long, CustomerSummary> customers,
            AuthorizationState state,
            Instant now
    ) {
        String status = toStatusLabel(pass, now);
        boolean wasUsed = used.contains(pass.getId());
        boolean canRefund = ACTIVE.equals(status)
                && !wasUsed
                && authorizationService.permits(state, pass.getPurchaseBranchId(), BranchAction.WRITE_PAYMENT_HISTORY);

        return new IssuedPass(
                Long.toString(pass.getId()),
                ApiVersion.fromDatabase(pass.getVersion()),
                customers.get(pass.getCustomerId()),
                ProductViews.toApiKind(pass.getKind()),
                Long.toString(pass.getProductId()),
                pass.getSnapshotProductName(),
                pass.getSnapshotCategoryName(),
                toTerms(pass),
                pass.getRemainingMinutes(),
                pass.getRemainingCreditKrw(),
                status,
                pass.getStartedAt(),
                pass.getExpiresAt(),
                Long.toString(pass.getPurchaseBranchId()),
                Long.toString(pass.getPurchasePaymentId()),
                pass.getCreatedAt(),
                wasUsed,
                canRefund
        );
    }

    private Set<Long> findUsedIds(List<Pass> passes) {
        if (passes.isEmpty()) {
            return Set.of();
        }

        List<Long> passIds = passes.stream()
                .map(Pass::getId)
                .toList();
        Set<Long> result = new HashSet<>();
        passRepository.findUsedIds(passIds).forEach(id -> result.add(id.longValue()));

        return result;
    }

    private static SessionPass toSessionPass(Pass pass) {
        return new SessionPass(
                Long.toString(pass.getId()),
                ProductViews.toApiKind(pass.getKind()),
                pass.getSnapshotProductName(),
                Long.toString(pass.getPurchaseBranchId()),
                pass.getRemainingMinutes(),
                pass.getRemainingCreditKrw()
        );
    }

    private static UsablePass toUsablePass(Pass pass) {
        return new UsablePass(
                Long.toString(pass.getId()),
                ProductViews.toApiKind(pass.getKind()),
                pass.getSnapshotProductName(),
                pass.getRemainingMinutes(),
                pass.getRemainingCreditKrw(),
                pass.getExpiresAt(),
                Long.toString(pass.getPurchaseBranchId())
        );
    }

    private static PassLedger toLedger(Pass pass, List<PassBalanceEvent> rows, Instant now) {
        List<PassLedger.Entry> entries = rows.stream()
                .map(row -> new PassLedger.Entry(
                        row.getKind() == PassBalanceEvent.Kind.ADJUST,
                        row.getSeatSessionId(),
                        row.getDeltaMinutes(),
                        row.getDeltaKrw(),
                        row.getActorEmployeeId(),
                        row.getReason(),
                        row.getCreatedAt()
                ))
                .toList();
        long ledgerMinutes = rows.stream()
                .mapToLong(PassBalanceEvent::getDeltaMinutes)
                .sum();
        long ledgerKrw = rows.stream()
                .mapToLong(PassBalanceEvent::getDeltaKrw)
                .sum();

        return new PassLedger(
                pass.getId(),
                ProductViews.toApiKind(pass.getKind()),
                pass.getSnapshotProductName(),
                toStatusLabel(pass, now),
                pass.getPurchaseBranchId(),
                pass.getPurchasePaymentId(),
                pass.getSnapshotMinutes(),
                pass.getSnapshotCreditKrw(),
                ledgerMinutes,
                ledgerKrw,
                pass.getRemainingMinutes(),
                pass.getRemainingCreditKrw(),
                entries
        );
    }

    private static String parseEffectiveStatus(String label) {
        return switch (label) {
            case ACTIVE -> "ACTIVE";
            case EXHAUSTED -> "EXHAUSTED";
            case EXPIRED -> "EXPIRED";
            case REFUNDED -> "REFUNDED";
            default -> throw ApiException.validation("status", "올바른 이용권 상태를 선택해 주세요.");
        };
    }

    /** Refunded > expired > exhausted > active (FR-PASS-11); the boundary is [startedAt, expiresAt). */
    private static String toStatusLabel(Pass pass, Instant now) {
        if (pass.getStatus() == PassStatus.REFUNDED) {
            return REFUNDED;
        }

        if (isExpired(pass, now)) {
            return EXPIRED;
        }

        if (pass.getStatus() == PassStatus.EXHAUSTED) {
            return EXHAUSTED;
        }

        return ACTIVE;
    }

    private static boolean isExpired(Pass pass, Instant now) {
        boolean isPastExpiry = pass.getExpiresAt() != null && !now.isBefore(pass.getExpiresAt());

        return pass.getStatus() == PassStatus.EXPIRED || isPastExpiry;
    }

    private static ProductTerms toTerms(Pass pass) {
        return switch (pass.getKind()) {
            case TIME -> ProductTerms.Time.of(pass.getSnapshotMinutes(), pass.getSnapshotValidDays());
            case PERIOD -> ProductTerms.Period.of(pass.getSnapshotPeriodDays());
            case STORED_VALUE -> ProductTerms.StoredValue.of(
                    pass.getSnapshotCreditKrw(),
                    pass.getSnapshotHourlyRateKrw(),
                    pass.getSnapshotValidDays()
            );
        };
    }
}
