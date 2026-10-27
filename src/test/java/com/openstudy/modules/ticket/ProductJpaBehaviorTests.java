package com.openstudy.modules.ticket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.ticket.dto.Product;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.service.ProductCategoryService;
import com.openstudy.modules.ticket.service.ProductService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4: database-level effects of the former SQL that the HTTP tests do not pin down — revision rows and their
 * DB timestamps, the sale-branch rows, INT UNSIGNED term limits and the category writer columns.
 */
@MySqlIntegrationTest
@Transactional
class ProductJpaBehaviorTests {
    static final long U32_MAX = 4_294_967_295L;

    @Autowired ProductService products;
    @Autowired ProductCategoryService categories;
    @Autowired JdbcTemplate jdbc;
    long owner;
    long first;
    long second;
    String timeCategory;

    @BeforeEach
    void seed() {
        // The single-owner slot may already be taken by fixtures of other suites; reuse that owner if so.
        var existing = jdbc.queryForList("SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE'", Long.class);
        if (existing.isEmpty()) {
            String email = "ticket-jpa-" + UUID.randomUUID() + "@example.com";
            jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                    + "VALUES (?,'hash','소유자','소유자','OWNER','ACTIVE')", email);
            owner = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        } else {
            owner = existing.getFirst();
        }
        first = branch();
        second = branch();
        String code = "JPA_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        timeCategory = categories.create(owner, key(), Map.of("code", code, "name", "시간 분류", "kind", ProductTerms.TIME), "category").id();
    }

    @Test
    void createAndChangesWriteRevisionsWithDatabaseTimeAndMatchingVersions() {
        var created = create(time(600, null), first);
        long id = Long.parseLong(created.id());
        var stopped = products.changeStatus(owner, id, "all", Map.of("version", created.version(), "status", "판매 중지"), "stop");

        var rows = jdbc.queryForList("SELECT product_version,event_type,changed_by,request_id,JSON_TYPE(snapshot_json) t,"
                + "TIMESTAMPDIFF(SECOND,changed_at,UTC_TIMESTAMP(6)) age FROM product_revisions WHERE original_product_id=? "
                + "ORDER BY product_version", id);
        assertThat(rows).extracting(r -> r.get("event_type")).containsExactly("CREATED", "UPDATED");
        assertThat(rows).extracting(r -> ((Number) r.get("product_version")).longValue()).containsExactly(0L, 1L);
        assertThat(rows).allSatisfy(r -> {
            assertThat(((Number) r.get("changed_by")).longValue()).isEqualTo(owner);
            assertThat(r.get("t")).isEqualTo("OBJECT");
            assertThat(((Number) r.get("age")).longValue()).isBetween(0L, 60L);
        });
        assertThat(stopped.version()).isEqualTo(created.version() + 1);
        assertThat(jdbc.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, id)).isEqualTo("STOPPED");
    }

    @Test
    void branchReplacementRewritesOnlyTheSaleBranchRowsAndAdvancesOnce() {
        var created = create(time(600, null), first);
        long id = Long.parseLong(created.id());
        var changed = products.replaceBranches(owner, id, "all",
                Map.of("version", created.version(), "branchIds", List.of("" + second, "" + first)), "branches");
        assertThat(changed.version()).isEqualTo(created.version() + 1);
        assertThat(changed.branchIds()).containsExactly("" + first, "" + second);
        assertThat(jdbc.queryForList("SELECT branch_id FROM product_branches WHERE product_id=? ORDER BY branch_id", Long.class, id))
                .containsExactly(first, second);

        var narrowed = products.replaceBranches(owner, id, "all",
                Map.of("version", changed.version(), "branchIds", List.of("" + second)), "narrow");
        assertThat(narrowed.version()).isEqualTo(changed.version() + 1);
        assertThat(jdbc.queryForList("SELECT branch_id FROM product_branches WHERE product_id=?", Long.class, id))
                .containsExactly(second);
    }

    @Test
    void unsignedIntTermsRoundTripAtTheirUpperBound() {
        var created = create(time(U32_MAX, U32_MAX), first);
        long id = Long.parseLong(created.id());
        assertThat(created.terms()).isEqualTo(ProductTerms.Time.of(U32_MAX, U32_MAX));
        assertThat(products.get(owner, id, "all").terms()).isEqualTo(ProductTerms.Time.of(U32_MAX, U32_MAX));
        assertThat(jdbc.queryForMap("SELECT included_minutes,valid_days,period_days,credit_krw FROM products WHERE id=?", id))
                .containsEntry("period_days", null).containsEntry("credit_krw", null);
    }

    @Test
    void categoryUpdateRecordsTheWriterAndAdvancesOnce() {
        var category = categories.update(owner, Long.parseLong(timeCategory), Map.of("version", 1L, "name", "바뀐 분류"), "rename");
        assertThat(category.version()).isEqualTo(2);
        assertThat(category.name()).isEqualTo("바뀐 분류");
        assertThat(jdbc.queryForMap("SELECT version,updated_by FROM product_categories WHERE id=?", Long.parseLong(timeCategory)))
                .containsEntry("version", java.math.BigInteger.ONE).containsEntry("updated_by", java.math.BigInteger.valueOf(owner));
    }

    private Product create(Map<String, Object> terms, long... branchIds) {
        var body = new LinkedHashMap<String, Object>();
        body.put("name", "JPA 상품"); body.put("priceKrw", 15000L); body.put("terms", terms); body.put("status", "판매 중");
        body.put("categoryId", timeCategory);
        body.put("branchIds", java.util.Arrays.stream(branchIds).mapToObj(String::valueOf).toList());
        return products.create(owner, "all", key(), body, "create");
    }

    private static Map<String, Object> time(long minutes, Long validDays) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("kind", ProductTerms.TIME); terms.put("includedMinutes", minutes); terms.put("validDays", validDays);
        return terms;
    }

    private long branch() {
        String code = "TK-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'상품 지점','주소')", code);
        return jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
