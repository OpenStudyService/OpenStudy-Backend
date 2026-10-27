package com.openstudy.modules.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;

@Repository
public class ProductCategoryRepository {
    private final JdbcTemplate jdbc;
    public ProductCategoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ProductCategory> all() {
        return jdbc.query("SELECT * FROM product_categories ORDER BY id", (rs, n) -> map(rs, ""));
    }

    public Optional<ProductCategory> find(long id) {
        return jdbc.query("SELECT * FROM product_categories WHERE id=?", (rs, n) -> map(rs, ""), id).stream().findFirst();
    }

    /** Serialize category edits so an update never races a product assignment to the same category. */
    public Optional<ProductCategory> findForUpdate(long id) {
        return jdbc.query("SELECT * FROM product_categories WHERE id=? FOR UPDATE", (rs, n) -> map(rs, ""), id).stream().findFirst();
    }

    public long create(long actor, String code, String name, String kind) {
        jdbc.update("INSERT INTO product_categories(code,name,kind,status,created_by,updated_by) VALUES(?,?,?,'ACTIVE',?,?)",
                code, name, databaseKind(kind), actor, actor);
        return jdbc.queryForObject("SELECT id FROM product_categories WHERE code=?", Long.class, code);
    }

    public void update(long actor, long id, String name, String status) {
        jdbc.update("UPDATE product_categories SET name=?,status=?,updated_by=?,version=version+1 WHERE id=?",
                name, "활성".equals(status) ? "ACTIVE" : "INACTIVE", actor, id);
    }

    public record Stored(byte[] hash, String response) {}

    public List<Stored> request(long actor, String key) {
        return jdbc.query("SELECT request_hash,response_json FROM idempotency_records WHERE actor_employee_id=? "
                + "AND operation='product-categories:create' AND scope_key='all' AND idempotency_key=? FOR UPDATE",
                (rs, n) -> new Stored(rs.getBytes(1), rs.getString(2)), actor, key);
    }

    public void saveRequest(long actor, String key, byte[] hash, String response) {
        jdbc.update("INSERT INTO idempotency_records(actor_employee_id,operation,scope_key,idempotency_key,request_hash,state,"
                + "http_status,response_json,expires_at) VALUES(?,'product-categories:create','all',?,?,'COMPLETED',201,?,"
                + "UTC_TIMESTAMP(6)+INTERVAL 24 HOUR)", actor, key, hash, response);
    }

    public void audit(long actor, long id, String action, String before, String after, String requestId) {
        jdbc.update("INSERT INTO audit_events(actor_employee_id,entity_type,entity_id,action,before_json,after_json,request_id) "
                + "VALUES(?,'product_categories',?,?,?,?,?)", actor, id, action, before, after, requestId);
        // Categories are shared by every branch, so every branch stream is told to reload product data.
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) "
                + "SELECT id,'product-categories',?,'INVALIDATE' FROM branches", id);
    }

    /** Maps columns with an optional alias prefix so product queries can join the category in one statement. */
    static ProductCategory map(ResultSet rs, String prefix) throws SQLException {
        return new ProductCategory(Long.toString(rs.getLong(prefix + "id")),
                ApiVersion.fromDatabase(rs.getLong(prefix + "version")),
                rs.getTimestamp(prefix + "created_at").toInstant().toString(),
                rs.getTimestamp(prefix + "updated_at").toInstant().toString(),
                rs.getString(prefix + "code"), rs.getString(prefix + "name"), apiKind(rs.getString(prefix + "kind")),
                "ACTIVE".equals(rs.getString(prefix + "status")) ? "활성" : "비활성");
    }

    static String apiKind(String kind) {
        return switch (kind) {
            case "TIME" -> ProductTerms.TIME;
            case "PERIOD" -> ProductTerms.PERIOD;
            default -> ProductTerms.STORED_VALUE;
        };
    }

    static String databaseKind(String kind) {
        return switch (kind) {
            case ProductTerms.TIME -> "TIME";
            case ProductTerms.PERIOD -> "PERIOD";
            default -> "STORED_VALUE";
        };
    }
}
