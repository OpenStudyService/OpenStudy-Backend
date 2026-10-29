package com.openstudy.modules.ticket.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.ticket.dto.IssuedPass;
import com.openstudy.modules.ticket.dto.PassUsage;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.dto.SaleTerms;
import com.openstudy.modules.ticket.entity.Pass;
import com.openstudy.modules.ticket.entity.PassStatus;
import com.openstudy.modules.ticket.entity.ProductKind;
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
    private final PassRepository repository;
    private final PassSearchRepository search;
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
