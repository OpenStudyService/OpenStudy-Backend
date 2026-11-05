package com.openstudy.modules.sales.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;

/**
 * The source rows of every sales metric, read straight from the original transactions (ADR-M9-001: no aggregate table).
 * <ul>
 *   <li>An approval item is a payments row with approved_at in [from, to). approved_at IS NOT NULL is the definition of an
 *       approved payment (APPROVED, or REFUNDED afterwards); a FAILED payment has none and never appears.</li>
 *   <li>A refund item is a refunds row whose basis time is in [from, to): refunds.refunded_at for processedDate, the
 *       original payments.approved_at for paymentDate (M9-D02).</li>
 *   <li>The branch condition is always the original payment's payments.branch_id, never refunds.processing_branch_id
 *       (M9-D05). Pass consumption (pass_balance_events, seat_sessions) is not read at all.</li>
 * </ul>
 * Totals and ledger pages share these WHERE fragments, so a ledger sums to the totals of the same filter. Only fixed
 * fragments are concatenated; every value is bound. Amount sums come back as DECIMAL and are converted exactly.
 */
@Repository
@RequiredArgsConstructor
public class SalesSourceRepository {
    /** Read as text so no driver or JVM time zone can shift a DATETIME(6) that is stored in UTC. */
    private static final String UTC_TEXT = "'%Y-%m-%dT%H:%i:%s.%fZ'";
    private static final String PASS_SNAPSHOT = " LEFT JOIN passes x ON x.purchase_payment_id = p.id";

    private final EntityManager entityManager;

    /** The applied condition of one aggregation. branchIds are already authorized and non-empty. */
    public record SourceFilter(List<Long> branchIds, Instant from, Instant to, RefundBasis refundBasis, SalesPurpose purpose) {
        public SourceFilter { branchIds = List.copyOf(branchIds); }
    }

    /** One (branch, purpose, item type) group: how many source rows and the sum of their amount_krw. */
    public record SourceTotal(long branchId, SalesPurpose purpose, LedgerEntryType type, long count, long amountKrw) {}

    /** One ledger item. amountKrw and passQuantity are the stored, unsigned values; the sign is the caller's. */
    public record LedgerRow(LedgerEntryType type, long paymentId, Long refundId, long branchId, Long processingBranchId,
            SalesPurpose purpose, Long productId, String productName, String categoryCode, String categoryName,
            int passQuantity, long amountKrw, Instant approvedAt, Instant refundedAt, Instant basisAt) {}

    private record Fragment(String from, String where, List<Object> binds) {}

    @SuppressWarnings("unchecked")
    public List<SourceTotal> totals(SourceFilter filter) {
        var result = new ArrayList<SourceTotal>();
        var approvals = approvals(filter);
        Query approved = bind(entityManager.createNativeQuery("SELECT p.branch_id, p.purpose, COUNT(*), "
                + "COALESCE(SUM(p.amount_krw), 0)" + approvals.from() + approvals.where()
                + " GROUP BY p.branch_id, p.purpose"), approvals.binds());
        for (Object[] row : (List<Object[]>) approved.getResultList()) result.add(total(row, LedgerEntryType.APPROVAL));
        var refunds = refunds(filter);
        Query refunded = bind(entityManager.createNativeQuery("SELECT p.branch_id, p.purpose, COUNT(*), "
                + "COALESCE(SUM(r.amount_krw), 0)" + refunds.from() + refunds.where()
                + " GROUP BY p.branch_id, p.purpose"), refunds.binds());
        for (Object[] row : (List<Object[]>) refunded.getResultList()) result.add(total(row, LedgerEntryType.REFUND));
        return result;
    }

    /**
     * Ledger items in a fixed order: basisAt, then approval before refund, then the source ID (payments.id or refunds.id),
     * every key in the requested direction. entryType null means both kinds.
     */
    @SuppressWarnings("unchecked")
    public List<LedgerRow> ledger(SourceFilter filter, LedgerEntryType entryType, boolean descending, long offset, int limit) {
        var parts = new ArrayList<String>();
        var binds = new ArrayList<Object>();
        String columns = " p.id AS payment_id, p.branch_id, p.purpose, p.product_id, p.product_name_snapshot,"
                + " p.issued_pass_quantity, p.approved_at, x.snapshot_category_code, x.snapshot_category_name,";
        if (entryType != LedgerEntryType.REFUND) {
            var approvals = approvals(filter);
            parts.add("SELECT 0 AS kind, p.id AS source_id, NULL AS refund_id, NULL AS processing_branch_id," + columns
                    + " p.amount_krw AS amount_krw, NULL AS refunded_at, p.approved_at AS basis_at"
                    + approvals.from() + PASS_SNAPSHOT + approvals.where());
            binds.addAll(approvals.binds());
        }
        if (entryType != LedgerEntryType.APPROVAL) {
            var refunds = refunds(filter);
            parts.add("SELECT 1 AS kind, r.id AS source_id, r.id AS refund_id, r.processing_branch_id," + columns
                    + " r.amount_krw AS amount_krw, r.refunded_at AS refunded_at, " + refundBasis(filter) + " AS basis_at"
                    + refunds.from() + PASS_SNAPSHOT + refunds.where());
            binds.addAll(refunds.binds());
        }
        String order = descending ? " DESC" : " ASC";
        Query query = entityManager.createNativeQuery("SELECT e.kind, e.payment_id, e.refund_id, e.branch_id,"
                + " e.processing_branch_id, e.purpose, e.product_id, e.product_name_snapshot, e.snapshot_category_code,"
                + " e.snapshot_category_name, e.issued_pass_quantity, e.amount_krw, DATE_FORMAT(e.approved_at, " + UTC_TEXT + "),"
                + " DATE_FORMAT(e.refunded_at, " + UTC_TEXT + "), DATE_FORMAT(e.basis_at, " + UTC_TEXT + ") FROM ("
                + String.join(" UNION ALL ", parts) + ") e ORDER BY e.basis_at" + order + ", e.kind" + order
                + ", e.source_id" + order + " LIMIT ? OFFSET ?");
        binds.add(limit);
        binds.add(offset);
        var rows = new ArrayList<LedgerRow>();
        for (Object[] row : (List<Object[]>) bind(query, binds).getResultList()) {
            var purpose = purpose(row[5]);
            boolean pass = purpose == SalesPurpose.PASS;
            rows.add(new LedgerRow(((Number) row[0]).intValue() == 0 ? LedgerEntryType.APPROVAL : LedgerEntryType.REFUND,
                    number(row[1]), row[2] == null ? null : number(row[2]), number(row[3]),
                    row[4] == null ? null : number(row[4]), purpose, pass && row[6] != null ? number(row[6]) : null,
                    pass ? (String) row[7] : null, pass ? (String) row[8] : null, pass ? (String) row[9] : null,
                    pass ? ((Number) row[10]).intValue() : 0, exact(row[11]), instant(row[12]), instant(row[13]),
                    instant(row[14])));
        }
        return rows;
    }

    private static Fragment approvals(SourceFilter filter) {
        var binds = new ArrayList<Object>(filter.branchIds());
        var where = new StringBuilder(" WHERE p.branch_id IN (").append(placeholders(filter.branchIds().size()))
                .append(") AND p.approved_at IS NOT NULL AND p.approved_at >= ? AND p.approved_at < ?");
        binds.add(filter.from());
        binds.add(filter.to());
        purpose(where, binds, filter);
        return new Fragment(" FROM payments p", where.toString(), binds);
    }

    private static Fragment refunds(SourceFilter filter) {
        var binds = new ArrayList<Object>(filter.branchIds());
        String basis = refundBasis(filter);
        var where = new StringBuilder(" WHERE p.branch_id IN (").append(placeholders(filter.branchIds().size()))
                .append(") AND ").append(basis).append(" >= ? AND ").append(basis).append(" < ?");
        binds.add(filter.from());
        binds.add(filter.to());
        purpose(where, binds, filter);
        return new Fragment(" FROM refunds r JOIN payments p ON p.id = r.payment_id", where.toString(), binds);
    }

    private static String refundBasis(SourceFilter filter) {
        return filter.refundBasis() == RefundBasis.PAYMENT_DATE ? "p.approved_at" : "r.refunded_at";
    }

    private static void purpose(StringBuilder where, List<Object> binds, SourceFilter filter) {
        if (filter.purpose() == null) return;
        where.append(" AND p.purpose = ?");
        binds.add(filter.purpose().name());
    }

    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }

    private static Query bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
        return query;
    }

    private static SourceTotal total(Object[] row, LedgerEntryType type) {
        return new SourceTotal(number(row[0]), purpose(row[1]), type, number(row[2]), exact(row[3]));
    }

    private static SalesPurpose purpose(Object value) { return SalesPurpose.valueOf(value.toString()); }

    private static long number(Object value) { return ((Number) value).longValue(); }

    /** SUM over BIGINT UNSIGNED is DECIMAL; anything that does not fit a long fails instead of wrapping. */
    private static long exact(Object value) { return new BigDecimal(value.toString()).longValueExact(); }

    private static Instant instant(Object value) { return value == null ? null : Instant.parse(value.toString()); }
}
