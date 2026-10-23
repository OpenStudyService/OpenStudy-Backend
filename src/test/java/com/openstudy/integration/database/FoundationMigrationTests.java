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
    void v1Fixture() {
        flyway("1").migrate();
        jdbc.update("INSERT INTO employees(id,email,password_hash,name,nickname,global_role,status,version) VALUES(10,' Owner@Example.com ','unchanged-hash','Owner','Owner','OWNER','ACTIVE',7)");
        jdbc.update("INSERT INTO branches(id,code,name,address,version) VALUES(20,'LEGACY','Legacy branch','Seoul',4)");
        jdbc.update("INSERT INTO products(id,code,name,kind,price_krw,included_minutes,version) VALUES(30,'LEGACY-TIME','Legacy time','TIME',10000,600,8)");
        jdbc.update("INSERT INTO employee_invitations(id,branch_id,invitee_email,initial_role,created_by,expires_at,version) VALUES(40,20,' New@Example.com ','STAFF',10,'2099-01-01',3)");
        jdbc.update("INSERT INTO customers(id,member_no,name) VALUES(50,'LEGACY-CUSTOMER','Customer')");
        jdbc.update("INSERT INTO payments(id,customer_id,branch_id,product_id,product_name_snapshot,purpose,method,amount_krw,status,idempotency_key,created_by) VALUES(60,50,20,30,'Original purchased name','PASS','CASH',9000,'REFUNDED','legacy-payment',10)");
    }
    void reviewedMapping() {
        jdbc.update("INSERT INTO product_categories(id,code,name,kind) VALUES(70,'REVIEWED-TIME','Reviewed category','TIME')");
        jdbc.update("UPDATE products SET category_id=70,updated_at=updated_at WHERE id=30");
        jdbc.update("INSERT INTO product_branches VALUES(30,20)");
        jdbc.update("UPDATE employee_invitations SET invitee_name='Verified invitee',updated_at=updated_at WHERE id=40");
        // Reviewed STAFF invitation intentionally has no grants, as required by Q5.
        jdbc.update("UPDATE foundation_legacy_review SET confirmed=TRUE");
    }

    @Test void upgradesReviewedV1DataWithoutChangingIdentityVersionsCredentialsOrPurchases() throws Exception {
        v1Fixture();
        var times = jdbc.queryForMap("SELECT created_at,updated_at FROM products WHERE id=30");
        flyway("2").migrate(); reviewedMapping(); flyway(null).migrate();
        assertThat(jdbc.queryForMap("SELECT created_at,updated_at FROM products WHERE id=30")).isEqualTo(times);
        assertThat(jdbc.queryForMap("SELECT email,password_hash,version FROM employees WHERE id=10"))
                .containsEntry("email","owner@example.com").containsEntry("password_hash","unchanged-hash");
        assertThat(count("SELECT version FROM employees WHERE id=10")).isEqualTo(7);
        assertThat(count("SELECT version FROM products WHERE id=30")).isEqualTo(8);
        assertThat(count("SELECT version FROM employee_invitations WHERE id=40")).isEqualTo(3);
        assertThat(count("SELECT amount_krw FROM payments WHERE id=60")).isEqualTo(9000);
        assertThat(jdbc.queryForObject("SELECT product_name_snapshot FROM payments WHERE id=60",String.class)).isEqualTo("Original purchased name");
        assertThat(jdbc.queryForObject("SELECT event_type FROM product_revisions WHERE original_product_id=30",String.class)).isEqualTo("BASELINE");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.branchIds[0]')) FROM product_revisions",String.class)).isEqualTo("20");
        var snapshot=JsonMapper.builder().build().readTree(jdbc.queryForObject("SELECT snapshot_json FROM product_revisions",String.class));
        assertThat(snapshot.get("terms").isObject()).isTrue();
        assertThat(snapshot.get("terms").get("kind").asString()).isEqualTo("시간권");
        assertThat(snapshot.get("categoryCode").asString()).isEqualTo("REVIEWED-TIME");
        assertThat(count("SELECT COUNT(*) FROM invitation_permission_grants")).isZero();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM products WHERE id=30")).hasMessageContaining("foreign key constraint");
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway(null).migrate().migrationsExecuted).isZero();
    }

    @Test void blocksUnreviewedLegacyScopeBeforeFinalizingAndCanResumeAfterExplicitReview() {
        v1Fixture(); flyway("2").migrate();
        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining("FOUNDATION_MAPPING_REQUIRED");
        assertThat(count("SELECT version FROM products WHERE id=30")).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM product_branches")).isZero();
        assertThat(count("SELECT COUNT(*) FROM product_revisions")).isZero();
        reviewedMapping();
        // Only this isolated test DB is repaired. V3 failed in its preflight, before data/DDL changes.
        flyway(null).repair(); flyway(null).migrate();
        assertThat(flyway(null).validateWithResult().validationSuccessful).isTrue();
    }

    @Test void rejectsNormalizedEmailCollisionAndConflictingSystemPreset() {
        flyway("1").migrate();
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname) VALUES(' x@example.com','hash','A','A'),('x@example.com','hash','B','B')");
        flyway("2").migrate();
        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining("FOUNDATION_EMAIL_COLLISION");
        jdbc.update("DELETE FROM employees WHERE email=' x@example.com'");
        jdbc.update("INSERT INTO permission_presets(name,role,is_system) VALUES('일반 직원 기본','STAFF',FALSE)");
        flyway(null).repair();
        assertThatThrownBy(() -> flyway(null).migrate()).hasStackTraceContaining("FOUNDATION_PRESET_SEED_CONFLICT");
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
        var sql = new StringBuilder("-- OpenStudy foundation 0.1.2 / MySQL 8.4 / Flyway V1-V3\n-- EMPTY database only. Includes real Flyway history, reference seeds, no business credentials.\nSET NAMES utf8mb4;\nSET time_zone = '+00:00';\n");
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
        assertThat(count("SELECT COUNT(*) FROM employees")).isZero();
        assertThat(count("SELECT COUNT(*) FROM permission_definitions")).isEqualTo(9);
        if(System.getProperty("foundation.exportDir")!=null) {
            Path out=Path.of(System.getProperty("foundation.exportDir")); Files.createDirectories(out);
            Files.copy(temp,out.resolve("schema-full.sql"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("contractVersion","0.1.2-design"); metadata.put("flywayVersion",3);
            metadata.put("mysqlVersion",jdbc.queryForObject("SELECT VERSION()",String.class));
            metadata.put("tables",tableNames); metadata.put("columns",columns); metadata.put("foreignKeys",keys);
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
