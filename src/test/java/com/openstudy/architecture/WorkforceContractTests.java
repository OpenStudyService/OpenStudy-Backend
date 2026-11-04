package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #82: the published M8 contract defines every shift and attendance operation, keeps shift management to the
 * branch MANAGER (M8-D01), never lets a branch choice open another employee's record (M8-D02), hides other branches in
 * a conflict (M8-D05), takes no employee or record id in the self commands (M8-D08) and fixes the worked-minute rule
 * with examples (M8-D07).
 */
class WorkforceContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("listShifts", "get /shifts"), Map.entry("createShift", "post /shifts"),
            Map.entry("getWeeklyShiftSchedule", "get /shifts/weekly"),
            Map.entry("getShift", "get /shifts/{id}"), Map.entry("updateShift", "patch /shifts/{id}"),
            Map.entry("cancelShift", "post /shifts/{id}/cancel"),
            Map.entry("getMyCurrentAttendance", "get /me/attendance/current"),
            Map.entry("clockIn", "post /me/attendance/clock-in"),
            Map.entry("startBreak", "post /me/attendance/break-start"),
            Map.entry("endBreak", "post /me/attendance/break-end"),
            Map.entry("clockOut", "post /me/attendance/clock-out"),
            Map.entry("listAttendanceRecords", "get /attendance-records"),
            Map.entry("getAttendanceRecord", "get /attendance-records/{id}"));
    static final List<String> SHIFT_OPERATIONS = List.of("listShifts", "createShift", "getWeeklyShiftSchedule", "getShift",
            "updateShift", "cancelShift");
    static final List<String> SELF_COMMANDS = List.of("clockIn", "startBreak", "endBreak", "clockOut");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws java.io.IOException {
        return Files.readString(DESIGN.resolve("workforce-api.md"));
    }

    JsonNode operation(String id) {
        String[] methodAndPath = OPERATIONS.get(id).split(" ");
        return spec.get("paths").get(methodAndPath[1]).get(methodAndPath[0]);
    }

    JsonNode resolve(JsonNode node) {
        if (!node.has("$ref")) return node;
        return spec.at(node.get("$ref").asString().substring(1));
    }

    JsonNode schema(String name) {
        return spec.at("/components/schemas/" + name);
    }

    List<String> names(JsonNode node) {
        var names = new ArrayList<String>();
        node.get("properties").properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    List<String> parameters(String id) {
        var names = new ArrayList<String>();
        for (var parameter : operation(id).get("parameters")) names.add(resolve(parameter).get("name").asString());
        return names;
    }

    String body(String id) {
        return operation(id).at("/requestBody/content/application~1json/schema/$ref").asString();
    }

    @Test void everyWorkforceOperationDefinesItsErrorResponsesAndIsDocumented() throws Exception {
        String contract = contract();
        String common = Files.readString(DESIGN.resolve("api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            assertThat(operation.get("x-error-codes").size()).as(id).isPositive();
            assertThat(operation.get("x-adr-decisions").toString()).as(id).contains("M8-D");
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1] + "`");
            for (var code : operation.get("x-error-codes")) {
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            }
        });
    }

    @Test void theContractIsSplitBetweenTheScheduleAndAttendanceIssues() {
        assertThat(spec.at("/info/version").asString()).isEqualTo("0.1.10-m8-workforce");
        OPERATIONS.keySet().forEach(id -> {
            String issue = SHIFT_OPERATIONS.contains(id) ? "#83" : "#84";
            assertThat(operation(id).get("x-implementation-issue").asString()).as(id).isEqualTo(issue);
        });
    }

    /** #83 implemented the shift operations and #84 the attendance ones: every M8 operation is now implemented. */
    @Test void issues83And84ImplementedEveryWorkforceOperation() throws Exception {
        OPERATIONS.keySet().forEach(id -> assertThat(operation(id).get("x-implementation-status").asString()).as(id)
                .isEqualTo("implemented"));
        assertThat(spec.at("/info/description").asString()).contains("주간 근무표·근무 목록·등록·상세·수정·취소 operation은 #83에서 구현됐다",
                "현재 근태·출근·휴게 시작·휴게 종료·퇴근·근태 목록·근태 상세 operation은 #84에서 구현됐다");
        assertThat(contract()).contains("근무표 operation 6개(주간 근무표·목록·등록·상세·수정·취소)는 #83에서 구현됐다(`implemented`)",
                "근태 operation 7개(현재 근태·출근·휴게 시작·휴게 종료·퇴근·근태 목록·근태 상세)는 #84에서 구현됐다(`implemented`)",
                "M8 operation 13개가 모두 구현됐다")
                .doesNotContain("아직 서버에 구현되어 있지 않다(#84)");
    }

    @Test void onlyCreatingCommandsTakeARequestKeyAndTheRestCarryAVersion() {
        for (String id : List.of("createShift", "clockIn")) {
            boolean required = false;
            for (var parameter : operation(id).get("parameters")) {
                var resolved = resolve(parameter);
                if (resolved.get("name").asString().equals("Idempotency-Key")) required = resolved.get("required").asBoolean();
            }
            assertThat(required).as(id).isTrue();
            assertThat(operation(id).get("x-error-codes").toString()).as(id).contains("\"IDEMPOTENCY_CONFLICT\"");
            assertThat(operation(id).get("description").asString()).as(id).contains("같은 키·다른 입력");
        }
        for (String id : List.of("updateShift", "cancelShift", "startBreak", "endBreak", "clockOut")) {
            assertThat(parameters(id)).as(id).doesNotContain("Idempotency-Key");
            assertThat(spec.at(body(id).substring(1)).get("required").toString()).as(id).contains("\"version\"");
            assertThat(operation(id).get("x-error-codes").toString()).as(id).contains("\"VERSION_CONFLICT\"");
        }
    }

    @Test void shiftManagementBelongsToTheBranchManagerAndReadsFollowTheRecordedBranch() {
        for (String id : List.of("createShift", "updateShift", "cancelShift")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("MANAGER", "M8-D01")
                    .doesNotContain(":write", "people:manage");
        }
        for (String id : List.of("listShifts", "getWeeklyShiftSchedule", "listAttendanceRecords")) {
            assertThat(operation(id).get("x-permission").asString()).as(id)
                    .contains("M8-D02", "기록 지점의 MANAGER", "본인 기록");
            assertThat(parameters(id)).as(id).contains("scope", "employeeId", "mine");
        }
        for (String id : List.of("getShift", "getAttendanceRecord")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("M8-D02");
            assertThat(operation(id).get("x-error-codes").toString()).as(id).contains("\"NOT_FOUND\"");
        }
    }

    @Test void choosingABranchNeverOpensAnotherEmployeesRecord() throws Exception {
        assertThat(contract()).contains("`OWNER ∨ branch_id ∈ 요청자가 MANAGER인 지점 ∨ employee_id = 요청자`",
                "`scope`는 허용 지점을 좁히기만 하고 직원 조건을 풀지 않는다",
                "일반 직원이 다른 직원 ID를 넣으면 403이 아니라 빈 목록이다");
        assertThat(operation("listAttendanceRecords").get("description").asString())
                .contains("지점 선택(scope)만으로 다른 직원의 기록이 열리지 않는다");
    }

    @Test void selfCommandsNameNoEmployeeAndNoRecord() {
        for (String id : SELF_COMMANDS) {
            String route = OPERATIONS.get(id);
            assertThat(route).as(id).contains(" /me/attendance/").doesNotContain("{");
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("본인");
            var input = spec.at(body(id).substring(1));
            assertThat(input.get("additionalProperties").asBoolean()).as(id).isFalse();
            assertThat(names(input)).as(id).noneMatch(field -> field.toLowerCase().contains("employee")
                    || field.equals("attendanceId") || field.equals("id") || field.toLowerCase().endsWith("at"));
        }
        assertThat(names(schema("ClockIn"))).containsExactly("branchId");
        assertThat(body("startBreak")).endsWith("/VersionCommand");
    }

    @Test void shiftEditsCannotMoveTheShiftToAnotherBranchOrEmployee() {
        assertThat(names(schema("ShiftUpdate"))).doesNotContain("branchId", "employeeId", "status");
        assertThat(operation("updateShift").get("x-error-codes").toString()).contains("\"IMMUTABLE_FIELD\"");
        assertThat(names(schema("ShiftCreate"))).doesNotContain("status", "createdBy");
        assertThat(spec.at("/paths/~1shifts~1{id}").has("delete")).isFalse();
    }

    @Test void inputErrorsAndConflictsAreDifferentCodes() {
        for (String id : List.of("createShift", "updateShift")) {
            assertThat(operation(id).get("x-error-codes").toString()).as(id).contains("\"SHIFT_TIME_INVALID\"",
                    "\"SHIFT_BREAK_INVALID\"", "\"INVALID_SHIFT_EMPLOYEE\"", "\"SHIFT_CONFLICT\"", "\"BRANCH_NOT_ACTIVE\"");
        }
        assertThat(operation("clockIn").get("x-error-codes").toString())
                .contains("\"ATTENDANCE_ALREADY_WORKING\"", "\"BRANCH_NOT_ACTIVE\"", "\"FORBIDDEN\"");
        assertThat(operation("startBreak").get("x-error-codes").toString()).contains("\"ATTENDANCE_ON_BREAK\"", "\"ATTENDANCE_NOT_WORKING\"");
        assertThat(operation("endBreak").get("x-error-codes").toString()).contains("\"ATTENDANCE_NOT_ON_BREAK\"");
        assertThat(operation("clockOut").get("x-error-codes").toString()).contains("\"ATTENDANCE_ON_BREAK\"", "\"ATTENDANCE_NOT_WORKING\"")
                .doesNotContain("\"BRANCH_NOT_ACTIVE\"");
        assertThat(operation("cancelShift").get("x-error-codes").toString()).doesNotContain("\"BRANCH_NOT_ACTIVE\"");
    }

    @Test void aConflictShowsTheOverlapAndHidesBranchesTheRequesterCannotSee() throws Exception {
        for (String id : List.of("createShift", "updateShift")) {
            assertThat(operation(id).at("/responses/409/content/application~1json/schema/$ref").asString()).as(id)
                    .endsWith("/ShiftConflictError");
        }
        var error = schema("ShiftConflictError");
        assertThat(error.get("required").toString()).contains("\"code\"", "\"message\"", "\"requestId\"");
        assertThat(names(error)).containsAll(names(schema("ApiError"))).contains("conflicts");
        var conflict = schema("ShiftConflict");
        assertThat(names(conflict)).containsExactlyInAnyOrder("kind", "visible", "label", "overlapStartsAt", "overlapEndsAt", "shift");
        assertThat(conflict.at("/properties/label/enum").toString()).contains("다른 지점 근무", "승인 휴가");
        assertThat(conflict.at("/properties/kind/enum").toString()).contains("근무", "휴가");
        assertThat(conflict.at("/properties/shift/nullable").asBoolean()).isTrue();
        assertThat(names(schema("ShiftConflictTarget"))).containsExactlyInAnyOrder("id", "branchId", "startsAt", "endsAt");
        assertThat(contract()).contains("지점 ID, 근무 ID, 상대 근무의 원래 시작·종료", "겹친 구간을 시간순으로 합쳐",
                "`[starts_on 00:00, ends_on 다음 날 00:00)` Asia/Seoul");
    }

    @Test void shiftLengthRulesCarryBoundaryExamples() throws Exception {
        assertThat(contract()).contains(
                "| 11-04 22:00 | 11-05 06:00 | 30 | 480 | 201(자정 넘김) |",
                "| 11-04 09:00 | 11-05 09:00 | 0 | 1440 | 201(정확히 24시간) |",
                "| 11-04 09:00 | 11-05 09:01 | 0 | 1441 | 422 `SHIFT_TIME_INVALID` |",
                "| 11-04 09:00 | 11-04 09:00 | 0 | 0 | 422 `SHIFT_TIME_INVALID` |",
                "| 11-04 09:00 | 11-04 17:00 | 479 | 480 | 201 |",
                "| 11-04 09:00 | 11-04 17:00 | 480 | 480 | 422 `SHIFT_BREAK_INVALID` |");
        assertThat(schema("ShiftCreate").at("/properties/plannedBreakMinutes/minimum").asInt()).isZero();
    }

    @Test void workedMinutesTruncateOnceAndNeverGoNegative() throws Exception {
        assertThat(contract()).contains(
                "workedMinutes = max(0, floor(workedSeconds ÷ 60))",
                "| 09:00:00 | 18:00:59 | 없음 | 0 | 32459 | 540 |",
                "| 09:00:30 | 09:01:29 | 없음 | 0 | 59 | 0 |",
                "| 09:00:00 | 18:00:00 | 12:00:00~12:30:30, 15:00:00~15:10:45 | 2475 | 32400 − 2475 = 29925 | 498 |",
                "| 22:00:00 | 익일 06:30:00 | 02:00:00~02:30:00 | 1800 | 30600 − 1800 = 28800 | 480 |",
                "| 09:00:00 | 09:10:00 | 09:00:00~09:10:00 | 600 | 0 | 0 |");
        for (String name : List.of("AttendanceRecord", "AttendanceSummary")) {
            assertThat(schema(name).at("/properties/workedMinutes/minimum").asInt()).as(name).isZero();
            assertThat(schema(name).at("/properties/workedMinutes/nullable").asBoolean()).as(name).isTrue();
        }
        for (String id : List.of("listAttendanceRecords", "getAttendanceRecord")) {
            assertThat(operation(id).get("x-adr-decisions").toString()).as(id).contains("M8-D07");
        }
    }

    @Test void plannedShiftsAndActualAttendanceAreSeparateShapes() {
        var weekly = schema("WeeklyShiftSchedule");
        assertThat(weekly.at("/properties/shifts/items/$ref").asString()).endsWith("/Shift");
        assertThat(weekly.at("/properties/attendance/items/$ref").asString()).endsWith("/AttendanceRecord");
        assertThat(weekly.at("/properties/timeZone/enum").toString()).contains("Asia/Seoul");
        assertThat(schema("ShiftStatus").get("enum").toString()).contains("예정", "취소").doesNotContain("근무 중");
        assertThat(schema("AttendanceStatus").get("enum").toString()).contains("근무 중", "휴게 중", "퇴근");
        assertThat(names(schema("Shift"))).contains("workDate", "plannedBreakMinutes", "attendance", "cancellation")
                .doesNotContain("clockInAt");
        assertThat(names(schema("AttendanceRecord"))).contains("shiftId", "clockInAt", "clockOutAt", "breaks", "workDate");
        assertThat(parameters("getWeeklyShiftSchedule")).contains("weekOf").doesNotContain("page", "pageSize");
    }

    @Test void timesAreStoredInUtcAndDaysAndWeeksFollowSeoul() throws Exception {
        assertThat(contract()).contains("월요일 00:00부터 다음 월요일 00:00 전까지(Asia/Seoul)",
                "자정을 넘는 근무는 **시작한 날과 그 주**에만 나온다",
                "| 11-08(일) 22:00 ~ 11-09(월) 06:00 | 11-08 13:00Z ~ 11-08 21:00Z | 11-08 | 11-02 ~ 11-08 |",
                "| 11-09(월) 08:00 ~ 17:00 | 11-08 23:00Z ~ 11-09 08:00Z | 11-09 | 11-09 ~ 11-15 |");
    }

    @Test void concurrencyIsGuardedPerEmployeeAndByTheActiveAttendanceKey() throws Exception {
        assertThat(contract()).contains("대상 직원의 `employees` 행을 `FOR UPDATE`로 잠가",
                "잠금 단위는 지점이 아니라 **직원**이어야 한다", "`active_employee_id`", "`uk_attendance_records_1`");
    }

    @Test void stoppedBranchesRefuseNewWorkButAllowCleanUp() throws Exception {
        assertThat(contract()).contains("| 근무 등록·수정 | 409 `BRANCH_NOT_ACTIVE` |", "| 근무 취소 | 허용 |",
                "| 출근 | 409 `BRANCH_NOT_ACTIVE` |", "| 진행 중 근태의 휴게 시작·종료·퇴근 | 허용 |");
    }

    @Test void workspaceEventsCoverShiftsAndAttendance() {
        var resources = spec.at("/components/schemas/WorkspaceEvent/properties/resource/enum").toString();
        assertThat(resources).contains("\"shifts\"", "\"attendance-records\"");
    }

    @Test void leaveSwapsAndCorrectionsStayOutOfThisContract() {
        spec.get("paths").properties().forEach(entry -> assertThat(entry.getKey())
                .doesNotContain("leave", "swap", "correction", "substitution"));
    }
}
