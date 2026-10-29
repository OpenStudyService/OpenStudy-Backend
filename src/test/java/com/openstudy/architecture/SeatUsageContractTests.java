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
 * Issue #60: the published M5 contract defines every seat, check-in/out, move, adjustment and history operation,
 * keeps request keys mandatory on writes, never lets a client send a deduction, and keeps the exact-match lookup out
 * of the URL.
 */
class SeatUsageContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("listSeats", "get /seats"), Map.entry("createSeat", "post /seats"),
            Map.entry("getSeat", "get /seats/{id}"), Map.entry("updateSeat", "patch /seats/{id}"),
            Map.entry("changeSeatStatus", "post /seats/{id}/status"), Map.entry("getSeatBoard", "get /seats/board"),
            Map.entry("getSeatOccupancy", "get /seats/occupancy"),
            Map.entry("lookupCheckInCustomer", "post /customer-lookups/check-in"),
            Map.entry("listSeatSessions", "get /seat-sessions"), Map.entry("checkIn", "post /seat-sessions"),
            Map.entry("getSeatSession", "get /seat-sessions/{id}"),
            Map.entry("checkOut", "post /seat-sessions/{id}/check-out"),
            Map.entry("moveSeatSession", "post /seat-sessions/{id}/move"),
            Map.entry("adjustPass", "post /passes/{id}/adjustments"),
            Map.entry("getCustomerPassHistory", "get /customers/{id}/pass-history"));
    static final List<String> KEYED = List.of("createSeat", "checkIn", "checkOut", "moveSeatSession", "adjustPass");
    static final Pattern DEDUCTION = Pattern.compile("(?i).*(minutes|krw|amount|deduct|overage|billed).*");

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

    @Test void everySeatUsageOperationDefinesItsErrorResponsesAndIsDocumented() throws Exception {
        String contract = Files.readString(DESIGN.resolve("seat-usage-api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-error-codes").size()).as(id).isPositive();
            String[] methodAndPath = route.split(" ");
            assertThat(contract).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1] + "`");
        });
    }

    @Test void checkInOutMoveAndAdjustRequireAKeyAndRejectADifferentInputWith409() {
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

    @Test void checkInAndCheckOutRequestsCarryNoUsageOrDeduction() {
        for (String id : List.of("checkIn", "checkOut", "moveSeatSession")) {
            var schema = resolve(operation(id).at("/requestBody/content/application~1json/schema"));
            assertThat(schema.get("additionalProperties").asBoolean()).as(id).isFalse();
            assertThat(propertyNames(schema)).as(id).noneMatch(name -> DEDUCTION.matcher(name).matches());
        }
    }

    @Test void exactMatchLookupTakesItsConditionInTheBodyAndReturnsOnlyMinimalCustomerData() {
        var operation = operation("lookupCheckInCustomer");
        for (var parameter : operation.get("parameters")) assertThat(resolve(parameter).get("in").asString()).isNotEqualTo("query");
        var request = resolve(operation.at("/requestBody/content/application~1json/schema"));
        assertThat(propertyNames(request)).containsExactlyInAnyOrder("branchId", "memberNo", "phone");
        var candidate = spec.at("/components/schemas/CheckInCandidate");
        assertThat(propertyNames(candidate)).noneMatch(name -> name.matches("(?i).*(phone|email|memo|amount|price).*"));
        assertThat(spec.at("/components/schemas/CustomerSummary/properties").has("phone")).isFalse();
    }

    @Test void sessionResponsesSeparatePurchaseAndUseBranches() {
        assertThat(spec.at("/components/schemas/SeatSession/properties").has("branchId")).isTrue();
        assertThat(spec.at("/components/schemas/SeatSessionPass/properties").has("purchaseBranchId")).isTrue();
        assertThat(spec.at("/components/schemas/WorkspaceEvent/properties/resource/enum").toString())
                .contains("\"seats\"", "\"seat-sessions\"");
    }

    List<String> propertyNames(JsonNode schema) {
        var names = new ArrayList<String>();
        var resolved = resolve(schema);
        if (resolved.has("properties")) {
            for (var entry : resolved.get("properties").properties()) {
                names.add(entry.getKey());
                names.addAll(propertyNames(entry.getValue()));
            }
        }
        for (String combinator : List.of("allOf", "oneOf", "anyOf")) {
            if (resolved.has(combinator)) for (var child : resolved.get(combinator)) names.addAll(propertyNames(child));
        }
        return names;
    }
}
