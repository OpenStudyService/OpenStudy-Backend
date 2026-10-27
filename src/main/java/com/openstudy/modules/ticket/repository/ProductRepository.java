package com.openstudy.modules.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.openstudy.modules.ticket.dto.ProductTerms;

@Repository
public class ProductRepository {
    private static final String SELECT = "SELECT p.*,c.id AS c_id,c.version AS c_version,c.created_at AS c_created_at,"
            + "c.updated_at AS c_updated_at,c.code AS c_code,c.name AS c_name,c.kind AS c_kind,c.status AS c_status "
            + "FROM products p JOIN product_categories c ON c.id=p.category_id";
    private final JdbcTemplate jdbc;
    public ProductRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ProductRecord> all() {
        Map<Long, List<Long>> branches = new HashMap<>();
        jdbc.query("SELECT product_id,branch_id FROM product_branches ORDER BY product_id,branch_id",
                rs -> { branches.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>()).add(rs.getLong(2)); });
        return jdbc.query(SELECT + " ORDER BY p.id", (rs, n) -> map(rs, branches.getOrDefault(rs.getLong("id"), List.of())));
    }

    public Optional<ProductRecord> find(long id) {
        // Branches are read after the product row: a nested query inside the row mapper would hold two connections.
        return jdbc.query(SELECT + " WHERE p.id=?", (rs, n) -> map(rs, List.of()), id).stream().findFirst()
                .map(product -> product.withBranches(branchIds(id)));
    }

    /** Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks and before child rows. */
    public void lock(long id) { jdbc.queryForList("SELECT id FROM products WHERE id=? FOR UPDATE", Long.class, id); }

    /** Shared lock for a purchase transaction: a concurrent stop or branch change waits for the sale to finish. */
    public void lockShared(long id) { jdbc.queryForList("SELECT id FROM products WHERE id=? FOR SHARE", Long.class, id); }

    private List<Long> branchIds(long id) {
        return jdbc.queryForList("SELECT branch_id FROM product_branches WHERE product_id=? ORDER BY branch_id", Long.class, id);
    }

    public long insert(long actor, String name, long price, ProductTerms terms, long categoryId, boolean onSale) {
        String code = "PD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
        var columns = columns(terms);
        jdbc.update("INSERT INTO products(code,name,kind,price_krw,included_minutes,period_days,credit_krw,hourly_rate_krw,"
                + "valid_days,sale_status,category_id,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                code, name, columns.kind, price, columns.minutes, columns.periodDays, columns.credit, columns.hourlyRate,
                columns.validDays, onSale ? "ON_SALE" : "STOPPED", categoryId, actor, actor);
        return jdbc.queryForObject("SELECT id FROM products WHERE code=?", Long.class, code);
    }

    /** The product kind never changes (Q9), so only the kind-specific columns of the same kind are rewritten. */
    public int update(long actor, long id, long expectedDbVersion, String name, long price, ProductTerms terms,
            long categoryId, boolean onSale) {
        var columns = columns(terms);
        return jdbc.update("UPDATE products SET name=?,price_krw=?,included_minutes=?,period_days=?,credit_krw=?,"
                + "hourly_rate_krw=?,valid_days=?,category_id=?,sale_status=?,updated_by=?,version=version+1 "
                + "WHERE id=? AND version=?", name, price, columns.minutes, columns.periodDays, columns.credit,
                columns.hourlyRate, columns.validDays, categoryId, onSale ? "ON_SALE" : "STOPPED", actor, id, expectedDbVersion);
    }

    public void addBranches(long id, Set<Long> branches) {
        for (long branch : branches) jdbc.update("INSERT INTO product_branches(product_id,branch_id) VALUES(?,?)", id, branch);
    }

    public void removeBranches(long id, Set<Long> branches) {
        for (long branch : branches) jdbc.update("DELETE FROM product_branches WHERE product_id=? AND branch_id=?", id, branch);
    }

    public void revision(long id, long dbVersion, String eventType, long actor, String requestId, String snapshot) {
        jdbc.update("INSERT INTO product_revisions(product_id,original_product_id,product_version,event_type,changed_by,"
                + "changed_at,request_id,snapshot_json) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP(6),?,?)",
                id, id, dbVersion, eventType, actor, requestId, snapshot);
    }

    public record Revision(long id, long productId, long dbVersion, String eventType, Long actorId,
            java.time.Instant changedAt, String snapshot) {}

    public List<Revision> revisions(long id) {
        return jdbc.query("SELECT id,original_product_id,product_version,event_type,changed_by,changed_at,snapshot_json "
                + "FROM product_revisions WHERE original_product_id=? ORDER BY product_version,id",
                (rs, n) -> new Revision(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                        nullable(rs, 5), rs.getTimestamp(6).toInstant(), rs.getString(7)), id);
    }

    public record Stored(byte[] hash, String response) {}

    public List<Stored> request(long actor, String key) {
        return jdbc.query("SELECT request_hash,response_json FROM idempotency_records WHERE actor_employee_id=? "
                + "AND operation='products:create' AND scope_key='all' AND idempotency_key=? FOR UPDATE",
                (rs, n) -> new Stored(rs.getBytes(1), rs.getString(2)), actor, key);
    }

    public void saveRequest(long actor, String key, byte[] hash, String response) {
        jdbc.update("INSERT INTO idempotency_records(actor_employee_id,operation,scope_key,idempotency_key,request_hash,state,"
                + "http_status,response_json,expires_at) VALUES(?,'products:create','all',?,?,'COMPLETED',201,?,"
                + "UTC_TIMESTAMP(6)+INTERVAL 24 HOUR)", actor, key, hash, response);
    }

    /** One audit row per command: a shared product has no single owning branch, so branch_id stays NULL. */
    public void audit(long actor, long id, String action, String before, String after, String requestId) {
        jdbc.update("INSERT INTO audit_events(actor_employee_id,entity_type,entity_id,action,before_json,after_json,request_id) "
                + "VALUES(?,'products',?,?,?,?,?)", actor, id, action, before, after, requestId);
    }

    /** Both the previous and the new branches must reload; the outbox rows commit with the product change. */
    public void invalidate(long id, Set<Long> branches) {
        for (long branch : branches) jdbc.update(
                "INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) VALUES(?,'products',?,'INVALIDATE')",
                branch, id);
    }

    private record Columns(String kind, Long minutes, Long periodDays, Long credit, Long hourlyRate, Long validDays) {}

    private static Columns columns(ProductTerms terms) {
        return switch (terms) {
            case ProductTerms.Time time -> new Columns("TIME", time.includedMinutes(), null, null, null, time.validDays());
            case ProductTerms.Period period -> new Columns("PERIOD", null, period.periodDays(), null, null, null);
            case ProductTerms.StoredValue stored -> new Columns("STORED_VALUE", null, null, stored.creditKrw(),
                    stored.hourlyRateKrw(), stored.validDays());
        };
    }

    private static ProductRecord map(ResultSet rs, List<Long> branches) throws SQLException {
        ProductTerms terms = switch (rs.getString("kind")) {
            case "TIME" -> ProductTerms.Time.of(rs.getLong("included_minutes"), nullable(rs, "valid_days"));
            case "PERIOD" -> ProductTerms.Period.of(rs.getLong("period_days"));
            default -> ProductTerms.StoredValue.of(rs.getLong("credit_krw"), rs.getLong("hourly_rate_krw"), nullable(rs, "valid_days"));
        };
        return new ProductRecord(rs.getLong("id"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("code"), rs.getString("name"),
                rs.getLong("price_krw"), terms, "ON_SALE".equals(rs.getString("sale_status")),
                ProductCategoryRepository.map(rs, "c_"), List.copyOf(branches));
    }

    private static Long nullable(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullable(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
