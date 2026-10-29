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
        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank", String.class))
                .containsExactly("1", "2");
        assertThat(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'"))
                .isEqualTo(65);
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
        assertThat(count("SELECT COUNT(*) FROM permission_definitions")).isEqualTo(9);
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

        assertThat(flyway(null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
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
        assertThat(count("SELECT COUNT(*) FROM permission_definitions")).isEqualTo(9);
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
