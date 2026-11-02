package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #67: the published M6 contract defines every room, reservation, locker, assignment and cross-branch operation,
 * keeps request keys mandatory on paid commands, never lets a client send an amount, separates input errors from
 * conflicts, and limits the cross-branch summary to the fields POL-01 allows.
 */
class SpaceOperationsContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("listRooms", "get /rooms"), Map.entry("createRoom", "post /rooms"),
            Map.entry("getRoom", "get /rooms/{id}"), Map.entry("updateRoom", "patch /rooms/{id}"),
            Map.entry("changeRoomStatus", "post /rooms/{id}/status"),
            Map.entry("listRoomReservations", "get /room-reservations"),
            Map.entry("createRoomReservation", "post /room-reservations"),
            Map.entry("getRoomReservationCalendar", "get /room-reservations/calendar"),
            Map.entry("getRoomReservation", "get /room-reservations/{id}"),
            Map.entry("cancelRoomReservation", "post /room-reservations/{id}/cancel"),
            Map.entry("startRoomUsage", "post /room-reservations/{id}/start"),
            Map.entry("completeRoomUsage", "post /room-reservations/{id}/complete"),
            Map.entry("listLockers", "get /lockers"), Map.entry("createLocker", "post /lockers"),
            Map.entry("getLockerOccupancy", "get /lockers/occupancy"), Map.entry("getLocker", "get /lockers/{id}"),
            Map.entry("updateLocker", "patch /lockers/{id}"), Map.entry("changeLockerStatus", "post /lockers/{id}/status"),
            Map.entry("applyBranchLockerRate", "post /branches/{id}/locker-rate"),
            Map.entry("listLockerAssignments", "get /locker-assignments"),
            Map.entry("createLockerAssignment", "post /locker-assignments"),
            Map.entry("getLockerAssignment", "get /locker-assignments/{id}"),
            Map.entry("extendLockerAssignment", "post /locker-assignments/{id}/extend"),
            Map.entry("returnLocker", "post /locker-assignments/{id}/return"),
            Map.entry("getCrossBranchSummary", "post /customers/{id}/cross-branch-summary"),
            Map.entry("getCustomerSpaceHistory", "get /customers/{id}/space-history"));
    static final List<String> KEYED = List.of("createRoom", "createRoomReservation", "cancelRoomReservation", "createLocker",
            "applyBranchLockerRate", "createLockerAssignment", "extendLockerAssignment");
    static final Pattern MONEY = Pattern.compile("(?i).*(amount|krw|hours|terms|rate).*");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
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

    @Test void everySpaceOperationDefinesItsErrorResponsesAndIsDocumented() throws Exception {
        String contract = Files.readString(DESIGN.resolve("space-operations-api.md"));
        String common = Files.readString(DESIGN.resolve("api.md")) + Files.readString(DESIGN.resolve("seat-usage-api.md"))
                + Files.readString(DESIGN.resolve("customer-sales-api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1] + "`");
            for (var code : operation.get("x-error-codes")) {
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            }
        });
    }

    @Test void issue69ImplementedTheRoomAndBookingOperationsAndLeftTheRestAsDesigned() {
        for (String id : List.of("listRooms", "createRoom", "getRoom", "updateRoom", "changeRoomStatus", "listRoomReservations",
                "createRoomReservation", "getRoomReservationCalendar", "getRoomReservation")) {
            assertThat(operation(id).get("x-implementation-status").asString()).as(id).isEqualTo("implemented");
        }
        for (String id : List.of("cancelRoomReservation", "startRoomUsage", "completeRoomUsage")) {
            assertThat(operation(id).get("x-implementation-status").asString()).as(id).isEqualTo("design-target");
        }
    }

    @Test void paidCommandsRequireARequestKeyAndRejectADifferentInputWith409() {
        for (String id : KEYED) {
            var operation = operation(id);
            boolean required = false;
            for (var parameter : operation.get("parameters")) {
                var resolved = resolve(parameter);
                if (resolved.get("name").asString().equals("Idempotency-Key")) required = resolved.get("required").asBoolean();
            }
            assertThat(required).as(id).isTrue();
            assertThat(operation.get("x-error-codes").toString()).as(id).contains("\"IDEMPOTENCY_CONFLICT\"");
            assertThat(operation.get("description").asString()).as(id).contains("같은 키·다른 입력");
        }
    }

    @Test void noPaidRequestCarriesAnAmount() {
        for (String name : List.of("RoomReservationCreate", "RoomReservationCancel", "LockerAssignmentCreate",
                "LockerAssignmentExtend")) {
            assertThat(schema(name).get("additionalProperties").asBoolean()).as(name).isFalse();
            assertThat(names(schema(name))).as(name).noneMatch(field -> MONEY.matcher(field).matches());
        }
    }

    @Test void inputErrorsAndConflictsAreDifferentCodes() {
        var reservation = operation("createRoomReservation").get("x-error-codes").toString();
        assertThat(reservation).contains("\"ROOM_BRANCH_MISMATCH\"", "\"RESERVATION_TIME_INVALID\"",
                "\"RESERVATION_CAPACITY_EXCEEDED\"", "\"ROOM_NOT_AVAILABLE\"", "\"ROOM_TIME_CONFLICT\"");
        var assignment = operation("createLockerAssignment").get("x-error-codes").toString();
        assertThat(assignment).contains("\"LOCKER_TERM_INVALID\"", "\"LOCKER_PERIOD_CONFLICT\"");
    }

    @Test void pricingRulesCarryWorkedExamples() throws Exception {
        String contract = Files.readString(DESIGN.resolve("space-operations-api.md"));
        assertThat(contract).contains("| 8,000원 | 14:00~17:00 | 3 | 24,000원 |");
        assertThat(contract).contains("| 12,000원 | 2026-11-01 | 2026-12-27 | 56 | 2 | 24,000원 |");
    }

    @Test void permissionsFollowThePolicy() {
        for (String id : List.of("createRoom", "updateRoom", "changeRoomStatus", "createLocker", "updateLocker",
                "changeLockerStatus", "applyBranchLockerRate")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("operations:write");
        }
        for (String id : List.of("createRoomReservation", "cancelRoomReservation", "createLockerAssignment", "extendLockerAssignment")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("payments:write");
        }
        for (String id : List.of("startRoomUsage", "completeRoomUsage", "returnLocker")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("유효 소속").doesNotContain(":write");
        }
        assertThat(operation("getCrossBranchSummary").get("x-permission").asString()).contains("customers:cross-branch-read");
        assertThat(schema("PermissionCode").get("enum").toString()).contains("\"customers:cross-branch-read\"");
    }

    @Test void theCrossBranchSummaryIsLimitedAndNeedsAReason() {
        assertThat(schema("CrossBranchSummaryRequest").get("required").toString()).contains("\"reason\"");
        assertThat(names(schema("CrossBranchPass"))).containsExactlyInAnyOrder("kind", "productName", "status", "expiresAt");
        assertThat(names(schema("CrossBranchUsage"))).containsExactlyInAnyOrder("kind", "branchId", "startAt", "endAt");
        assertThat(names(schema("CrossBranchSummary"))).containsExactlyInAnyOrder("customerId", "passes", "usages", "asOf");
        for (var parameter : operation("getCrossBranchSummary").get("parameters")) {
            assertThat(resolve(parameter).get("name").asString()).isNotEqualTo("reason");
        }
    }

    @Test void customersAreMinimalAndBillingCanBeHidden() {
        assertThat(schema("RoomReservation").at("/properties/customer/$ref").asString()).endsWith("/CustomerSummary");
        assertThat(schema("LockerAssignment").at("/properties/customer/$ref").asString()).endsWith("/CustomerSummary");
        assertThat(schema("RoomReservation").at("/properties/billing/nullable").asBoolean()).isTrue();
        assertThat(schema("LockerAssignment").at("/properties/charges/nullable").asBoolean()).isTrue();
    }

    @Test void paymentsGainThePurposesTheSchemaAlreadyHas() {
        assertThat(schema("Payment").at("/properties/purpose/enum").toString()).contains("이용권", "스터디룸", "사물함");
        assertThat(schema("Payment").at("/properties/productId/nullable").asBoolean()).isTrue();
        assertThat(names(schema("Payment"))).contains("roomReservationId", "lockerAssignmentId");
    }
}
