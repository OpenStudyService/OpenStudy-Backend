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
 * Issue #74: the published M7 contract defines every task, channel, message, card, facility, inquiry and notice
 * operation, keeps the client message key mandatory, gives messages a cursor instead of an offset, versions every
 * edit, keeps tasks undeletable and shares its filters between lists and summaries.
 */
class CollaborationContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("listTasks", "get /tasks"), Map.entry("createTask", "post /tasks"),
            Map.entry("getTaskSummary", "get /tasks/summary"), Map.entry("getTask", "get /tasks/{id}"),
            Map.entry("updateTask", "patch /tasks/{id}"), Map.entry("changeTaskStatus", "post /tasks/{id}/status"),
            Map.entry("listTaskEvents", "get /tasks/{id}/events"),
            Map.entry("addTaskChecklistItem", "post /tasks/{id}/checklist-items"),
            Map.entry("updateTaskChecklistItem", "patch /tasks/{id}/checklist-items/{itemId}"),
            Map.entry("deleteTaskChecklistItem", "delete /tasks/{id}/checklist-items/{itemId}"),
            Map.entry("listAssigneeCandidates", "get /assignee-candidates"),
            Map.entry("listMyChannels", "get /channels"), Map.entry("createChannel", "post /channels"),
            Map.entry("listManageableChannels", "get /channels/manageable"),
            Map.entry("listChannelMemberCandidates", "get /channels/member-candidates"),
            Map.entry("getChannel", "get /channels/{id}"), Map.entry("updateChannel", "patch /channels/{id}"),
            Map.entry("listChannelMembers", "get /channels/{id}/members"),
            Map.entry("replaceChannelMembers", "put /channels/{id}/members"),
            Map.entry("markChannelRead", "post /channels/{id}/read"),
            Map.entry("listChannelMessages", "get /channels/{id}/messages"),
            Map.entry("sendChannelMessage", "post /channels/{id}/messages"),
            Map.entry("getChannelMessage", "get /channels/{id}/messages/{messageId}"),
            Map.entry("listMessageReplies", "get /channels/{id}/messages/{messageId}/replies"),
            Map.entry("shareTaskCard", "post /channels/{id}/task-cards"),
            Map.entry("listFacilityTypes", "get /facility-types"), Map.entry("createFacilityType", "post /facility-types"),
            Map.entry("listFacilities", "get /facilities"), Map.entry("createFacility", "post /facilities"),
            Map.entry("getFacilitySummary", "get /facilities/summary"), Map.entry("getFacility", "get /facilities/{id}"),
            Map.entry("updateFacility", "patch /facilities/{id}"),
            Map.entry("completeFacilityInspection", "post /facilities/{id}/inspection/complete"),
            Map.entry("listInquiries", "get /inquiries"), Map.entry("createInquiry", "post /inquiries"),
            Map.entry("getInquirySummary", "get /inquiries/summary"), Map.entry("getInquiry", "get /inquiries/{id}"),
            Map.entry("listInquiryEvents", "get /inquiries/{id}/events"),
            Map.entry("changeInquiryAssignee", "post /inquiries/{id}/assignee"),
            Map.entry("answerInquiry", "post /inquiries/{id}/answer"),
            Map.entry("changeInquiryStatus", "post /inquiries/{id}/status"),
            Map.entry("listNotices", "get /notices"), Map.entry("createNotice", "post /notices"),
            Map.entry("getNotice", "get /notices/{id}"), Map.entry("updateNotice", "patch /notices/{id}"),
            Map.entry("changeNoticeStatus", "post /notices/{id}/status"));
    static final List<String> KEYED = List.of("createTask", "createChannel", "createFacilityType", "createFacility",
            "completeFacilityInspection", "createInquiry", "createNotice");
    static final List<String> VERSIONED = List.of("TaskUpdate", "TaskStatusChange", "TaskChecklistItemCreate",
            "TaskChecklistItemUpdate", "ChannelUpdate", "ChannelMembersReplace", "FacilityUpdate",
            "FacilityInspectionComplete", "InquiryAssigneeChange", "InquiryAnswer", "InquiryStatusChange",
            "NoticeUpdate", "NoticeStatusChange");

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

    List<String> parameters(String id) {
        var names = new ArrayList<String>();
        for (var parameter : operation(id).get("parameters")) names.add(resolve(parameter).get("name").asString());
        return names;
    }

    @Test void everyCollaborationOperationDefinesItsErrorResponsesAndIsDocumented() throws Exception {
        String contract = Files.readString(DESIGN.resolve("collaboration-api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-error-codes").size()).as(id).isPositive();
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1] + "`");
            for (var code : operation.get("x-error-codes")) {
                if (code.asString().startsWith("INVALID_") && !code.asString().equals("INVALID_STATE")) {
                    assertThat(contract).as(code.asString()).contains(code.asString());
                }
            }
        });
    }

    @Test void creatingCommandsRequireARequestKeyAndRejectADifferentInputWith409() {
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

    @Test void sendingRequiresAClientMessageKeyAndAReplayReturnsTheOriginalMessage() {
        for (var entry : Map.of("sendChannelMessage", "MessageCreate", "shareTaskCard", "TaskCardCreate").entrySet()) {
            var operation = operation(entry.getKey());
            assertThat(schema(entry.getValue()).get("required").toString()).contains("\"clientMessageKey\"");
            assertThat(schema(entry.getValue()).at("/properties/clientMessageKey/maxLength").asInt()).isEqualTo(100);
            assertThat(parameters(entry.getKey())).doesNotContain("Idempotency-Key");
            for (String status : List.of("200", "201")) {
                assertThat(operation.at("/responses/" + status + "/content/application~1json/schema/$ref").asString())
                        .as("%s %s", entry.getKey(), status).endsWith("/Message");
            }
            assertThat(operation.get("x-error-codes").toString()).contains("\"IDEMPOTENCY_CONFLICT\"");
        }
    }

    @Test void messageHistoryUsesACursorAndHasNoOffsetPage() {
        for (String id : List.of("listChannelMessages", "listMessageReplies")) {
            assertThat(parameters(id)).contains("before", "limit").doesNotContain("page", "pageSize");
            assertThat(operation(id).at("/responses/200/content/application~1json/schema/$ref").asString()).endsWith("/MessageFeed");
        }
        assertThat(schema("MessageFeed").get("properties").has("nextCursor")).isTrue();
        assertThat(schema("MessageFeed").get("properties").has("hasMore")).isTrue();
        assertThat(schema("MessageFeed").get("properties").has("meta")).isFalse();
    }

    @Test void everyEditCarriesAVersionAndAConflictIsDeclared() {
        for (String name : VERSIONED) {
            assertThat(schema(name).get("required").toString()).as(name).contains("\"version\"");
        }
        for (String id : List.of("updateTask", "changeTaskStatus", "addTaskChecklistItem", "updateTaskChecklistItem",
                "deleteTaskChecklistItem", "updateChannel", "replaceChannelMembers", "updateFacility",
                "completeFacilityInspection", "changeInquiryAssignee", "answerInquiry", "changeInquiryStatus",
                "updateNotice", "changeNoticeStatus")) {
            assertThat(operation(id).get("x-error-codes").toString()).as(id).contains("\"VERSION_CONFLICT\"");
        }
        assertThat(parameters("deleteTaskChecklistItem")).contains("version");
    }

    @Test void tasksCannotBeDeletedAndCreateTakesNoActorOrSessionField() {
        assertThat(spec.at("/paths/~1tasks~1{id}").has("delete")).isFalse();
        var create = schema("TaskCreate").get("properties");
        assertThat(create.has("createdBy")).isFalse();
        assertThat(create.has("createdById")).isFalse();
        assertThat(schema("TaskUpdate").get("properties").has("status")).isFalse();
        assertThat(schema("TaskUpdate").get("properties").has("branchId")).isFalse();
    }

    @Test void summariesTakeTheListFiltersWithoutPaging() {
        for (var entry : Map.of("listTasks", "getTaskSummary", "listFacilities", "getFacilitySummary",
                "listInquiries", "getInquirySummary").entrySet()) {
            var expected = new ArrayList<>(parameters(entry.getKey()));
            expected.removeAll(List.of("page", "pageSize", "sort", "direction", "status"));
            assertThat(parameters(entry.getValue())).as(entry.getValue()).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test void aTaskCardDoesNotCopyTheTaskAndAnInaccessibleCardCanBeEmpty() {
        var card = schema("TaskCard").get("properties");
        assertThat(card.has("accessible")).isTrue();
        for (String field : List.of("taskId", "branchId", "title", "status", "priority", "assignee", "dueAt")) {
            assertThat(card.get(field).get("nullable").asBoolean()).as(field).isTrue();
        }
        assertThat(card.has("body")).isFalse();
    }

    @Test void facilityStatusAndWorkStatusStaySeparate() {
        assertThat(schema("FacilityStatus").get("enum").toString()).contains("정상", "점검 필요", "고장", "사용 중지");
        assertThat(schema("FacilityWorkStatus").get("enum").toString()).doesNotContain("정상");
        assertThat(schema("FacilityOpenWork").get("properties").has("assignee")).isTrue();
        assertThat(schema("FacilityListItem").get("properties").has("assignee")).isFalse();
        assertThat(schema("FacilityInspectionComplete").get("required").toString()).contains("\"result\"", "\"completedOn\"");
    }

    @Test void inquiriesExposeOnlyMinimalCustomerData() {
        for (String name : List.of("Inquiry", "InquiryListItem")) {
            assertThat(schema(name).at("/properties/customer/$ref").asString()).endsWith("/CustomerSummary");
        }
        assertThat(schema("InquiryStatusChange").at("/properties/status/enum").toString()).doesNotContain("답변 완료");
    }

    @Test void workspaceEventsCoverCollaborationButNotChatDelivery() {
        var resources = spec.at("/components/schemas/WorkspaceEvent/properties/resource/enum").toString();
        assertThat(resources).contains("\"tasks\"", "\"facilities\"", "\"inquiries\"", "\"notices\"");
        assertThat(resources).doesNotContain("\"messages\"", "\"channels\"");
    }
}
