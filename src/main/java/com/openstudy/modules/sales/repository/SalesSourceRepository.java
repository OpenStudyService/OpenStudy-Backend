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

import com.openstudy.modules.sales.dto.request.AdvancedFilter;
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
    private static final String SNAPSHOT_KEY = " x.product_id AS g_product,"
            + " x.snapshot_product_name" + BINARY + " AS g_name,"
            + " x.snapshot_category_code" + BINARY + " AS g_code, x.snapshot_category_name" + BINARY + " AS g_category,"
            + " x.kind AS g_kind";
    private static final String TOTAL_COLUMNS = "SELECT p.branch_id, p.purpose, COUNT(*), ";
    private static final String TOTAL_GROUP = " GROUP BY p.branch_id, p.purpose";
    private static final String DAY_GROUP = ", g_day";
    private static final String LEDGER_COLUMNS = " p.id AS payment_id, p.branch_id, p.purpose, p.product_id,"
            + " p.product_name_snapshot, p.issued_pass_quantity, p.approved_at, x.snapshot_category_code,"
            + " x.snapshot_category_name,";
    private static final int APPROVAL_KIND = 0;

    private final EntityManager entityManager;

    /**
     * The applied condition of one aggregation. branchIds are already authorized and non-empty. productId and categoryCode
     * are only ever set for pass metrics; the revenue reads never see them (the resolver refuses them there).
     */
    public record SourceFilter(
            List<Long> branchIds,
            Instant from,
            Instant to,
            RefundBasis refundBasis,
            SalesPurpose purpose,
            Long productId,
            String categoryCode,
            AdvancedFilter advanced
    ) {

        public SourceFilter {
            branchIds = List.copyOf(branchIds);
            advanced = advanced == null ? AdvancedFilter.NONE : advanced;
        }

        public SourceFilter(
                List<Long> branchIds,
                Instant from,
                Instant to,
                RefundBasis refundBasis,
                SalesPurpose purpose,
                Long productId,
                String categoryCode
        ) {
            this(branchIds, from, to, refundBasis, purpose, productId, categoryCode, AdvancedFilter.NONE);
        }
    }

    /** One (branch, purpose, item type) group: how many source rows and the sum of their amount_krw. */
    public record SourceTotal(long branchId, SalesPurpose purpose, LedgerEntryType type, long count, long amountKrw) {
    }

    /** One ledger item. amountKrw and passQuantity are the stored, unsigned values; the sign is the caller's. */
    public record LedgerRow(
            LedgerEntryType type,
            long paymentId,
            Long refundId,
            long branchId,
            Long processingBranchId,
            SalesPurpose purpose,
            Long productId,
            String productName,
            String categoryCode,
            String categoryName,
            int passQuantity,
            long amountKrw,
            Instant approvedAt,
            Instant refundedAt,
            Instant basisAt
    ) {
    }

    /** A {@link SourceTotal} of one Asia/Seoul day: approvals by approved_at, refunds by the refund basis time. */
    public record DayTotal(LocalDate day, SourceTotal total) {
    }

    /**
     * One (sale-time snapshot, item type[, day]) group of pass items. For approvals count is the approved PASS payments,
     * quantity the sum of issued_pass_quantity and issued the passes rows created for them; for refunds count is the
     * refund rows, quantity the sum of the original payment's issued_pass_quantity and issued 0. day is null unless
     * grouped by day.
     */
    public record PassTotal(
            LocalDate day,
            LedgerEntryType type,
            long productId,
            String productName,
            String categoryCode,
            String categoryName,
            String kind,
            long count,
            long quantity,
            long issued
    ) {
    }

    private record Fragment(String from, String where, List<Object> binds) {
    }

    public List<SourceTotal> totals(SourceFilter filter) {
        return findGroupedTotals(filter, false)
                .stream()
                .map(DayTotal::total)
                .toList();
    }

    /** The same groups as {@link #totals} split by Asia/Seoul day, for a trend. */
    public List<DayTotal> dayTotals(SourceFilter filter) {
        return findGroupedTotals(filter, true);
    }

    /**
     * Pass items of the filter grouped by sale-time snapshot (and day when byDay): only purpose PASS, productId matched on
     * passes.product_id and categoryCode exactly on passes.snapshot_category_code. The groups are few (products times
     * renames), so the caller sums, ranks and pages them; no source row is loaded.
     */
    @SuppressWarnings("unchecked")
    public List<PassTotal> passTotals(SourceFilter filter, boolean byDay) {
        List<PassTotal> result = new ArrayList<>();
        String group = " GROUP BY g_product, g_name, g_code, g_category, g_kind" + (byDay ? DAY_GROUP : "");
        Fragment approvals = toPassFragment(toApprovalFragment(filter), filter);
        String approvedSql = "SELECT" + SNAPSHOT_KEY + ", COUNT(*),"
                + " COALESCE(SUM(p.issued_pass_quantity), 0), COUNT(x.id)" + toDayColumn(byDay, "p.approved_at")
                + approvals.from() + approvals.where() + group;
        Query approved = bindAll(entityManager.createNativeQuery(approvedSql), approvals.binds());

        for (Object[] row : (List<Object[]>) approved.getResultList()) {
            result.add(toPassTotal(row, LedgerEntryType.APPROVAL, byDay));
        }

        Fragment refunds = toPassFragment(toRefundFragment(filter), filter);
        String refundedSql = "SELECT" + SNAPSHOT_KEY + ", COUNT(*),"
                + " COALESCE(SUM(p.issued_pass_quantity), 0), 0" + toDayColumn(byDay, toRefundBasisColumn(filter))
                + refunds.from() + refunds.where() + group;
        Query refunded = bindAll(entityManager.createNativeQuery(refundedSql), refunds.binds());

        for (Object[] row : (List<Object[]>) refunded.getResultList()) {
            result.add(toPassTotal(row, LedgerEntryType.REFUND, byDay));
        }

        return result;
    }

    /**
     * Ledger items in a fixed order: basisAt, then approval before refund, then the source ID (payments.id or refunds.id),
     * every key in the requested direction. entryType null means both kinds.
     */
    @SuppressWarnings("unchecked")
    public List<LedgerRow> ledger(
            SourceFilter filter,
            LedgerEntryType entryType,
            boolean descending,
            long offset,
            int limit
    ) {
        List<String> parts = new ArrayList<>();
        List<Object> binds = new ArrayList<>();

        if (entryType != LedgerEntryType.REFUND) {
            Fragment approvals = toApprovalFragment(filter);
            parts.add("SELECT 0 AS kind, p.id AS source_id, NULL AS refund_id, NULL AS processing_branch_id"
                    + "," + LEDGER_COLUMNS
                    + " p.amount_krw AS amount_krw, NULL AS refunded_at, p.approved_at AS basis_at"
                    + approvals.from() + PASS_SNAPSHOT + approvals.where());
            binds.addAll(approvals.binds());
        }

        if (entryType != LedgerEntryType.APPROVAL) {
            Fragment refunds = toRefundFragment(filter);
            parts.add("SELECT 1 AS kind, r.id AS source_id, r.id AS refund_id, r.processing_branch_id"
                    + "," + LEDGER_COLUMNS
                    + " r.amount_krw AS amount_krw, r.refunded_at AS refunded_at, " + toRefundBasisColumn(filter)
                    + " AS basis_at" + refunds.from() + PASS_SNAPSHOT + refunds.where());
            binds.addAll(refunds.binds());
        }

        String order = descending ? " DESC" : " ASC";
        String sql = "SELECT e.kind, e.payment_id, e.refund_id, e.branch_id,"
                + " e.processing_branch_id, e.purpose, e.product_id, e.product_name_snapshot, e.snapshot_category_code,"
                + " e.snapshot_category_name, e.issued_pass_quantity, e.amount_krw,"
                + " DATE_FORMAT(e.approved_at, " + UTC_TEXT + "),"
                + " DATE_FORMAT(e.refunded_at, " + UTC_TEXT + "), DATE_FORMAT(e.basis_at, " + UTC_TEXT + ") FROM ("
                + String.join(" UNION ALL ", parts) + ") e ORDER BY e.basis_at" + order + ", e.kind" + order
                + ", e.source_id" + order + " LIMIT ? OFFSET ?";
        Query query = entityManager.createNativeQuery(sql);
        binds.add(limit);
        binds.add(offset);
        List<LedgerRow> rows = new ArrayList<>();

        for (Object[] row : (List<Object[]>) bindAll(query, binds).getResultList()) {
            rows.add(toLedgerRow(row));
        }

        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<DayTotal> findGroupedTotals(SourceFilter filter, boolean byDay) {
        List<DayTotal> result = new ArrayList<>();
        Fragment approvals = toApprovalFragment(filter);
        String approvedSql = TOTAL_COLUMNS
                + "COALESCE(SUM(p.amount_krw), 0)" + toDayColumn(byDay, "p.approved_at") + approvals.from()
                + approvals.where() + TOTAL_GROUP + (byDay ? DAY_GROUP : "");
        Query approved = bindAll(entityManager.createNativeQuery(approvedSql), approvals.binds());

        for (Object[] row : (List<Object[]>) approved.getResultList()) {
            LocalDate day = byDay ? LocalDate.parse(row[4].toString()) : null;
            result.add(new DayTotal(day, toSourceTotal(row, LedgerEntryType.APPROVAL)));
        }

        Fragment refunds = toRefundFragment(filter);
        String refundedSql = TOTAL_COLUMNS
                + "COALESCE(SUM(r.amount_krw), 0)" + toDayColumn(byDay, toRefundBasisColumn(filter)) + refunds.from()
                + refunds.where() + TOTAL_GROUP + (byDay ? DAY_GROUP : "");
        Query refunded = bindAll(entityManager.createNativeQuery(refundedSql), refunds.binds());

        for (Object[] row : (List<Object[]>) refunded.getResultList()) {
            LocalDate day = byDay ? LocalDate.parse(row[4].toString()) : null;
            result.add(new DayTotal(day, toSourceTotal(row, LedgerEntryType.REFUND)));
        }

        return result;
    }

    private static String toDayColumn(boolean byDay, String column) {
        return byDay ? ", " + SEOUL_DAY.formatted(column) + " AS g_day" : "";
    }

    /** A pass fragment: the snapshot join, purpose PASS and the product filters, after the shared WHERE. */
    private static Fragment toPassFragment(Fragment base, SourceFilter filter) {
        List<Object> binds = new ArrayList<>(base.binds());
        StringBuilder where = new StringBuilder(base.where()).append(" AND p.purpose = 'PASS'");

        if (filter.productId() != null) {
            where.append(" AND x.product_id = ?");
            binds.add(filter.productId());
        }

        if (filter.categoryCode() != null) {
            where.append(" AND x.snapshot_category_code")
                    .append(BINARY)
                    .append(" = ?");
            binds.add(filter.categoryCode());
        }

        return new Fragment(base.from() + PASS_SNAPSHOT, where.toString(), binds);
    }

    /**
     * Every approved PASS payment issues its pass in the same transaction (PaymentService), so a missing snapshot is a
     * broken invariant: failing beats ranking a row without a product.
     */
    private static PassTotal toPassTotal(Object[] row, LedgerEntryType type, boolean byDay) {
        if (row[0] == null) {
            throw new IllegalStateException("An approved pass payment has no issued pass");
        }

        return new PassTotal(
                byDay ? LocalDate.parse(row[8].toString()) : null,
                type,
                toLong(row[0]),
                (String) row[1],
                (String) row[2],
                (String) row[3],
                row[4].toString(),
                toLong(row[5]),
                toExactLong(row[6]),
                toLong(row[7])
        );
    }

    private static LedgerRow toLedgerRow(Object[] row) {
        SalesPurpose purpose = toPurpose(row[5]);
        boolean pass = purpose == SalesPurpose.PASS;
        LedgerEntryType type = ((Number) row[0]).intValue() == APPROVAL_KIND
                ? LedgerEntryType.APPROVAL
                : LedgerEntryType.REFUND;

        return new LedgerRow(
                type,
                toLong(row[1]),
                row[2] == null ? null : toLong(row[2]),
                toLong(row[3]),
                row[4] == null ? null : toLong(row[4]),
                purpose,
                pass && row[6] != null ? toLong(row[6]) : null,
                pass ? (String) row[7] : null,
                pass ? (String) row[8] : null,
                pass ? (String) row[9] : null,
                pass ? ((Number) row[10]).intValue() : 0,
                toExactLong(row[11]),
                toInstant(row[12]),
                toInstant(row[13]),
                toInstant(row[14])
        );
    }

    private static Fragment toApprovalFragment(SourceFilter filter) {
        List<Object> binds = new ArrayList<>(filter.branchIds());
        StringBuilder where = new StringBuilder(" WHERE p.branch_id IN (")
                .append(placeholders(filter.branchIds().size()))
                .append(") AND p.approved_at IS NOT NULL AND p.approved_at >= ? AND p.approved_at < ?");
        binds.add(filter.from());
        binds.add(filter.to());
        appendPurpose(where, binds, filter);
        appendAdvanced(where, binds, filter, "p.created_by", false);

        return new Fragment(" FROM payments p", where.toString(), binds);
    }

    private static Fragment toRefundFragment(SourceFilter filter) {
        List<Object> binds = new ArrayList<>(filter.branchIds());
        String basis = toRefundBasisColumn(filter);
        StringBuilder where = new StringBuilder(" WHERE p.branch_id IN (")
                .append(placeholders(filter.branchIds().size()))
                .append(") AND ")
                .append(basis)
                .append(" >= ? AND ")
                .append(basis)
                .append(" < ?");
        binds.add(filter.from());
        binds.add(filter.to());
        appendPurpose(where, binds, filter);
        appendAdvanced(where, binds, filter, "r.processed_by", true);

        return new Fragment(" FROM refunds r JOIN payments p ON p.id = r.payment_id", where.toString(), binds);
    }

    private static String toRefundBasisColumn(SourceFilter filter) {
        return filter.refundBasis() == RefundBasis.PAYMENT_DATE ? "p.approved_at" : "r.refunded_at";
    }

    private static void appendPurpose(StringBuilder where, List<Object> binds, SourceFilter filter) {
        if (filter.purpose() == null) {
            return;
        }

        where.append(" AND p.purpose = ?");
        binds.add(filter.purpose().name());
    }

    /**
     * The M13 advanced filters (M13-D10, #116) on an approval (p) or refund (r, p) fragment: the method is the payment's,
     * the operator the payment's creator or the refund's processor, the processing branch only narrows refunds, and a
     * product kind or category matches the sale-time snapshot of the pass the payment issued.
     */
    private static void appendAdvanced(
            StringBuilder where,
            List<Object> binds,
            SourceFilter filter,
            String operator,
            boolean refund
    ) {
        AdvancedFilter advanced = filter.advanced();
        appendIn(where, binds, "p.method", advanced.methods());
        appendIn(where, binds, operator, advanced.operatorIds());

        if (refund) {
            appendIn(where, binds, "r.processing_branch_id", advanced.processingBranchIds());
        }

        if (!advanced.productKinds().isEmpty()) {
            where.append(" AND EXISTS (SELECT 1 FROM passes k WHERE k.purchase_payment_id = p.id AND k.kind IN (")
                    .append(placeholders(advanced.productKinds().size()))
                    .append("))");
            binds.addAll(advanced.productKinds());
        }

        if (filter.categoryCode() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM passes k WHERE k.purchase_payment_id = p.id")
                    .append(" AND k.snapshot_category_code")
                    .append(BINARY)
                    .append(" = ?)");
            binds.add(filter.categoryCode());
        }
    }

    private static void appendIn(StringBuilder where, List<Object> binds, String column, List<?> values) {
        if (values.isEmpty()) {
            return;
        }

        where.append(" AND ")
                .append(column)
                .append(" IN (")
                .append(placeholders(values.size()))
                .append(")");
        binds.addAll(values);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Query bindAll(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) {
            query.setParameter(index + 1, binds.get(index));
        }

        return query;
    }

    private static SourceTotal toSourceTotal(Object[] row, LedgerEntryType type) {
        return new SourceTotal(toLong(row[0]), toPurpose(row[1]), type, toLong(row[2]), toExactLong(row[3]));
    }

    private static SalesPurpose toPurpose(Object value) {
        return SalesPurpose.valueOf(value.toString());
    }

    private static long toLong(Object value) {
        return ((Number) value).longValue();
    }

    /** SUM over BIGINT UNSIGNED is DECIMAL; anything that does not fit a long fails instead of wrapping. */
    private static long toExactLong(Object value) {
        return new BigDecimal(value.toString()).longValueExact();
    }

    private static Instant toInstant(Object value) {
        return value == null ? null : Instant.parse(value.toString());
    }
}
