package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #106: the published M12 contract defines every attendance-correction, leave and shift-swap operation with the
 * ADR-M12-001 permissions (requester only, the deciding branch's MANAGER, never the requester's own request), a state
 * table for each request, the target information of the conflict errors and the answer to repeated requests.
 */
class WorkforceRequestsContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    /** operationId → "method path", in the order of the contract document. */
    static final Map<String, String> OPERATIONS = new LinkedHashMap<>();
    static {
        OPERATIONS.put("listLeaveGrants", "get /leave-grants");
        OPERATIONS.put("createLeaveGrant", "post /leave-grants");
        OPERATIONS.put("getLeaveGrant", "get /leave-grants/{id}");
        OPERATIONS.put("adjustLeaveGrant", "post /leave-grants/{id}/adjust");
        OPERATIONS.put("getMyLeaveBalance", "get /me/leave-balance");
        OPERATIONS.put("getEmployeeLeaveBalance", "get /employees/{employeeId}/leave-balance");
        OPERATIONS.put("listLeaveRequests", "get /leave-requests");
        OPERATIONS.put("createLeaveRequest", "post /leave-requests");
        OPERATIONS.put("getLeaveRequest", "get /leave-requests/{id}");
        OPERATIONS.put("approveLeaveRequest", "post /leave-requests/{id}/approve");
        OPERATIONS.put("rejectLeaveRequest", "post /leave-requests/{id}/reject");
        OPERATIONS.put("cancelLeaveRequest", "post /leave-requests/{id}/cancel");
        OPERATIONS.put("cancelLeaveApproval", "post /leave-requests/{id}/cancel-approval");
        OPERATIONS.put("listAttendanceCorrections", "get /attendance-corrections");
        OPERATIONS.put("createAttendanceCorrection", "post /attendance-records/{id}/corrections");
        OPERATIONS.put("getAttendanceCorrection", "get /attendance-corrections/{id}");
        OPERATIONS.put("approveAttendanceCorrection", "post /attendance-corrections/{id}/approve");
        OPERATIONS.put("rejectAttendanceCorrection", "post /attendance-corrections/{id}/reject");
        OPERATIONS.put("cancelAttendanceCorrection", "post /attendance-corrections/{id}/cancel");
        OPERATIONS.put("listShiftSwaps", "get /shift-swaps");
        OPERATIONS.put("createShiftSwap", "post /shifts/{id}/swaps");
        OPERATIONS.put("getShiftSwap", "get /shift-swaps/{id}");
        OPERATIONS.put("approveShiftSwap", "post /shift-swaps/{id}/approve");
        OPERATIONS.put("rejectShiftSwap", "post /shift-swaps/{id}/reject");
        OPERATIONS.put("cancelShiftSwap", "post /shift-swaps/{id}/cancel");
        OPERATIONS.put("cancelShiftSwapApproval", "post /shift-swaps/{id}/cancel-approval");
    }
    static final List<String> CREATES = List.of("createLeaveGrant", "createLeaveRequest", "createAttendanceCorrection",
            "createShiftSwap");
    /** Decisions by the deciding branch's MANAGER, never on the requester's own request (2026-11-09 user decision). */
    static final List<String> DECISIONS = List.of("approveLeaveRequest", "rejectLeaveRequest", "cancelLeaveApproval",
            "approveAttendanceCorrection", "rejectAttendanceCorrection", "approveShiftSwap", "rejectShiftSwap",
            "cancelShiftSwapApproval");
    static final List<String> SELF_CANCELS = List.of("cancelLeaveRequest", "cancelAttendanceCorrection", "cancelShiftSwap");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws java.io.IOException {
        return Files.readString(DESIGN.resolve("workforce-requests-api.md"));
    }

    JsonNode operation(String id) {
        String[] methodAndPath = OPERATIONS.get(id).split(" ");
        return spec.get("paths").get(methodAndPath[1]).get(methodAndPath[0]);
    }

    JsonNode schema(String name) {
        return spec.at("/components/schemas/" + name);
    }

    List<String> names(JsonNode node) {
        var names = new ArrayList<String>();
        node.get("properties").properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    String body(String id) {
        return operation(id).at("/requestBody/content/application~1json/schema/$ref").asString();
    }

    String conflictSchema(String id) {
        return operation(id).at("/responses/409/content/application~1json/schema/$ref").asString();
    }

    @Test void everyRequestOperationIsDefinedWithItsErrorsAndDocumented() throws Exception {
        String contract = contract();
        String common = Files.readString(DESIGN.resolve("api.md")) + Files.readString(DESIGN.resolve("workforce-api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation).as(route).isNotNull();
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            assertThat(operation.get("x-error-codes").size()).as(id).isPositive();
            assertThat(operation.get("x-adr-decisions").toString()).as(id).contains("M12-D");
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1] + "`");
            for (var code : operation.get("x-error-codes")) {
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            }
        });
        // No other M12 operation hides in the specification under these paths.
        var published = new ArrayList<String>();
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            String route = method.getKey() + " " + path.getKey();
            if (route.matches(".*(/leave-|/attendance-corrections|/corrections|/shift-swaps|/swaps|leave-balance).*")) published.add(route);
        }));
        assertThat(published).containsExactlyInAnyOrderElementsOf(OPERATIONS.values());
    }

    @Test void theContractIsADesignTargetSplitAcrossTheImplementationIssues() throws Exception {
        // M12 was published in 0.1.13-m12-workforce-requests; later contracts raise the version.
        assertThat(Integer.parseInt(spec.at("/info/version").asString().split("[.-]")[2])).isGreaterThanOrEqualTo(13);
        OPERATIONS.forEach((id, route) -> {
            String issue = route.contains("leave") ? "#108" : route.contains("correction") ? "#107" : "#109";
            assertThat(operation(id).get("x-implementation-issue").asString()).as(id).isEqualTo(issue);
            assertThat(operation(id).get("x-implementation-status").asString()).as(id).isEqualTo("design-target");
        });
        assertThat(spec.at("/info/description").asString()).contains("Issue #106", "workforce-requests-api.md");
        assertThat(contract()).contains("`0.1.13-m12-workforce-requests`", "operation은 모두 설계 계약(`design-target`)");
        assertThat(Files.readString(DESIGN.resolve("README.md"))).contains("workforce-requests-api.md",
                "0.1.13-m12-workforce-requests");
    }

    /** Acceptance 1: each request has a state table and the allowed command per state. */
    @Test void eachRequestHasItsStateTransitionsAndAllowedCommands() throws Exception {
        String contract = contract();
        assertThat(texts(schema("RequestStatus").get("enum"))).containsExactly("신청", "승인", "반려", "취소");
        assertThat(contract).contains("| 현재 상태 | 승인 | 반려 | 신청 취소(본인) | 승인 취소(관리자) |",
                "| 신청 | 200 → 승인 | 200 → 반려 | 200 → 취소 | 409 `INVALID_STATE` |",
                "| 반려 | 409 `INVALID_STATE` | 409 `INVALID_STATE` | 409 `INVALID_STATE` | 409 `INVALID_STATE` |",
                "stateDiagram-v2");
        // Leave and swaps can be cancelled after approval by a manager; an approved correction never is (M12-D08).
        assertThat(OPERATIONS).containsKeys("cancelLeaveApproval", "cancelShiftSwapApproval");
        assertThat(OPERATIONS.values()).noneMatch(route -> route.startsWith("post /attendance-corrections/{id}/cancel-approval"));
        assertThat(contract).contains("승인된 근태 정정은 취소하지 않는다(M12-D08)");
        // Leave approval can be cancelled only before the leave starts (2026-11-09 user decision).
        assertThat(texts(operation("cancelLeaveApproval").get("x-error-codes"))).contains("LEAVE_ALREADY_STARTED");
        assertThat(contract).contains("**시작일 전까지만** 승인을 취소할 수 있다(2026-11-09 사용자 결정)");
        assertThat(texts(schema("RequestCancellation").at("/properties/kind/enum"))).containsExactly("신청 취소", "승인 취소");
    }

    /** Acceptance 2: permissions match ADR-M12-001 (M12-D02, D04, D05) and the self-decision ban. */
    @Test void permissionsFollowTheAdrAndNoOneDecidesTheirOwnRequest() throws Exception {
        String contract = contract();
        DECISIONS.forEach(id -> {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("MANAGER", "본인 신청 제외");
            assertThat(texts(operation(id).get("x-error-codes"))).as(id).contains("SELF_DECISION_FORBIDDEN", "INVALID_STATE",
                    "VERSION_CONFLICT");
        });
        SELF_CANCELS.forEach(id -> {
            assertThat(operation(id).get("x-permission").asString()).as(id).isEqualTo("신청자 본인");
            assertThat(texts(operation(id).get("x-error-codes"))).as(id).doesNotContain("SELF_DECISION_FORBIDDEN");
        });
        assertThat(operation("approveLeaveRequest").get("x-permission").asString()).contains("신청 지점의 MANAGER");
        assertThat(operation("createLeaveGrant").get("x-permission").asString()).contains("originBranchId 지점의 MANAGER");
        assertThat(operation("approveAttendanceCorrection").get("x-permission").asString()).contains("근태 기록 지점의 MANAGER");
        assertThat(operation("approveShiftSwap").get("x-permission").asString()).contains("근무 지점의 MANAGER");
        assertThat(operation("createShiftSwap").get("x-permission").asString()).contains("원래 담당자 본인");
        assertThat(operation("createAttendanceCorrection").get("x-permission").asString()).contains("근태의 직원 본인");
        assertThat(contract).contains("**본인 신청의 처리 금지(2026-11-09 사용자 결정)**", "403 `SELF_DECISION_FORBIDDEN`",
                "**동의 단계는 없다**(M12-D04)");
        // The requester is the session: the self commands take no employee field.
        assertThat(names(schema("LeaveRequestCreate"))).doesNotContain("employeeId").contains("branchId");
        assertThat(names(schema("AttendanceCorrectionCreate"))).doesNotContain("employeeId", "attendanceRecordId");
        assertThat(names(schema("ShiftSwapCreate"))).doesNotContain("employeeId", "requesterId");
    }

    /** Acceptance 3: conflict errors carry their target information. */
    @Test void conflictErrorsCarryTheirTargets() throws Exception {
        assertThat(names(schema("WorkforceRequestConflictError"))).contains("conflicts", "attendanceConflicts", "breakIds",
                "requiredDays", "availableDays", "overlappingRequestIds", "pendingCorrectionId", "pendingSwapId", "shiftId",
                "latestVersion", "approvedVersion", "reloadUrl");
        assertThat(schema("WorkforceRequestConflictError").at("/properties/conflicts/items/$ref").asString())
                .isEqualTo("#/components/schemas/ShiftConflict");
        assertThat(names(schema("AttendanceConflict"))).contains("visible", "label", "overlapStartsAt", "overlapEndsAt",
                "attendanceRecordId");
        for (String id : List.of("approveLeaveRequest", "approveAttendanceCorrection", "approveShiftSwap",
                "cancelShiftSwapApproval", "createLeaveRequest", "createAttendanceCorrection", "createShiftSwap")) {
            assertThat(conflictSchema(id)).as(id).isEqualTo("#/components/schemas/WorkforceRequestConflictError");
        }
        assertThat(texts(operation("approveLeaveRequest").get("x-error-codes"))).contains("LEAVE_SHIFT_CONFLICT");
        assertThat(texts(operation("approveAttendanceCorrection").get("x-error-codes"))).contains("CORRECTION_OVERLAP",
                "CORRECTION_BREAK_OUTSIDE");
        assertThat(texts(operation("approveShiftSwap").get("x-error-codes"))).contains("SHIFT_VERSION_CONFLICT", "SHIFT_CONFLICT");
        assertThat(texts(operation("cancelShiftSwapApproval").get("x-error-codes"))).contains("SWAP_RESTORE_CONFLICT");
        // The swap approval names the shift version the manager saw, so a changed shift is reported, not overwritten.
        assertThat(body("approveShiftSwap")).isEqualTo("#/components/schemas/ShiftSwapApprove");
        assertThat(texts(schema("ShiftSwapApprove").get("required"))).containsExactly("version", "shiftVersion");
        assertThat(names(schema("ShiftSwap"))).contains("shiftVersion", "originalStartsAt", "proposedStartsAt");
        assertThat(contract()).contains("| 409 | `LEAVE_SHIFT_CONFLICT` |", "| 409 | `CORRECTION_OVERLAP` |",
                "| 409 | `SHIFT_VERSION_CONFLICT` |", "| 409 | `SWAP_RESTORE_CONFLICT` |");
    }

    /** Acceptance 4: repeated and concurrent requests get the same result or a 409. */
    @Test void repeatedAndConcurrentRequestsAreDefined() throws Exception {
        CREATES.forEach(id -> {
            assertThat(operation(id).get("parameters").toString()).as(id).contains("#/components/parameters/Idempotency");
            assertThat(texts(operation(id).get("x-error-codes"))).as(id).contains("IDEMPOTENCY_CONFLICT", "REQUEST_IN_PROGRESS");
            assertThat(operation(id).get("responses").has("201")).as(id).isTrue();
        });
        for (String id : OPERATIONS.keySet()) {
            if (CREATES.contains(id) || !OPERATIONS.get(id).startsWith("post")) continue;
            assertThat(operation(id).get("parameters").toString()).as(id).doesNotContain("#/components/parameters/Idempotency");
            String body = body(id);
            assertThat(schema(body.substring(body.lastIndexOf('/') + 1)).get("required").toString()).as(id).contains("version");
        }
        assertThat(contract()).contains("## 요청 키와 반복·동시 요청", "**동시 승인·반려**", "**중복 차감·복원 방지(M12-D07)**",
                "`leave:{신청 ID}:use:{부여 ID}`", "`leave:{신청 ID}:restore:{부여 ID}`", "검사 순서**: 상태 → version");
    }

    @Test void theLeaveRulesFollowM12D06() throws Exception {
        var days = schema("LeaveRequestCreate").at("/properties/requestedDays");
        assertThat(days.get("multipleOf").asDouble()).isEqualTo(0.5);
        assertThat(days.get("minimum").asDouble()).isEqualTo(0.5);
        assertThat(contract()).contains("| 12-30 | 01-02 | 4 | 422(연도를 넘음) |", "| 11-10 | 11-12 | 3.5 | 422(달력 일수 초과) |",
                "만료일(`validThrough`)이 빠른 순", "`leave_requests`에 신청 지점 `branch_id`");
    }
}
