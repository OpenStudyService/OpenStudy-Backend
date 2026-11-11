package com.openstudy.integration.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import com.openstudy.support.database.MySqlTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Rebuilds the schema with Flyway before every test; run with `gradlew slowTest` or the full `test`.
@Tag("slow")
class FoundationMigrationTests {
    static MySQLContainer mysql;
    static DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;

    @BeforeAll static void startIsolatedDatabase() {
        mysql = new MySQLContainer(DockerImageName.parse(MySqlTestConfiguration.MYSQL_IMAGE))
                .withDatabaseName("openstudy_foundation_test").withUsername("foundation_test")
                .withPassword(UUID.randomUUID().toString())
                .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci", "--default-time-zone=+00:00");
        mysql.start();
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }
    @AfterAll static void stopDatabase() { if (mysql != null) mysql.stop(); }
    Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) config.target(target);
        return config.load();
    }
    @BeforeEach void resetOnlyTheTestcontainersDatabase() {
        assertThat(dataSource.getUrl()).isEqualTo(mysql.getJdbcUrl());
        assertThat(mysql.getDatabaseName()).isEqualTo("openstudy_foundation_test");
        flyway(null).clean();
        jdbc = new JdbcTemplate(dataSource);
    }
    long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    @Test void createsTheConsolidatedBaselineWithIncrementalMigrationsAndPreservesDataOnRestart() {
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(19);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'"))
                .isEqualTo(79); // V7 adds the seven ai_* tables of the AI workspace (DomainLens), V12 branch_closures, V14 customer_merges, V15 daily_closings and daily_closing_adjustments, V16 password_reset_tokens, V18 drops ai_export_approvals, V19 retention_rules and retention_runs
        assertThat(count("SELECT COUNT(*) FROM employees")).isZero();
        assertThat(count("SELECT COUNT(*) FROM administration_guard WHERE id=1")).isEqualTo(1);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES('RESTART','Restart test','Test')");
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        assertThat(count("SELECT COUNT(*) FROM branches WHERE code='RESTART'")).isEqualTo(1);
    }

    @Test void rejectsAnExistingDifferentV1WithoutChangingItsDataOrHistory() {
        flyway(null).migrate();
        jdbc.update("INSERT INTO branches(code,name,address) VALUES('OLD','Old test database','Test')");
        // Simulate an already-applied, different V1 in this disposable database only.
        jdbc.update("UPDATE flyway_schema_history SET checksum=checksum+1 WHERE version='1'");
        var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(flyway(null).validateWithResult().validationSuccessful).isFalse();
        assertThatThrownBy(() -> flyway(null).migrate()).hasMessageContaining("checksum mismatch");
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history);
        assertThat(count("SELECT COUNT(*) FROM branches WHERE code='OLD'")).isEqualTo(1);
    }

    @Test void rejectsANonemptyDatabaseWithoutHistoryInsteadOfBaseliningIt() {
        jdbc.execute("CREATE TABLE existing_data (id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO existing_data VALUES(1)");
        assertThatThrownBy(() -> flyway(null).migrate()).hasMessageContaining("non-empty schema");
        assertThat(count("SELECT COUNT(*) FROM existing_data")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='employees'"))
                .isZero();
    }

    @Test void enforcesProductInvitationOwnerAndIdempotencyConstraintsAndRetainsDeletedHistory() {
        flyway(null).migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'owner@example.com','hash','Owner','Owner','OWNER','ACTIVE')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role) VALUES('other@example.com','hash','Other','Other','OWNER')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO branches(id,code,name,address) VALUES(20,'B','B','Seoul')");
        jdbc.update("INSERT INTO product_categories(id,code,name,kind) VALUES(70,'TIME','Time','TIME'),(71,'PERIOD','Period','PERIOD')");
        for (String invalid : List.of(
            "('ZERO','Zero','TIME',0,60,NULL,NULL,70)",
            "('MISSING','Missing','TIME',1000,NULL,NULL,NULL,70)",
            "('PERIOD','Period','PERIOD',1000,NULL,30,30,71)")) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO products(code,name,kind,price_krw,included_minutes,period_days,valid_days,category_id) VALUES"+invalid))
                .hasStackTraceContaining("Check constraint");
        }
        jdbc.update("INSERT INTO products(id,code,name,kind,price_krw,included_minutes,category_id) VALUES(30,'OK','Time','TIME',1000,60,70)");
        jdbc.update("INSERT INTO product_revisions(product_id,original_product_id,product_version,event_type,changed_at,request_id,snapshot_json) VALUES(30,30,0,'CREATED',UTC_TIMESTAMP(6),'test','{\"schemaVersion\":1}'),(30,30,1,'DELETED',UTC_TIMESTAMP(6),'test','{\"schemaVersion\":1}')");
        jdbc.update("DELETE FROM products WHERE id=30");
        assertThat(count("SELECT COUNT(*) FROM product_revisions WHERE original_product_id=30 AND product_id IS NULL")).isEqualTo(2);
        jdbc.update("INSERT INTO employee_invitations(branch_id,invitee_email,invitee_name,initial_role,created_by,expires_at) VALUES(20,'invite@example.com','Invite','STAFF',10,'2099-01-01')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO employee_invitations(branch_id,invitee_email,invitee_name,initial_role,created_by,expires_at) VALUES(20,'INVITE@example.com','Invite','STAFF',10,'2099-01-01')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE employee_invitations SET status='REJECTED'"))
                .hasStackTraceContaining("Check constraint");
        jdbc.update("INSERT INTO idempotency_records(actor_employee_id,operation,scope_key,idempotency_key,request_hash,state,expires_at) VALUES(10,'create','global','Key',UNHEX(SHA2('a',256)),'PROCESSING','2099-01-01'),(10,'create','global','key',UNHEX(SHA2('a',256)),'PROCESSING','2099-01-01')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO idempotency_records(actor_employee_id,operation,scope_key,idempotency_key,request_hash,state,expires_at) VALUES(10,'create','global','Key',UNHEX(SHA2('a',256)),'PROCESSING','2099-01-01')")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(count("SELECT COUNT(*) FROM permission_definitions")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM permission_preset_grants g JOIN permission_presets p ON p.id=g.preset_id WHERE p.role='STAFF'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM permission_preset_grants g JOIN permission_presets p ON p.id=g.preset_id WHERE p.role='MANAGER'")).isEqualTo(9);
    }

    @Test void enforcesPurchaseQuantityRevisionPairAndPeriodActivation() {
        flyway(null).migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'owner@example.com','hash','Owner','Owner','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address) VALUES(20,'B','B','Test')");
        jdbc.update("INSERT INTO customers(id,member_no,name) VALUES(50,'C','Customer')");
        jdbc.update("INSERT INTO product_categories(id,code,name,kind) VALUES(70,'PERIOD','Period','PERIOD')");
        jdbc.update("INSERT INTO products(id,code,name,kind,price_krw,period_days,category_id) VALUES(30,'P','Period','PERIOD',1000,30,70),(31,'OTHER','Other','PERIOD',1000,30,70)");
        jdbc.update("INSERT INTO product_revisions(id,product_id,original_product_id,product_version,event_type,changed_at,request_id,snapshot_json) VALUES(80,30,30,0,'CREATED',UTC_TIMESTAMP(6),'test','{}'),(81,31,31,0,'CREATED',UTC_TIMESTAMP(6),'test','{}')");
        jdbc.update("INSERT INTO payments(id,customer_id,branch_id,product_id,purpose,method,amount_krw,status,idempotency_key,issued_pass_quantity,created_by) VALUES(60,50,20,30,'PASS','CASH',1000,'PENDING','payment',0,10)");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET status='APPROVED' WHERE id=60"))
                .hasStackTraceContaining("ck_payments_issued_pass_quantity");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET issued_pass_quantity=1 WHERE id=60"))
                .hasStackTraceContaining("ck_payments_issued_pass_quantity");
        jdbc.update("UPDATE payments SET status='APPROVED',issued_pass_quantity=1 WHERE id=60");
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET issued_pass_quantity=2 WHERE id=60"))
                .hasStackTraceContaining("ck_payments_issued_pass_quantity");
        String insert = "INSERT INTO passes(customer_id,product_id,purchase_payment_id,purchase_branch_id,purchase_product_revision_id,kind,snapshot_price_krw,snapshot_product_name,snapshot_category_code,snapshot_category_name,snapshot_period_days,status) VALUES(50,30,60,20,?,'PERIOD',1000,'Purchased period','PERIOD','Purchased category',30,'NOT_STARTED')";
        assertThatThrownBy(() -> jdbc.update(insert,81)).hasStackTraceContaining("fk_passes_purchase_revision_product");
        jdbc.update(insert,80);
        assertThatThrownBy(() -> jdbc.update(insert,80)).hasStackTraceContaining("uk_passes_1");
        assertThatThrownBy(() -> jdbc.update("UPDATE passes SET status='ACTIVE'"))
                .hasStackTraceContaining("ck_passes_period_activation");
        jdbc.update("UPDATE passes SET status='ACTIVE',started_at='2026-10-27 00:00:00',expires_at='2026-11-26 00:00:00'");
        assertThatThrownBy(() -> jdbc.update("UPDATE passes SET expires_at=started_at"))
                .hasStackTraceContaining("ck_passes_period_activation");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM product_revisions WHERE id=80"))
                .hasStackTraceContaining("fk_passes_purchase_revision_product");
        jdbc.update("UPDATE products SET name='Changed product' WHERE id=30");
        assertThat(jdbc.queryForObject("SELECT snapshot_product_name FROM passes",String.class)).isEqualTo("Purchased period");
    }

    @Test void v2AddsCustomerBranchAndMemoToAV1DatabaseAndBackfillsTheFirstPaymentBranch() {
        assertThat(flyway("1").migrate().migrationsExecuted).isEqualTo(1);
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'owner@example.com','hash','Owner','Owner','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address) VALUES(20,'A','A','Test'),(21,'B','B','Test'),(22,'C','C','Test')");
        jdbc.update("INSERT INTO customers(id,member_no,name,updated_at,version) VALUES(50,'PAID','Paid','2026-01-01 00:00:00',3),(51,'NONE','No payment','2026-01-01 00:00:00',0)");
        jdbc.update("INSERT INTO product_categories(id,code,name,kind) VALUES(70,'TIME','Time','TIME')");
        jdbc.update("INSERT INTO products(id,code,name,kind,price_krw,included_minutes,category_id) VALUES(30,'T','Time','TIME',1000,60,70)");
        // The later payment has the smaller id, so ordering must follow created_at first.
        jdbc.update("INSERT INTO payments(id,customer_id,branch_id,product_id,purpose,method,amount_krw,status,idempotency_key,created_by,created_at) VALUES"
                + "(60,50,20,30,'PASS','CASH',1000,'FAILED','late',10,'2026-03-01 00:00:00'),"
                + "(61,50,21,30,'PASS','CASH',1000,'FAILED','early',10,'2026-02-01 00:00:00')");

        assertThat(flyway("2").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("2").validateWithResult().validationSuccessful).isTrue();
        var paid = jdbc.queryForMap("SELECT registered_branch_id,memo,updated_at,version FROM customers WHERE id=50");
        assertThat(((Number) paid.get("registered_branch_id")).longValue()).isEqualTo(21);
        assertThat(paid.get("memo")).isNull();
        assertThat(paid.get("updated_at").toString()).startsWith("2026-01-01");
        assertThat(((Number) paid.get("version")).longValue()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT registered_branch_id FROM customers WHERE id=51", Long.class)).isNull();

        var columns = jdbc.queryForList("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='customers' AND COLUMN_NAME IN ('registered_branch_id','memo') ORDER BY COLUMN_NAME");
        assertThat(columns).containsExactly(
                Map.of("COLUMN_NAME", "memo", "COLUMN_TYPE", "varchar(1000)", "IS_NULLABLE", "YES"),
                Map.of("COLUMN_NAME", "registered_branch_id", "COLUMN_TYPE", "bigint unsigned", "IS_NULLABLE", "YES"));
        jdbc.update("INSERT INTO customers(member_no,name,phone,registered_branch_id,memo) VALUES('NEW','New','01012345678',22,?)", "가".repeat(1000));
        assertThatThrownBy(() -> jdbc.update("UPDATE customers SET memo=? WHERE member_no='NEW'", "가".repeat(1001)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO customers(member_no,name,registered_branch_id) VALUES('BAD','Bad',999)"))
                .hasStackTraceContaining("fk_customers_registered_branch");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM branches WHERE id=22"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void v3AddsChannelDescriptionAndVersionToExistingConversations() {
        assertThat(flyway("2").migrate().migrationsExecuted).isEqualTo(2);
        jdbc.update("INSERT INTO branches(id,code,name,address) VALUES(20,'A','A','Test')");
        jdbc.update("INSERT INTO conversations(id,kind,branch_id,name) VALUES(40,'GLOBAL_CHANNEL',NULL,'All'),(41,'BRANCH_CHANNEL',20,'Branch')");

        assertThat(flyway("3").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("3").validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForList("SELECT description FROM conversations WHERE id IN (40,41)", String.class)).containsOnlyNulls();
        assertThat(jdbc.queryForList("SELECT version FROM conversations WHERE id IN (40,41)", Long.class)).containsExactly(0L, 0L);
        var columns = jdbc.queryForList("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='conversations' AND COLUMN_NAME IN ('description','version') ORDER BY COLUMN_NAME");
        assertThat(columns.get(0)).containsEntry("COLUMN_TYPE", "varchar(500)").containsEntry("IS_NULLABLE", "YES");
        assertThat(columns.get(1)).containsEntry("COLUMN_TYPE", "bigint unsigned").containsEntry("IS_NULLABLE", "NO").containsEntry("COLUMN_DEFAULT", "0");
        jdbc.update("UPDATE conversations SET description=? WHERE id=40", "가".repeat(500));
        assertThatThrownBy(() -> jdbc.update("UPDATE conversations SET description=? WHERE id=40", "가".repeat(501)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private void seedRoomPrerequisites() {
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'owner@example.com','hash','Owner','Owner','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address) VALUES(20,'A','A','Test')");
        jdbc.update("INSERT INTO customers(id,member_no,name) VALUES(50,'C','Customer')");
    }
    private void v3Reservation(long id, String start, String end, int rate, int slots, long amount, String key) {
        jdbc.update("INSERT INTO room_reservations(id,room_id,branch_id,customer_id,start_at,end_at,rate_per_30min_snapshot_krw,slot_count,amount_krw,status,request_key,created_by,updated_at) "
                + "VALUES(?,30,20,50,?,?,?,?,?,'PAYMENT_PENDING',?,10,'2026-01-01 00:00:00')", id, start, end, rate, slots, amount, key);
    }
    private void reservation(String start, String end, long rate, long hours, long amount, String key) {
        jdbc.update("INSERT INTO room_reservations(room_id,branch_id,customer_id,start_at,end_at,rate_per_hour_snapshot_krw,hours,amount_krw,status,request_key,created_by) "
                + "VALUES(30,20,50,?,?,?,?,?,'PAYMENT_PENDING',?,10)", start, end, rate, hours, amount, key);
    }
    private void migratedRoomFixture() {
        flyway(null).migrate();
        seedRoomPrerequisites();
        jdbc.update("INSERT INTO rooms(id,branch_id,room_no,name,capacity,rate_per_hour_krw) VALUES(30,20,'R1','Room',4,8000)");
    }
    private long columnsNamed(String names) {
        return count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN (" + names + ")");
    }

    @Test void v4ConvertsRoomRatesReservationsAndSlotsOfAHourAlignedV3Database() {
        flyway("3").migrate();
        seedRoomPrerequisites();
        jdbc.update("INSERT INTO rooms(id,branch_id,room_no,name,capacity,rate_per_30min_krw,updated_at,version) VALUES(30,20,'R1','Room',4,4000,'2026-01-01 00:00:00',5)");
        // 14:00-16:00 is four 30-minute slots in V3 (rate 4,000 per slot, amount 16,000).
        v3Reservation(90, "2026-12-01 14:00:00", "2026-12-01 16:00:00", 4000, 4, 16000, "OLD");
        for (String slot : List.of("14:00", "14:30", "15:00", "15:30"))
            jdbc.update("INSERT INTO room_reservation_slots(reservation_id,room_id,slot_start_at) VALUES(90,30,?)", "2026-12-01 " + slot + ":00");

        assertThat(flyway("4").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("4").validateWithResult().validationSuccessful).isTrue();

        var room = jdbc.queryForMap("SELECT rate_per_hour_krw,updated_at,version FROM rooms WHERE id=30");
        assertThat(((Number) room.get("rate_per_hour_krw")).longValue()).isEqualTo(8000);
        assertThat(room.get("updated_at").toString()).startsWith("2026-01-01");
        assertThat(((Number) room.get("version")).longValue()).isEqualTo(5);
        var old = jdbc.queryForMap("SELECT rate_per_hour_snapshot_krw,hours,amount_krw,updated_at FROM room_reservations WHERE id=90");
        assertThat(((Number) old.get("rate_per_hour_snapshot_krw")).longValue()).isEqualTo(8000);
        assertThat(((Number) old.get("hours")).longValue()).isEqualTo(2);
        assertThat(((Number) old.get("amount_krw")).longValue()).isEqualTo(16000);
        assertThat(old.get("updated_at").toString()).startsWith("2026-01-01");
        assertThat(jdbc.queryForList("SELECT DATE_FORMAT(slot_start_at,'%H:%i') FROM room_reservation_slots WHERE reservation_id=90 ORDER BY slot_start_at", String.class))
                .containsExactly("14:00", "15:00");
        assertThat(columnsNamed("'rate_per_30min_krw','rate_per_30min_snapshot_krw','slot_count'")).isZero();
    }

    @Test void v5MarksEveryExistingInteractionAsAnExactLookupAndAcceptsOnlyTheTwoSources() {
        flyway("4").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'staff@example.com','hash','Staff','Staff','EMPLOYEE','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address) VALUES(20,'B','B','Seoul')");
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(10,20,'STAFF','ACTIVE')");
        jdbc.update("INSERT INTO customers(id,member_no,name,phone) VALUES(40,'M-1','Customer','01012345678')");
        jdbc.update("INSERT INTO customer_service_interactions(id,customer_id,branch_id,employee_id,reason) VALUES(50,40,20,10,'lookup')");

        assertThat(flyway("5").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("5").validateWithResult().validationSuccessful).isTrue();

        assertThat(jdbc.queryForObject("SELECT source FROM customer_service_interactions WHERE id=50", String.class)).isEqualTo("CHECK_IN_LOOKUP");
        jdbc.update("INSERT INTO customer_service_interactions(customer_id,branch_id,employee_id,reason,source) VALUES(40,20,10,'summary','CROSS_BRANCH_SUMMARY')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO customer_service_interactions(customer_id,branch_id,employee_id,reason,source) VALUES(40,20,10,'x','OTHER')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void v6MakesEachReadPositionTheBaselineOfTheThreadsAndAddsTheirTable() {
        flyway("5").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE'),(11,'b@example.com','hash','B','B','EMPLOYEE','ACTIVE'),(12,'c@example.com','hash','C','C','EMPLOYEE','ACTIVE')");
        jdbc.update("INSERT INTO conversations(id,kind,name,created_by) VALUES(30,'GLOBAL_CHANNEL','Channel',10),(31,'GLOBAL_CHANNEL','Other',10)");
        jdbc.update("INSERT INTO conversation_members(conversation_id,employee_id,last_read_message_id,last_read_at) VALUES(30,10,NULL,NULL),(30,11,41,'2026-11-01 00:00:00'),(30,12,42,'2026-11-01 00:00:00'),(31,10,NULL,NULL)");
        jdbc.update("INSERT INTO messages(id,conversation_id,sender_employee_id,parent_message_id,body,client_message_key) VALUES(40,30,10,NULL,'original','k1'),(41,30,10,40,'reply','k2'),(42,30,10,NULL,'later','k3'),(43,31,10,NULL,'other','k4'),(44,30,10,40,'reply 2','k5')");

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(14); // V6 through V19
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();

        // A position on a reply (41) carries over as it is, and no position stays none.
        assertThat(jdbc.queryForMap("SELECT last_read_message_id AS position, thread_read_baseline_message_id AS baseline FROM conversation_members WHERE conversation_id=30 AND employee_id=11"))
                .containsEntry("baseline", java.math.BigInteger.valueOf(41)).containsEntry("position", java.math.BigInteger.valueOf(41));
        assertThat(count("SELECT thread_read_baseline_message_id FROM conversation_members WHERE conversation_id=30 AND employee_id=12")).isEqualTo(42);
        assertThat(count("SELECT COUNT(*) FROM conversation_members WHERE employee_id=10 AND thread_read_baseline_message_id IS NULL")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM conversation_thread_reads")).isZero();

        // What a reader of the migrated rows is left with, by the rule of the unread queries.
        String unreadReplies = "SELECT COUNT(*) FROM messages m JOIN conversation_members me ON me.conversation_id=m.conversation_id AND me.employee_id=%d "
                + "WHERE m.conversation_id=30 AND m.parent_message_id IS NOT NULL AND (me.thread_read_baseline_message_id IS NULL OR m.id>me.thread_read_baseline_message_id)";
        assertThat(count(unreadReplies.formatted(11))).isEqualTo(1);
        assertThat(count(unreadReplies.formatted(10))).isEqualTo(2);

        jdbc.update("INSERT INTO conversation_thread_reads(conversation_id,employee_id,parent_message_id,last_read_message_id,last_read_at) VALUES(30,11,40,44,'2026-11-03 00:00:00')");
        String insert = "INSERT INTO conversation_thread_reads(conversation_id,employee_id,parent_message_id,last_read_message_id,last_read_at) VALUES(%s,'2026-11-03 00:00:00')";
        assertThatThrownBy(() -> jdbc.update(insert.formatted("30,11,40,41"))).hasStackTraceContaining("Duplicate entry");
        // A thread of another channel, a position in another channel, someone who never took part.
        assertThatThrownBy(() -> jdbc.update(insert.formatted("31,10,40,43"))).hasStackTraceContaining("fk_conversation_thread_reads_2");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("30,12,40,43"))).hasStackTraceContaining("fk_conversation_thread_reads_3");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("31,11,43,43"))).hasStackTraceContaining("fk_conversation_thread_reads_1");
    }

    /** #108 (M12-D02): every leave request names its deciding branch, which must exist. */
    @Test void v11AddsTheRequiredLeaveRequestBranch() {
        flyway("10").migrate();
        assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='leave_requests' "
                + "AND column_name='branch_id' AND is_nullable='NO'")).isEqualTo(1);
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','EMPLOYEE','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address,status) VALUES(20,'B1','B','서울','ACTIVE')");
        jdbc.update("INSERT INTO leave_requests(employee_id,branch_id,request_year,starts_on,ends_on,requested_days,reason) "
                + "VALUES(10,20,2026,'2026-11-10','2026-11-10',1,'사유')");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO leave_requests(employee_id,branch_id,request_year,starts_on,ends_on,requested_days,reason) "
                + "VALUES(10,99,2026,'2026-11-11','2026-11-11',1,'사유')")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    /** #112 (M13-D01·D05): one closure per branch and day, on an existing branch, by an existing employee. */
    @Test void v12AddsOneClosurePerBranchAndDay() {
        flyway("11").migrate();
        assertThat(flyway("12").migrate().migrationsExecuted).isEqualTo(1);
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address,status) VALUES(20,'B1','B','서울','ACTIVE')");
        String insert = "INSERT INTO branch_closures(branch_id,closed_on,note,created_by) VALUES(%s)";
        jdbc.update(insert.formatted("20,'2026-12-25','성탄절',10"));
        assertThat(count("SELECT version FROM branch_closures WHERE branch_id=20")).isZero();
        assertThatThrownBy(() -> jdbc.update(insert.formatted("20,'2026-12-25',NULL,10"))).hasStackTraceContaining("uk_branch_closures_1");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("99,'2026-12-26',NULL,10"))).hasStackTraceContaining("fk_branch_closures_1");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("20,'2026-12-26',NULL,99"))).hasStackTraceContaining("fk_branch_closures_2");
    }

    /** #113 (M13-D02·D06): the sale period and limit are optional, and the database refuses an inverted period or a bad limit. */
    @Test void v13AddsTheOptionalSalePeriodAndLimit() {
        flyway("12").migrate();
        jdbc.update("INSERT INTO product_categories(id,code,name,kind,status) VALUES(30,'C1','분류','TIME','ACTIVE')");
        jdbc.update("INSERT INTO products(id,code,name,kind,price_krw,included_minutes,category_id) VALUES(40,'P1','상품','TIME',1000,60,30)");
        assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(1);
        // An existing product keeps no restriction.
        assertThat(jdbc.queryForMap("SELECT sales_starts_at, sales_ends_at, sales_limit FROM products WHERE id=40").values()).containsOnlyNulls();
        jdbc.update("UPDATE products SET sales_starts_at='2026-12-01 00:00:00', sales_ends_at='2026-12-31 00:00:00', sales_limit=10 WHERE id=40");
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET sales_ends_at='2026-11-01 00:00:00' WHERE id=40"))
                .hasStackTraceContaining("ck_products_sales_period");
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET sales_limit=0 WHERE id=40")).hasStackTraceContaining("ck_products_sales_limit");
        assertThatThrownBy(() -> jdbc.update("UPDATE products SET sales_limit=1000001 WHERE id=40")).hasStackTraceContaining("ck_products_sales_limit");
    }

    /** #114 (M13-D03): a merged customer points at its target and only a merged one does; merges are kept as rows. */
    @Test void v14AddsTheMergedStatusAndTheMergeHistory() {
        flyway("13").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO customers(id,member_no,name,phone) VALUES(50,'M-1','고객','01012345678'),(51,'M-2','고객','01012345678')");
        assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM customers WHERE id=50", String.class)).isEqualTo("NORMAL");
        jdbc.update("UPDATE customers SET status='MERGED', merged_into_customer_id=50 WHERE id=51");
        jdbc.update("INSERT INTO customer_merges(target_customer_id,absorbed_customer_id,merged_by,merged_at,reason) VALUES(50,51,10,NOW(6),'중복')");
        // MERGED and the target go together.
        assertThatThrownBy(() -> jdbc.update("UPDATE customers SET status='MERGED' WHERE id=50")).hasStackTraceContaining("ck_customers_merged");
        assertThatThrownBy(() -> jdbc.update("UPDATE customers SET merged_into_customer_id=51 WHERE id=50")).hasStackTraceContaining("ck_customers_merged");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO customer_merges(target_customer_id,absorbed_customer_id,merged_by,merged_at,reason) "
                + "VALUES(50,50,10,NOW(6),'자기')")).hasStackTraceContaining("ck_customer_merges_1");
    }

    /** #115 (M13-D08): one closing per branch and day; one adjustment per refund, a positive amount on an existing closing. */
    @Test void v15AddsOneClosingPerBranchAndDayAndOneAdjustmentPerRefund() {
        flyway("14").migrate();
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO branches(id,code,name,address,status) VALUES(20,'B1','B','서울','ACTIVE')");
        String closing = "INSERT INTO daily_closings(branch_id,business_date,snapshot_json,closed_by,closed_at) VALUES(20,'2026-11-01','{}',10,NOW(6))";
        jdbc.update(closing);
        assertThatThrownBy(() -> jdbc.update(closing)).hasStackTraceContaining("uk_daily_closings_1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO daily_closing_adjustments(closing_id,refund_id,payment_id,amount_krw,processed_by,processed_at) "
                + "VALUES(999,1,1,1000,10,NOW(6))")).hasStackTraceContaining("fk_daily_closing_adjustments");
    }

    /**
     * #119 (M14-D05): a reset link has a token hash and an expiry exactly while it can be used (SENDING, SENT), a hash names
     * one link, and the links go with their employee.
     */
    @Test void v16KeepsAResetTokenHashOnlyWhileTheLinkCanBeUsed() {
        flyway("15").migrate();
        assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(1);
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE'),(11,'b@example.com','hash','B','B','EMPLOYEE','ACTIVE')");
        String pending = "INSERT INTO password_reset_tokens(employee_id,requested_by,status,next_attempt_at) VALUES(11,10,'PENDING',NOW(6))";
        jdbc.update(pending);
        String sent = "INSERT INTO password_reset_tokens(employee_id,requested_by,status,token_hash,expires_at,next_attempt_at) "
                + "VALUES(11,10,'SENT',UNHEX(SHA2('t',256)),NOW(6),NOW(6))";
        jdbc.update(sent);
        assertThatThrownBy(() -> jdbc.update(sent)).hasStackTraceContaining("uk_password_reset_tokens_1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_tokens(employee_id,status,expires_at,next_attempt_at) "
                + "VALUES(11,'SENDING',NOW(6),NOW(6))")).hasStackTraceContaining("ck_password_reset_tokens_1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_tokens(employee_id,status,token_hash,next_attempt_at) "
                + "VALUES(11,'SENT',UNHEX(SHA2('u',256)),NOW(6))")).hasStackTraceContaining("ck_password_reset_tokens_2");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO password_reset_tokens(employee_id,status,token_hash,next_attempt_at) "
                + "VALUES(11,'PENDING',UNHEX(SHA2('v',256)),NOW(6))")).hasStackTraceContaining("ck_password_reset_tokens_1");
        jdbc.update("DELETE FROM employees WHERE id=11");
        assertThat(count("SELECT COUNT(*) FROM password_reset_tokens")).isZero();
    }

    /**
     * #125: an execution saved before V17 is a question with the analysis default period and every product; a refinement
     * keeps its own period, products and source.
     */
    @Test void v17KeepsSavedExecutionsAsQuestionsAndAddsRefinementConditions() {
        flyway("16").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO ai_executions(id,request_ref,employee_id,scope_key,branch_ids_csv,question,status,as_of_utc,snapshot_version,policy_version) "
                + "VALUES('exec-1','req-1',10,'all','1','q','READY',NOW(6),'s','p')");
        assertThat(flyway("17").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT origin, source_execution_id AS source, period_from AS start, product_ids_csv AS products FROM ai_executions WHERE id='exec-1'"))
                .containsEntry("origin", "QUESTION").containsEntry("source", null).containsEntry("start", null).containsEntry("products", null);
        jdbc.update("INSERT INTO ai_executions(id,request_ref,employee_id,scope_key,branch_ids_csv,question,status,as_of_utc,snapshot_version,policy_version,"
                + "period_from,period_to,product_ids_csv,origin,source_execution_id) "
                + "VALUES('exec-2','req-2',10,'all','1','q','READY',NOW(6),'s','p','2026-10-01','2026-10-31','3,7','REFINEMENT','exec-1')");
        assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE origin='REFINEMENT' AND period_to='2026-10-31'")).isEqualTo(1);
    }

    /** #214: the ported export approvals go with their table; the executions they pointed at stay. */
    @Test void v18DropsTheExportApprovalsAndKeepsTheExecutions() {
        flyway("17").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','OWNER','ACTIVE')");
        jdbc.update("INSERT INTO ai_executions(id,request_ref,employee_id,scope_key,branch_ids_csv,question,status,as_of_utc,snapshot_version,policy_version) "
                + "VALUES('exec-1','req-1',10,'all','1','q','READY',NOW(6),'s','p')");
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_export_approvals'")).isEqualTo(1);
        assertThat(flyway("18").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='ai_export_approvals'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE id='exec-1'")).isEqualTo(1);
    }

    /**
     * #100: every retention rule starts disabled, a rule cannot be enabled without a period or set below its legal minimum,
     * a run record carries counts only, and existing customers start not anonymized.
     */
    @Test void v19StartsEveryRetentionRuleDisabledAndGuardsItsPeriod() {
        flyway("18").migrate();
        jdbc.update("INSERT INTO customers(id,member_no,name) VALUES(50,'C','Customer')");
        assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM retention_rules")).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM retention_rules WHERE enabled = 1")).isZero();
        assertThat(jdbc.queryForObject("SELECT anonymized_at FROM customers WHERE id=50", Object.class)).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE retention_rules SET enabled = 1 WHERE code = 'AUDIT_EVENT'"))
                .hasStackTraceContaining("ck_retention_rules_1");
        assertThatThrownBy(() -> jdbc.update("UPDATE retention_rules SET period_days = 364 WHERE code = 'AUDIT_EVENT'"))
                .hasStackTraceContaining("ck_retention_rules_2");
        jdbc.update("UPDATE retention_rules SET enabled = 1 WHERE code = 'CUSTOMER_IDENTITY'");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO retention_runs(rule_code,period_days,cutoff_at,started_at,finished_at,result) "
                + "VALUES('CUSTOMER_IDENTITY',365,NOW(6),NOW(6),NOW(6),'FAILED')")).hasStackTraceContaining("ck_retention_runs_1");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO retention_runs(rule_code,period_days,cutoff_at,started_at,finished_at,result) "
                + "VALUES('NO_SUCH_RULE',1,NOW(6),NOW(6),NOW(6),'SUCCESS')")).hasStackTraceContaining("fk_retention_runs_1");
    }

    /** No branch is guessed for a request that predates V11: the migration fails and leaves the table as it was. */
    @Test void v11RefusesExistingLeaveRequestsWithoutChangingThem() {
        flyway("10").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status) VALUES(10,'a@example.com','hash','A','A','EMPLOYEE','ACTIVE')");
        jdbc.update("INSERT INTO leave_requests(employee_id,request_year,starts_on,ends_on,requested_days,reason) "
                + "VALUES(10,2026,'2026-11-10','2026-11-10',1,'사유')");
        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining("fk_leave_requests_branch");
        assertThat(count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='leave_requests' "
                + "AND column_name='branch_id'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM leave_requests")).isEqualTo(1);
    }

    @Test void v4RefusesAV3DatabaseWithNonHourReservationsWithoutChangingAnything() {
        flyway("3").migrate();
        seedRoomPrerequisites();
        jdbc.update("INSERT INTO rooms(id,branch_id,room_no,name,capacity,rate_per_30min_krw) VALUES(30,20,'R1','Room',4,4000)");
        v3Reservation(91, "2026-12-01 14:30:00", "2026-12-01 15:30:00", 4000, 2, 8000, "HALF");

        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining("ck_room_reservations_13");
        assertThat(columnsNamed("'rate_per_30min_krw','rate_per_30min_snapshot_krw','slot_count'")).isEqualTo(3);
        assertThat(count("SELECT rate_per_30min_krw FROM rooms WHERE id=30")).isEqualTo(4000);
        assertThat(count("SELECT slot_count FROM room_reservations WHERE id=91")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM permission_definitions WHERE code='customers:cross-branch-read'")).isZero();
    }

    @Test void v4RefusesA90MinuteV3ReservationThatStartsOnTheHour() {
        flyway("3").migrate();
        seedRoomPrerequisites();
        jdbc.update("INSERT INTO rooms(id,branch_id,room_no,name,capacity,rate_per_30min_krw) VALUES(30,20,'R1','Room',4,4000)");
        v3Reservation(92, "2026-12-01 14:00:00", "2026-12-01 15:30:00", 4000, 3, 12000, "NINETY");

        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining("ck_room_reservations_14");
        assertThat(columnsNamed("'rate_per_30min_krw','rate_per_30min_snapshot_krw','slot_count'")).isEqualTo(3);
    }

    @Test void v4RejectsReservationsThatAreNotWholeHoursOnTheHourOrPricedByTheHour() {
        migratedRoomFixture();
        reservation("2026-12-01 14:00:00", "2026-12-01 15:00:00", 8000, 1, 8000, "OK1");
        reservation("2026-12-01 16:00:00", "2026-12-01 19:00:00", 8000, 3, 24000, "OK3");
        assertThat(count("SELECT COUNT(*) FROM room_reservations")).isEqualTo(2);
        assertThatThrownBy(() -> reservation("2026-12-02 14:30:00", "2026-12-02 15:30:00", 8000, 1, 8000, "HALF-START"))
                .hasStackTraceContaining("ck_room_reservations_13");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 14:30:00", 8000, 1, 8000, "HALF-LONG"))
                .hasStackTraceContaining("ck_room_reservations_14");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 15:30:00", 8000, 1, 8000, "NINETY"))
                .hasStackTraceContaining("ck_room_reservations_14");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 16:00:00", 8000, 1, 8000, "HOURS-MISMATCH"))
                .hasStackTraceContaining("ck_room_reservations_7");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 14:00:00", 8000, 0, 0, "EMPTY"))
                .hasStackTraceContaining("Check constraint");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 16:00:00", 8000, 2, 8000, "AMOUNT-LOW"))
                .hasStackTraceContaining("ck_room_reservations_10");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 16:00:00", 8000, 2, 32000, "AMOUNT-HIGH"))
                .hasStackTraceContaining("ck_room_reservations_10");
        assertThatThrownBy(() -> reservation("2026-12-02 14:00:00", "2026-12-02 15:00:00", 0, 1, 0, "FREE"))
                .hasStackTraceContaining("ck_room_reservations_9");
        assertThatThrownBy(() -> jdbc.update("UPDATE room_reservations SET amount_krw=1 WHERE request_key='OK1'"))
                .hasStackTraceContaining("ck_room_reservations_10");
        assertThatThrownBy(() -> jdbc.update("UPDATE rooms SET rate_per_hour_krw=0 WHERE id=30"))
                .hasStackTraceContaining("ck_rooms_2");
        assertThat(count("SELECT COUNT(*) FROM room_reservations")).isEqualTo(2);
    }

    @Test void v4AllowsOnlyOnTheHourSlotsAndKeepsTheRoomSlotUniqueKey() {
        migratedRoomFixture();
        reservation("2026-12-01 14:00:00", "2026-12-01 16:00:00", 8000, 2, 16000, "SLOTS");
        reservation("2026-12-01 18:00:00", "2026-12-01 19:00:00", 8000, 1, 8000, "OTHER");
        long reservation = jdbc.queryForObject("SELECT id FROM room_reservations WHERE request_key='SLOTS'", Long.class);
        long other = jdbc.queryForObject("SELECT id FROM room_reservations WHERE request_key='OTHER'", Long.class);
        String insert = "INSERT INTO room_reservation_slots(reservation_id,room_id,slot_start_at) VALUES(?,30,?)";
        jdbc.update(insert, reservation, "2026-12-01 14:00:00");
        jdbc.update(insert, reservation, "2026-12-01 15:00:00");
        assertThatThrownBy(() -> jdbc.update(insert, reservation, "2026-12-01 15:30:00"))
                .hasStackTraceContaining("ck_room_reservation_slots_2");
        assertThatThrownBy(() -> jdbc.update(insert, reservation, "2026-12-01 16:00:30"))
                .hasStackTraceContaining("ck_room_reservation_slots_1");
        assertThatThrownBy(() -> jdbc.update(insert, other, "2026-12-01 15:00:00"))
                .hasStackTraceContaining("uk_room_reservation_slots_1");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='room_reservation_slots' AND INDEX_NAME='uk_room_reservation_slots_1' AND NON_UNIQUE=0 ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("room_id", "slot_start_at");
    }

    @Test void v4AddsTheGrantableCrossBranchPermissionWithoutPuttingItInAnyPreset() {
        flyway(null).migrate();
        var permission = jdbc.queryForMap("SELECT code,is_admin_only FROM permission_definitions WHERE code='customers:cross-branch-read'");
        assertThat(permission.get("is_admin_only")).isIn(false, 0);
        assertThat(count("SELECT COUNT(*) FROM permission_definitions")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM permission_preset_grants g JOIN permission_definitions d ON d.id=g.permission_id WHERE d.code='customers:cross-branch-read'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM permission_preset_grants")).isEqualTo(9);
    }

    @Test void exportsPristineSchemaAndRestoresItWithMatchingFlywayHistoryAndMetadata() throws Exception {
        flyway(null).migrate();
        var columns = jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY,COLUMN_DEFAULT,EXTRA,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION");
        var keys = jdbc.queryForList("SELECT k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,k.CONSTRAINT_NAME,r.DELETE_RULE FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA=DATABASE() ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION");
        var tableNames = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name",String.class);
        var ordered = new ArrayList<String>();
        while(ordered.size()<tableNames.size()) {
            int before=ordered.size();
            for(String t:tableNames) if(!ordered.contains(t) && keys.stream().filter(k->t.equals(k.get("TABLE_NAME")))
                    .allMatch(k->t.equals(k.get("REFERENCED_TABLE_NAME")) || ordered.contains(k.get("REFERENCED_TABLE_NAME")))) ordered.add(t);
            if(before==ordered.size()) throw new IllegalStateException("Unresolved FK cycle: "+tableNames);
        }
        var sql = new StringBuilder("-- OpenStudy consolidated V1 / MySQL 8.4 / Flyway V1\n-- EMPTY database only. Includes real Flyway history, reference seeds, no business credentials.\nSET NAMES utf8mb4;\nSET time_zone = '+00:00';\n");
        for(String t:ordered) sql.append(jdbc.queryForMap("SHOW CREATE TABLE `"+t+"`").get("Create Table")).append(";\n\n");
        for(String t:List.of("permission_definitions","permission_presets","permission_preset_grants","administration_guard","flyway_schema_history")) {
            for(var row:jdbc.queryForList("SELECT * FROM `"+t+"`")) {
                sql.append("INSERT INTO `").append(t).append("` (`").append(String.join("`,`",row.keySet())).append("`) VALUES (");
                sql.append(String.join(",",row.values().stream().map(FoundationMigrationTests::sqlLiteral).toList())).append(");\n");
            }
        }
        Path temp=Files.createTempFile("foundation-schema-", ".sql");
        Files.writeString(temp,sql);
        flyway(null).clean();
        try(var connection=dataSource.getConnection()) { ScriptUtils.executeSqlScript(connection,new FileSystemResource(temp)); }
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY,COLUMN_DEFAULT,EXTRA,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION")).isEqualTo(columns);
        assertThat(jdbc.queryForList("SELECT k.TABLE_NAME,k.COLUMN_NAME,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME,k.CONSTRAINT_NAME,r.DELETE_RULE FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA=DATABASE() ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION")).isEqualTo(keys);
        assertThat(count("SELECT COUNT(*) FROM employees")).isZero();
        assertThat(count("SELECT COUNT(*) FROM permission_definitions")).isEqualTo(10);
        if(System.getProperty("foundation.exportDir")!=null) {
            Path out=Path.of(System.getProperty("foundation.exportDir")); Files.createDirectories(out);
            Files.copy(temp,out.resolve("schema-full.sql"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("contractVersion","consolidated-v1"); metadata.put("flywayVersion",1);
            metadata.put("mysqlVersion",jdbc.queryForObject("SELECT VERSION()",String.class));
            metadata.put("tables",tableNames); metadata.put("columns",columns); metadata.put("foreignKeys",keys);
            metadata.put("uniqueIndexes",jdbc.queryForList("SELECT TABLE_NAME,INDEX_NAME,COLUMN_NAME FROM information_schema.statistics WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0 ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"));
            Files.writeString(out.resolve("schema-metadata.json"),JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        }
        Files.delete(temp);
    }
    static String sqlLiteral(Object value) {
        if(value==null) return "NULL";
        if(value instanceof Number) return value.toString();
        if(value instanceof Boolean b) return b?"1":"0";
        return "'"+value.toString().replace("\\","\\\\").replace("'","''")+"'";
    }
}
