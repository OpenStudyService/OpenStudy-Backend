package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #118: the published M14 contract defines password change, administrator reset, reset-link use, login unlock,
 * profile photos and audit search with their errors and the ADR-M14-001 permissions, and no response carries a
 * password, token or storage key.
 */
class AccountContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    /** operationId → "method path", in the order of the contract document. */
    static final Map<String, String> OPERATIONS = new LinkedHashMap<>();
    static {
        OPERATIONS.put("changeMyPassword", "post /me/password");
        OPERATIONS.put("requestPasswordReset", "post /employees/{id}/password-reset");
        OPERATIONS.put("previewPasswordReset", "post /password-resets/preview");
        OPERATIONS.put("completePasswordReset", "post /password-resets/complete");
        OPERATIONS.put("unlockEmployeeLogin", "post /employees/{id}/login-unlock");
        OPERATIONS.put("uploadMyAvatar", "put /me/avatar");
        OPERATIONS.put("deleteMyAvatar", "delete /me/avatar");
        OPERATIONS.put("getEmployeeAvatar", "get /employees/{id}/avatar");
        OPERATIONS.put("searchAuditEvents", "get /audit-events");
        OPERATIONS.put("getAuditEvent", "get /audit-events/{id}");
    }
    static final Map<String, String> ISSUES = Map.ofEntries(
            Map.entry("changeMyPassword", "#119"), Map.entry("requestPasswordReset", "#119"),
            Map.entry("previewPasswordReset", "#119"), Map.entry("completePasswordReset", "#119"),
            Map.entry("unlockEmployeeLogin", "#119"), Map.entry("uploadMyAvatar", "#120"),
            Map.entry("deleteMyAvatar", "#120"), Map.entry("getEmployeeAvatar", "#120"),
            Map.entry("searchAuditEvents", "#122"), Map.entry("getAuditEvent", "#122"));
    /** Field names that would leak a secret if any response schema had them. */
    static final Set<String> SECRET_FIELDS = Set.of("password", "currentPassword", "newPassword", "passwordHash", "token",
            "tokenHash", "resetUrl", "sessionId", "csrfToken", "storageKey", "avatarKey", "avatarUrl");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws java.io.IOException {
        return Files.readString(DESIGN.resolve("account-collab-api.md"));
    }

    JsonNode operation(String id) {
        String[] methodAndPath = OPERATIONS.get(id).split(" ");
        return spec.get("paths").get(methodAndPath[1]).get(methodAndPath[0]);
    }

    JsonNode schema(String name) {
        return spec.at("/components/schemas/" + name);
    }

    static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    /** Acceptance 1: every operation and its error responses are defined and documented. */
    @Test void everyOperationIsDefinedWithItsErrorsAndDocumented() throws Exception {
        String contract = contract();
        String common = Files.readString(DESIGN.resolve("api.md"));
        assertThat(OPERATIONS).hasSize(10);
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation).as(route).isNotNull();
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            assertThat(operation.get("x-error-codes").size()).as(id).isPositive();
            assertThat(operation.get("x-adr-decisions").toString()).as(id).contains("M14-D");
            // #119 and #122 implemented their operations; #120 is still to come.
            String status = ISSUES.get(id).equals("#120") ? "design-target" : "implemented";
            assertThat(operation.get("x-implementation-status").asString()).as(id).isEqualTo(status);
            assertThat(operation.get("x-implementation-issue").asString()).as(id).isEqualTo(ISSUES.get(id));
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1]);
            for (var code : operation.get("x-error-codes")) {
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            }
            boolean write = !methodAndPath[0].equals("get");
            assertThat(operation.get("parameters").toString().contains("#/components/parameters/Csrf")).as(id).isEqualTo(write);
        });
        assertThat(contract).contains("| 422 | `CURRENT_PASSWORD_MISMATCH` |", "| 422 | `PASSWORD_SAME_AS_CURRENT` |",
                "| 403 | `SELF_RESET_FORBIDDEN` |", "| 422 | `PASSWORD_RESET_LINK_INVALID` |",
                "| 422 | `AVATAR_TYPE_UNSUPPORTED` |", "| 422 | `AVATAR_TOO_LARGE` |");
        // Success statuses of the contract tables.
        assertThat(operation("changeMyPassword").get("responses").has("204")).isTrue();
        assertThat(operation("completePasswordReset").get("responses").has("204")).isTrue();
        assertThat(operation("requestPasswordReset").get("responses").has("202")).isTrue();
        // Only the reset-link operations are reachable without a session.
        for (String id : OPERATIONS.keySet()) {
            boolean open = id.equals("previewPasswordReset") || id.equals("completePasswordReset");
            assertThat(operation(id).has("security")).as(id).isEqualTo(open);
            assertThat(texts(operation(id).get("x-session-modes")).contains("ANONYMOUS")).as(id).isEqualTo(open);
        }
    }

    /** Acceptance 2: passwords and tokens appear only in request bodies, never in a response. */
    @Test void noResponseCarriesAPasswordATokenOrAStorageKey() {
        var reachable = new java.util.LinkedHashSet<String>();
        OPERATIONS.keySet().forEach(id -> operation(id).get("responses").properties().forEach(entry -> {
            if (entry.getKey().startsWith("2")) collect(entry.getValue(), reachable);
        }));
        assertThat(reachable).contains("PasswordResetRequested", "PasswordResetPreview", "LoginUnlockResult", "CurrentUser",
                "AuditEventPage", "AuditEventListItem", "AuditEventDetail");
        for (String name : reachable) {
            var properties = schema(name).get("properties");
            if (properties == null) continue;
            properties.properties().forEach(entry -> assertThat(SECRET_FIELDS).as("%s.%s", name, entry.getKey())
                    .doesNotContain(entry.getKey()));
            assertThat(schema(name).get("additionalProperties").asBoolean(true)).as(name).isFalse();
        }
        // Request bodies hold the secrets as write-only fields.
        assertThat(schema("PasswordChange").at("/properties/currentPassword/writeOnly").asBoolean()).isTrue();
        assertThat(schema("PasswordChange").at("/properties/newPassword/writeOnly").asBoolean()).isTrue();
        assertThat(schema("PasswordResetComplete").at("/properties/token/writeOnly").asBoolean()).isTrue();
        assertThat(schema("PasswordResetPreviewRequest").at("/properties/token/writeOnly").asBoolean()).isTrue();
        // The audit detail masks secrets again when it is read.
        assertThat(schema("AuditEventDetail").at("/properties/after/description").asString()).contains("password", "token", "***");
    }

    private void collect(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            var ref = node.get("$ref");
            if (ref != null && ref.asString().startsWith("#/components/schemas/")) {
                String name = ref.asString().substring("#/components/schemas/".length());
                if (names.add(name)) collect(schema(name), names);
            }
            node.properties().forEach(entry -> collect(entry.getValue(), names));
        } else if (node.isArray()) {
            for (var child : node) collect(child, names);
        }
    }

    /** Acceptance 3: each permission matches ADR-M14-001 and its 2026-11-10 decisions. */
    @Test void permissionsMatchTheAdr() throws Exception {
        String reset = operation("requestPasswordReset").get("x-permission").asString();
        assertThat(reset).contains("people:manage", "OWNER 대상은 다른 OWNER만");
        assertThat(operation("unlockEmployeeLogin").get("x-permission").asString()).startsWith(reset);
        assertThat(texts(operation("requestPasswordReset").get("x-error-codes"))).contains("SELF_RESET_FORBIDDEN");
        assertThat(texts(operation("requestPasswordReset").get("x-adr-decisions"))).contains("M14-D05", "M14-D11");
        for (String audit : List.of("searchAuditEvents", "getAuditEvent")) {
            assertThat(operation(audit).get("x-permission").asString()).as(audit).isEqualTo("OWNER(M14-D04)");
        }
        assertThat(operation("getEmployeeAvatar").get("x-permission").asString()).contains("OWNER", "활성 소속 지점", "M14-D14");
        assertThat(operation("getEmployeeAvatar").at("/responses/200/content").has("image/webp")).isTrue();
        assertThat(operation("uploadMyAvatar").at("/requestBody/content").has("multipart/form-data")).isTrue();
        assertThat(texts(operation("searchAuditEvents").get("x-error-codes"))).contains("PERIOD_TOO_LONG");
        assertThat(contract()).contains("최근 7일", "92일을 넘으면 422 `PERIOD_TOO_LONG`", "기존 비밀번호를 즉시 무효",
                "서명 URL은 쓰지 않는다");
        String adr = Files.readString(Path.of("docs/db/ADR-M14-001.md"));
        assertThat(adr).contains("| M14-D11 |", "| M14-D12 |", "| M14-D13 |", "| M14-D14 |", "2026-11-10 사용자 결정");
    }

    /** The photo path replaces the storage key in user responses when #120 lands, not before. */
    @Test void theAvatarChangeIsPlannedForItsIssue() {
        for (String name : List.of("SessionUser", "Employee")) {
            var planned = schema(name).get("x-planned-changes");
            assertThat(planned).as(name).isNotNull();
            assertThat(planned.toString()).as(name).contains("#120", "/api/employees/{id}/avatar");
        }
    }

    @Test void theContractIsPublishedAsTheM14Version() throws Exception {
        assertThat(spec.at("/info/version").asString()).isEqualTo("0.1.15-m14-account");
        assertThat(spec.at("/info/description").asString()).contains("Issue #118", "account-collab-api.md");
        assertThat(contract()).contains("`0.1.15-m14-account`", "operation은 모두 설계 계약(`design-target`)");
        assertThat(Files.readString(DESIGN.resolve("README.md"))).contains("account-collab-api.md", "0.1.15-m14-account");
    }
}
