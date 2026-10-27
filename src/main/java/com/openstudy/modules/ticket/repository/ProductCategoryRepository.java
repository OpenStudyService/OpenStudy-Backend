package com.openstudy.modules.ticket.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;

@Repository
public class ProductCategoryRepository {
    private final JdbcTemplate jdbc;
    private static final String CREATE = "product-categories:create";
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    public ProductCategoryRepository(JdbcTemplate jdbc, AuditTrail audit, WorkspaceOutbox outbox, IdempotencyStore requests,
            Clock clock) {
        this.jdbc = jdbc; this.audit = audit; this.outbox = outbox; this.requests = requests; this.clock = clock;
    }

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
        return requests.lock(actor, CREATE, "all", key).map(r -> new Stored(r.getRequestHash(), r.getResponseJson()))
                .stream().toList();
    }

    public void saveRequest(long actor, String key, byte[] hash, String response) {
        requests.complete(actor, CREATE, "all", key, hash, 201, response, clock.instant().plus(Duration.ofHours(24)));
    }

    public void audit(long actor, long id, String action, String before, String after, String requestId) {
        audit.record(AuditEvent.change(actor, null, "product_categories", id, action, before, after, requestId));
        // Categories are shared by every branch, so every branch stream is told to reload product data.
        outbox.invalidateEveryBranch("product-categories", id);
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
