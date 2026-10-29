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

/** Issue #55: the published M4 contract keeps idempotency mandatory and never accepts a client amount. */
class CustomerSalesContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("listCustomers", "get /customers"), Map.entry("createCustomer", "post /customers"),
            Map.entry("getCustomer", "get /customers/{id}"), Map.entry("updateCustomer", "patch /customers/{id}"),
            Map.entry("changeCustomerStatus", "post /customers/{id}/status"),
            Map.entry("listPayments", "get /payments"), Map.entry("createPayment", "post /payments"),
            Map.entry("getPayment", "get /payments/{id}"), Map.entry("refundPayment", "post /payments/{id}/refund"),
            Map.entry("listPasses", "get /passes"), Map.entry("getPass", "get /passes/{id}"));
    static final Pattern AMOUNT = Pattern.compile("(?i).*(amount|price|krw|quantity).*");

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

    @Test void everyCustomerPaymentPassAndRefundOperationDefinesItsErrorResponses() throws Exception {
        String contract = Files.readString(DESIGN.resolve("customer-sales-api.md"));
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

    @Test void paymentCreationAndRefundRequireAnIdempotencyKeyAndRejectADifferentInputWith409() {
        for (String id : List.of("createPayment", "refundPayment", "createCustomer")) {
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
        assertThat(spec.at("/components/parameters/Idempotency/description").asString()).contains("다른 입력은 409");
    }

    @Test void noPaymentOrRefundRequestFieldAcceptsAClientAmount() {
        for (String id : List.of("createPayment", "refundPayment")) {
            var schema = resolve(operation(id).at("/requestBody/content/application~1json/schema"));
            assertThat(schema.get("additionalProperties").asBoolean()).as(id).isFalse();
            assertThat(propertyNames(schema)).as(id).isNotEmpty().noneMatch(name -> AMOUNT.matcher(name).matches());
        }
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
