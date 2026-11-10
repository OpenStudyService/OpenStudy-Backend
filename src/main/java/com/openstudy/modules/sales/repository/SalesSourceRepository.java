package com.openstudy.modules.sales.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.AdvancedFilter;
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
 * Totals, day totals, pass totals and ledger pages share these WHERE fragments, so a ledger sums to the totals of the
 * same filter and a trend's buckets sum to its totals. Only fixed fragments are concatenated; every value is bound.
 * Amount and quantity sums come back as DECIMAL and are converted exactly.
 * <p>Pass metrics (#88) read the sale-time snapshot of passes, never product_revisions again. Text keys are grouped and
 * matched under utf8mb4_0900_bin: the tables' utf8mb4_0900_ai_ci would fold a name changed only in case or accent into
 * the old row and let categoryCode match more than the exact code.</p>
 */
@Repository
@RequiredArgsConstructor
public class SalesSourceRepository {
    /** Read as text so no driver or JVM time zone can shift a DATETIME(6) that is stored in UTC. */
    private static final String UTC_TEXT = "'%Y-%m-%dT%H:%i:%s.%fZ'";
    private static final String PASS_SNAPSHOT = " LEFT JOIN passes x ON x.purchase_payment_id = p.id";
    private static final String BINARY = " COLLATE utf8mb4_0900_bin";
    /**
     * The Asia/Seoul calendar day of a UTC DATETIME(6). Asia/Seoul has been a fixed +09:00 without daylight saving time
     * since 1988 (AggregationConditions relies on the same), so a fixed offset needs no server time zone tables.
     */
    private static final String SEOUL_DAY = "DATE_FORMAT(DATE_ADD(%s, INTERVAL 9 HOUR), '%%Y-%%m-%%d')";
    /** The snapshot key of a pass row, aliased so GROUP BY uses the binary expressions and not the ci columns. */
    private static final String SNAPSHOT_KEY = " x.product_id AS g_product, x.snapshot_product_name" + BINARY + " AS g_name,"
            + " x.snapshot_category_code" + BINARY + " AS g_code, x.snapshot_category_name" + BINARY + " AS g_category,"
            + " x.kind AS g_kind";

    private final EntityManager entityManager;

    /**
     * The applied condition of one aggregation. branchIds are already authorized and non-empty. productId and categoryCode
     * are only ever set for pass metrics; the revenue reads never see them (the resolver refuses them there).
     */
    public record SourceFilter(List<Long> branchIds, Instant from, Instant to, RefundBasis refundBasis, SalesPurpose purpose,
            Long productId, String categoryCode, AdvancedFilter advanced) {
        public SourceFilter {
            branchIds = List.copyOf(branchIds);
            advanced = advanced == null ? AdvancedFilter.NONE : advanced;
        }

        public SourceFilter(List<Long> branchIds, Instant from, Instant to, RefundBasis refundBasis, SalesPurpose purpose,
                Long productId, String categoryCode) {
            this(branchIds, from, to, refundBasis, purpose, productId, categoryCode, AdvancedFilter.NONE);
        }
    }

    /** One (branch, purpose, item type) group: how many source rows and the sum of their amount_krw. */
    public record SourceTotal(long branchId, SalesPurpose purpose, LedgerEntryType type, long count, long amountKrw) {}

    /** One ledger item. amountKrw and passQuantity are the stored, unsigned values; the sign is the caller's. */
    public record LedgerRow(LedgerEntryType type, long paymentId, Long refundId, long branchId, Long processingBranchId,
            SalesPurpose purpose, Long productId, String productName, String categoryCode, String categoryName,
            int passQuantity, long amountKrw, Instant approvedAt, Instant refundedAt, Instant basisAt) {}

    /** A {@link SourceTotal} of one Asia/Seoul day: approvals by approved_at, refunds by the refund basis time. */
    public record DayTotal(LocalDate day, SourceTotal total) {}

    /**
     * One (sale-time snapshot, item type[, day]) group of pass items. For approvals count is the approved PASS payments,
     * quantity the sum of issued_pass_quantity and issued the passes rows created for them; for refunds count is the
     * refund rows, quantity the sum of the original payment's issued_pass_quantity and issued 0. day is null unless
     * grouped by day.
     */
    public record PassTotal(LocalDate day, LedgerEntryType type, long productId, String productName, String categoryCode,
            String categoryName, String kind, long count, long quantity, long issued) {}

    private record Fragment(String from, String where, List<Object> binds) {}

    public List<SourceTotal> totals(SourceFilter filter) {
        return grouped(filter, false).stream().map(DayTotal::total).toList();
    }

    /** The same groups as {@link #totals} split by Asia/Seoul day, for a trend. */
    public List<DayTotal> dayTotals(SourceFilter filter) { return grouped(filter, true); }

    @SuppressWarnings("unchecked")
    private List<DayTotal> grouped(SourceFilter filter, boolean byDay) {
        var result = new ArrayList<DayTotal>();
        var approvals = approvals(filter);
        Query approved = bind(entityManager.createNativeQuery("SELECT p.branch_id, p.purpose, COUNT(*), "
                + "COALESCE(SUM(p.amount_krw), 0)" + day(byDay, "p.approved_at") + approvals.from() + approvals.where()
                + " GROUP BY p.branch_id, p.purpose" + (byDay ? ", g_day" : "")), approvals.binds());
        for (Object[] row : (List<Object[]>) approved.getResultList())
            result.add(new DayTotal(byDay ? LocalDate.parse(row[4].toString()) : null, total(row, LedgerEntryType.APPROVAL)));
        var refunds = refunds(filter);
        Query refunded = bind(entityManager.createNativeQuery("SELECT p.branch_id, p.purpose, COUNT(*), "
                + "COALESCE(SUM(r.amount_krw), 0)" + day(byDay, refundBasis(filter)) + refunds.from() + refunds.where()
                + " GROUP BY p.branch_id, p.purpose" + (byDay ? ", g_day" : "")), refunds.binds());
        for (Object[] row : (List<Object[]>) refunded.getResultList())
            result.add(new DayTotal(byDay ? LocalDate.parse(row[4].toString()) : null, total(row, LedgerEntryType.REFUND)));
        return result;
    }

    /**
     * Pass items of the filter grouped by sale-time snapshot (and day when byDay): only purpose PASS, productId matched on
     * passes.product_id and categoryCode exactly on passes.snapshot_category_code. The groups are few (products times
     * renames), so the caller sums, ranks and pages them; no source row is loaded.
     */
    @SuppressWarnings("unchecked")
    public List<PassTotal> passTotals(SourceFilter filter, boolean byDay) {
        var result = new ArrayList<PassTotal>();
        String group = " GROUP BY g_product, g_name, g_code, g_category, g_kind" + (byDay ? ", g_day" : "");
        var approvals = passes(approvals(filter), filter);
        Query approved = bind(entityManager.createNativeQuery("SELECT" + SNAPSHOT_KEY + ", COUNT(*),"
                + " COALESCE(SUM(p.issued_pass_quantity), 0), COUNT(x.id)" + day(byDay, "p.approved_at")
                + approvals.from() + approvals.where() + group), approvals.binds());
        for (Object[] row : (List<Object[]>) approved.getResultList()) result.add(passTotal(row, LedgerEntryType.APPROVAL, byDay));
        var refunds = passes(refunds(filter), filter);
        Query refunded = bind(entityManager.createNativeQuery("SELECT" + SNAPSHOT_KEY + ", COUNT(*),"
                + " COALESCE(SUM(p.issued_pass_quantity), 0), 0" + day(byDay, refundBasis(filter))
                + refunds.from() + refunds.where() + group), refunds.binds());
        for (Object[] row : (List<Object[]>) refunded.getResultList()) result.add(passTotal(row, LedgerEntryType.REFUND, byDay));
        return result;
    }

    private static String day(boolean byDay, String column) {
        return byDay ? ", " + SEOUL_DAY.formatted(column) + " AS g_day" : "";
    }

    /** A pass fragment: the snapshot join, purpose PASS and the product filters, after the shared WHERE. */
    private static Fragment passes(Fragment base, SourceFilter filter) {
        var binds = new ArrayList<Object>(base.binds());
        var where = new StringBuilder(base.where()).append(" AND p.purpose = 'PASS'");
        if (filter.productId() != null) {
            where.append(" AND x.product_id = ?");
            binds.add(filter.productId());
        }
        if (filter.categoryCode() != null) {
            where.append(" AND x.snapshot_category_code").append(BINARY).append(" = ?");
            binds.add(filter.categoryCode());
        }
        return new Fragment(base.from() + PASS_SNAPSHOT, where.toString(), binds);
    }

    /**
     * Every approved PASS payment issues its pass in the same transaction (PaymentService), so a missing snapshot is a
     * broken invariant: failing beats ranking a row without a product.
     */
    private static PassTotal passTotal(Object[] row, LedgerEntryType type, boolean byDay) {
        if (row[0] == null) throw new IllegalStateException("An approved pass payment has no issued pass");
        return new PassTotal(byDay ? LocalDate.parse(row[8].toString()) : null, type, number(row[0]), (String) row[1],
                (String) row[2], (String) row[3], row[4].toString(), number(row[5]), exact(row[6]), number(row[7]));
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
        advanced(where, binds, filter, "p.created_by", false);
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
        advanced(where, binds, filter, "r.processed_by", true);
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

    /**
     * The M13 advanced filters (M13-D10, #116) on an approval (p) or refund (r, p) fragment: the method is the payment's,
     * the operator the payment's creator or the refund's processor, the processing branch only narrows refunds, and a
     * product kind or category matches the sale-time snapshot of the pass the payment issued.
     */
    private static void advanced(StringBuilder where, List<Object> binds, SourceFilter filter, String operator, boolean refund) {
        var advanced = filter.advanced();
        in(where, binds, "p.method", advanced.methods());
        in(where, binds, operator, advanced.operatorIds());
        if (refund) in(where, binds, "r.processing_branch_id", advanced.processingBranchIds());
        if (!advanced.productKinds().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM passes k WHERE k.purchase_payment_id = p.id AND k.kind IN (")
                    .append(placeholders(advanced.productKinds().size())).append("))");
            binds.addAll(advanced.productKinds());
        }
        if (filter.categoryCode() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM passes k WHERE k.purchase_payment_id = p.id AND k.snapshot_category_code")
                    .append(BINARY).append(" = ?)");
            binds.add(filter.categoryCode());
        }
    }

    private static void in(StringBuilder where, List<Object> binds, String column, List<?> values) {
        if (values.isEmpty()) return;
        where.append(" AND ").append(column).append(" IN (").append(placeholders(values.size())).append(")");
        binds.addAll(values);
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
