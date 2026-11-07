package com.openstudy.modules.chat;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #77: a task card shares a task with a channel and never widens who can open it (M7-D07). Every read builds the
 * card for the reader asking, from the task as it is now, so these tests read the same card as different people and at
 * different times, and always look at the raw response of the reader who must learn nothing.
 */
@MySqlIntegrationTest
@TestPropertySource(properties = {"spring.jpa.properties.hibernate.generate_statistics=true"})
class TaskCardIntegrationTests extends ChannelTestSupport {
    @Autowired EntityManagerFactory factory;

    // ---- fixtures -------------------------------------------------------------------------------------------

    /** Created through the task API, so the task has its real history. */
    JsonNode newTask(long creator, long branch, long assignee, String title) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("title", title); body.put("assigneeId", Long.toString(assignee));
        body.put("category", "운영");
        var session = as(creator);
        var response = http(session.client(), "POST", "/api/tasks", session.token(), body, key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        var created = node(response);
        tasks.add(created.get("id").asLong());
        return created;
    }

    long newTaskId(long creator, long branch, long assignee, String title) throws Exception {
        return newTask(creator, branch, assignee, title).get("id").asLong();
    }

    HttpResponse<String> card(long person, long channel, String key, Object task, String text, Object parent) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("clientMessageKey", key);
        if (task != null) body.put("taskId", task.toString());
        if (text != null) body.put("text", text);
        if (parent != null) body.put("parentId", parent.toString());
        return write(person, "POST", "/api/channels/" + channel + "/task-cards", body);
    }

    JsonNode shared(long person, long channel, Object task) throws Exception {
        var response = card(person, channel, key(), task, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    JsonNode feed(long person, long channel) throws Exception { return ok(get(person, "/api/channels/" + channel + "/messages")); }

    JsonNode lastCard(long person, long channel) throws Exception {
        var items = feed(person, channel).get("items");
        return items.get(items.size() - 1).get("task");
    }

    JsonNode taskNow(long person, long id) throws Exception { return ok(get(person, "/api/tasks/" + id)); }

    long taskVersion(long person, long id) throws Exception { return taskNow(person, id).get("version").asLong(); }

    // ---- sharing ---------------------------------------------------------------------------------------------

    @Test void onlyAParticipantMaySharePastAnyInput() throws Exception {
        long channel = channelId(owner, a, "공유 참여", staffA);
        long task = newTaskId(managerA, a, staffA, "참여 확인");
        // The owner created the channel and so took part until removed; managing it is no part of reading it.
        ok(replaceMembers(owner, channel, 1, staffA));
        // Not in the channel: a manager of the branch, the owner, someone from another branch, all the same 404,
        // also with input that would be a 422 for a participant.
        for (long person : List.of(staffA2, managerA, owner, staffB, outsider)) {
            refused(card(person, channel, key(), task, null, null), 404, "NOT_FOUND");
            refused(card(person, channel, "", null, "", "abc"), 404, "NOT_FOUND");
            refused(write(person, "POST", "/api/channels/" + channel + "/task-cards", Map.of("unknown", "field")), 404, "NOT_FOUND");
        }
        refused(card(staffA, 999999999L, key(), task, null, null), 404, "NOT_FOUND");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isZero();
    }

    @Test void aTaskTheSenderCannotOpenOrThatDoesNotExistIs404() throws Exception {
        long channel = channelId(owner, null, "공유 접근", staffA, staffB);
        long taskOfA = newTaskId(managerA, a, staffA, "A 지점 업무");
        // staffB is in the channel but not in branch A, and the answer is the same as for a task that does not exist.
        refused(card(staffB, channel, key(), taskOfA, null, null), 404, "NOT_FOUND");
        refused(card(staffB, channel, key(), 999999999L, null, null), 404, "NOT_FOUND");
        refused(get(staffB, "/api/tasks/" + taskOfA), 404, "NOT_FOUND");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isZero();
        // A reader of the branch may share it.
        assertThat(card(staffA, channel, key(), taskOfA, null, null).statusCode()).isEqualTo(201);
    }

    @Test void aSharedCardIsStoredAsAReferenceOnly() throws Exception {
        long channel = channelId(owner, a, "공유 저장", staffA);
        long task = newTaskId(managerA, a, staffA, "참조만 저장");
        var response = card(staffA, channel, key(), task, "확인 부탁", null);
        assertThat(response.statusCode()).isEqualTo(201);
        var message = node(response);
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/channels/" + channel + "/messages/" + id(message));
        assertThat(message.get("kind").asString()).isEqualTo("업무 카드");
        assertThat(message.get("body").asString()).isEqualTo("확인 부탁");
        assertThat(message.get("sender").get("id").asString()).isEqualTo(Long.toString(staffA));
        assertThat(message.get("parentId").isNull()).isTrue();
        assertThat(message.get("task").get("accessible").asBoolean()).isTrue();
        assertThat(message.get("task").get("taskId").asString()).isEqualTo(Long.toString(task));
        assertThat(message.get("task").get("title").asString()).isEqualTo("참조만 저장");
        assertThat(message.get("task").get("status").asString()).isEqualTo("할 일");
        assertThat(message.get("task").get("priority").asString()).isEqualTo("보통");
        assertThat(message.get("task").get("assignee").get("name").asString()).isEqualTo("다 직원");
        assertThat(message.get("task").get("branchId").asString()).isEqualTo(Long.toString(a));
        assertThat(jdbc.queryForMap("SELECT kind, body FROM messages WHERE id=?", id(message)))
                .containsEntry("kind", "TASK_CARD").containsEntry("body", "확인 부탁");
        assertThat(count("SELECT related_task_id FROM messages WHERE id=?", id(message))).isEqualTo(task);

        // Without text there is no body, and the card still reads.
        var bare = shared(staffA, channel, task);
        assertThat(bare.get("body").isNull()).isTrue();
        assertThat(bare.get("task").get("accessible").asBoolean()).isTrue();
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages/" + id(bare))).get("task").get("title").asString()).isEqualTo("참조만 저장");
    }

    @Test void theCardFollowsTheTaskAndNotTheMomentItWasShared() throws Exception {
        long channel = channelId(owner, a, "카드 최신", staffA, staffA2, managerA);
        long task = newTaskId(managerA, a, staffA, "처음 제목");
        var message = shared(staffA, channel, task);
        assertThat(message.get("task").get("status").asString()).isEqualTo("할 일");
        assertThat(message.get("task").get("dueAt").isNull()).isTrue();

        ok(write(managerA, "PATCH", "/api/tasks/" + task, Map.of("version", taskVersion(managerA, task),
                "title", "바뀐 제목", "assigneeId", Long.toString(staffA2), "dueAt", "2030-01-02T03:04:05Z", "priority", "긴급")));
        ok(write(managerA, "POST", "/api/tasks/" + task + "/status", Map.of("version", taskVersion(managerA, task), "status", "진행 중")));

        for (long reader : List.of(staffA, staffA2, managerA)) {
            var now = lastCard(reader, channel);
            assertThat(now.get("accessible").asBoolean()).isTrue();
            assertThat(now.get("title").asString()).isEqualTo("바뀐 제목");
            assertThat(now.get("status").asString()).isEqualTo("진행 중");
            assertThat(now.get("priority").asString()).isEqualTo("긴급");
            assertThat(now.get("assignee").get("id").asString()).isEqualTo(Long.toString(staffA2));
            assertThat(now.get("assignee").get("name").asString()).isEqualTo("라 직원");
            assertThat(now.get("dueAt").asString()).startsWith("2030-01-02T03:04:05");
        }
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages/" + id(message))).get("task").get("status").asString()).isEqualTo("진행 중");
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id=?", String.class, id(message))).isNull();
    }

    // ---- access ----------------------------------------------------------------------------------------------

    @Test void aParticipantOfAnotherBranchSeesAnInaccessibleCardAndLearnsNothing() throws Exception {
        long channel = channelId(owner, null, "전체 카드", staffA, staffB, dual);
        long task = newTaskId(managerA, a, staffA2, "A 지점 비공개 제목");
        long original = sent(staffA, channel, "원문");
        var top = shared(staffA, channel, task);
        var inReply = card(staffA, channel, key(), task, null, original);
        assertThat(inReply.statusCode()).isEqualTo(201);

        // Every way a message can be read by the participant of branch B.
        String base = "/api/channels/" + channel;
        var responses = List.of(get(staffB, base + "/messages"), get(staffB, base + "/messages/" + id(top)),
                get(staffB, base + "/messages/" + original + "/replies"), get(staffB, base + "/messages/" + id(node(inReply))));
        for (var response : responses) {
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).doesNotContain("A 지점 비공개 제목").doesNotContain("라 직원")
                    .doesNotContain("\"taskId\":\"" + task + "\"").doesNotContain("\"branchId\":\"");
        }
        var cardOfB = node(responses.get(1)).get("task");
        assertThat(cardOfB.get("accessible").asBoolean()).isFalse();
        for (String field : List.of("taskId", "branchId", "title", "status", "priority", "assignee", "dueAt"))
            assertThat(cardOfB.get(field).isNull()).as(field).isTrue();
        assertThat(cardOfB.size()).isEqualTo(8);
        assertThat(node(responses.get(0)).get("items").get(1).get("task").get("accessible").asBoolean()).isFalse();
        assertThat(node(responses.get(2)).get("items").get(0).get("task").get("accessible").asBoolean()).isFalse();
        assertThat(node(responses.get(3)).get("task").get("accessible").asBoolean()).isFalse();

        // The same participant gets nothing from the task API either, so the card opened nothing.
        refused(get(staffB, "/api/tasks/" + task), 404, "NOT_FOUND");
        refused(get(staffB, "/api/tasks/" + task + "/events"), 404, "NOT_FOUND");

        // The sender, a member of branch A, and one who belongs to both branches still see it.
        assertThat(lastCard(staffA, channel).get("accessible").asBoolean()).isTrue();
        assertThat(lastCard(dual, channel).get("title").asString()).isEqualTo("A 지점 비공개 제목");
    }

    @Test void theHistoryOpenedFromACardIsTheHistoryOfTheTaskScreen() throws Exception {
        long channel = channelId(owner, a, "카드 이력", staffA, managerA);
        long task = newTaskId(managerA, a, staffA, "이력 비교");
        ok(write(staffA, "POST", "/api/tasks/" + task + "/status", Map.of("version", taskVersion(staffA, task), "status", "진행 중")));
        ok(write(managerA, "PATCH", "/api/tasks/" + task, Map.of("version", taskVersion(managerA, task), "title", "이력 비교 수정")));
        shared(managerA, channel, task);

        long fromCard = lastCard(staffA, channel).get("taskId").asLong();
        assertThat(fromCard).isEqualTo(task);
        // The task screen finds the task in its own list; a card is only another way to learn the ID.
        String listed = null;
        for (var item : ok(get(staffA, "/api/tasks?q=" + URLEncoder.encode("이력 비교", StandardCharsets.UTF_8))).get("items"))
            listed = item.get("id").asString();
        assertThat(listed).isEqualTo(Long.toString(task));
        var viaCard = ok(get(staffA, "/api/tasks/" + fromCard + "/events"));
        var viaScreen = ok(get(staffA, "/api/tasks/" + listed + "/events"));
        assertThat(viaCard).isEqualTo(viaScreen);
        assertThat(viaCard.get("items").size()).isEqualTo(3);
        assertThat(viaCard.at("/meta/total").asLong()).isEqualTo(count("SELECT COUNT(*) FROM task_events WHERE task_id=?", task));
        assertThat(ok(get(managerA, "/api/tasks/" + task + "/events"))).isEqualTo(viaCard);
        assertThat(taskNow(staffA, fromCard).get("title").asString()).isEqualTo(lastCard(staffA, channel).get("title").asString());
    }

    @Test void aCardTurnsInaccessibleWhenTheReadersMembershipIsRevoked() throws Exception {
        long channel = channelId(owner, null, "회수 카드", staffA, dual);
        long task = newTaskId(managerA, a, staffA, "회수 대상 업무");
        shared(dual, channel, task);
        assertThat(lastCard(dual, channel).get("accessible").asBoolean()).isTrue();
        assertThat(taskNow(dual, task).get("title").asString()).isEqualTo("회수 대상 업무");

        ok(revoke(dual, a));

        // dual still belongs to branch B and so to the channel for everyone, but not to the task's branch.
        var response = get(dual, "/api/channels/" + channel + "/messages");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("회수 대상 업무");
        var after = node(response).get("items").get(0).get("task");
        assertThat(after.get("accessible").asBoolean()).isFalse();
        assertThat(after.get("taskId").isNull()).isTrue();
        refused(get(dual, "/api/tasks/" + task), 404, "NOT_FOUND");
        refused(card(dual, channel, key(), task, null, null), 404, "NOT_FOUND");
        // Branch A's own members are unaffected.
        assertThat(lastCard(staffA, channel).get("accessible").asBoolean()).isTrue();
    }

    @Test void aReaderRemovedFromTheChannelSeesNoCardAtAll() throws Exception {
        long channel = channelId(owner, a, "제거 카드", staffA, staffA2);
        long task = newTaskId(managerA, a, staffA, "제거 후 카드");
        var message = shared(staffA, channel, task);
        ok(replaceMembers(owner, channel, 1, staffA));
        refused(get(staffA2, "/api/channels/" + channel + "/messages"), 404, "NOT_FOUND");
        refused(get(staffA2, "/api/channels/" + channel + "/messages/" + id(message)), 404, "NOT_FOUND");
    }

    // ---- keys ------------------------------------------------------------------------------------------------

    @Test void aRepeatedKeyOfACardReturnsTheOriginalAndADifferentInputConflicts() throws Exception {
        long channel = channelId(owner, a, "카드 재전송", staffA);
        long parent = sent(owner, channel, "원문");
        long task = newTaskId(managerA, a, staffA, "재전송 업무");
        long other = newTaskId(managerA, a, staffA, "다른 업무");
        String key = key();
        var first = card(staffA, channel, key, task, "본문", null);
        assertThat(first.statusCode()).isEqualTo(201);
        var again = card(staffA, channel, key, task, "본문", null);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(node(again)).isEqualTo(node(first));

        refused(card(staffA, channel, key, other, "본문", null), 409, "IDEMPOTENCY_CONFLICT");
        refused(card(staffA, channel, key, task, "다른 본문", null), 409, "IDEMPOTENCY_CONFLICT");
        refused(card(staffA, channel, key, task, null, null), 409, "IDEMPOTENCY_CONFLICT");
        refused(card(staffA, channel, key, task, "본문", parent), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND client_message_key=?", channel, key)).isEqualTo(1);

        String bareKey = key();
        assertThat(card(staffA, channel, bareKey, task, null, parent).statusCode()).isEqualTo(201);
        assertThat(card(staffA, channel, bareKey, task, null, parent).statusCode()).isEqualTo(200);
        refused(card(staffA, channel, bareKey, task, null, null), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test void aTextAndACardNeverReplayEachOther() throws Exception {
        long channel = channelId(owner, a, "텍스트 카드 키", staffA);
        long task = newTaskId(managerA, a, staffA, "키 충돌 업무");
        String textKey = key(), cardKey = key();
        assertThat(send(staffA, channel, textKey, "본문", null).statusCode()).isEqualTo(201);
        assertThat(card(staffA, channel, cardKey, task, "본문", null).statusCode()).isEqualTo(201);
        refused(card(staffA, channel, textKey, task, "본문", null), 409, "IDEMPOTENCY_CONFLICT");
        refused(send(staffA, channel, cardKey, "본문", null), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(2);
    }

    @Test void aRepeatIsAnsweredFromTheTaskAsItIsAtTheRepeat() throws Exception {
        long channel = channelId(owner, a, "재전송 시점", staffA);
        long task = newTaskId(managerA, a, staffA, "재전송 시점 업무");
        String key = key();
        var first = card(staffA, channel, key, task, null, null);
        assertThat(node(first).get("task").get("status").asString()).isEqualTo("할 일");

        ok(write(managerA, "POST", "/api/tasks/" + task + "/status", Map.of("version", taskVersion(managerA, task), "status", "완료")));
        var again = card(staffA, channel, key, task, null, null);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(node(again).get("id")).isEqualTo(node(first).get("id"));
        assertThat(node(again).get("task").get("status").asString()).isEqualTo("완료");
        assertThat(again.body()).doesNotContain("할 일");
    }

    @Test void aRepeatAfterTheSenderLostTheTaskIs404AndStoresNothingNew() throws Exception {
        long channel = channelId(owner, null, "재전송 회수", staffA, dual);
        long task = newTaskId(managerA, a, staffA, "재전송 회수 업무");
        String key = key();
        assertThat(card(dual, channel, key, task, null, null).statusCode()).isEqualTo(201);
        ok(revoke(dual, a));
        refused(card(dual, channel, key, task, null, null), 404, "NOT_FOUND");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
    }

    // ---- threads and input -----------------------------------------------------------------------------------

    @Test void aCardCanReplyToAnOriginalOfItsChannelOnly() throws Exception {
        long channel = channelId(owner, a, "카드 스레드", staffA);
        long other = channelId(owner, a, "다른 채널", staffA);
        long task = newTaskId(managerA, a, staffA, "스레드 업무");
        long original = sent(owner, channel, "원문");
        long reply = reply(owner, channel, "답글", original);
        long foreign = sent(owner, other, "다른 채널 원문");

        var attached = card(staffA, channel, key(), task, "카드 답글", original);
        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        assertThat(node(attached).get("parentId").asString()).isEqualTo(Long.toString(original));
        assertThat(node(attached).get("task").get("accessible").asBoolean()).isTrue();
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages/" + original)).get("replyCount").asLong()).isEqualTo(2);
        var replies = ok(get(staffA, "/api/channels/" + channel + "/messages/" + original + "/replies")).get("items");
        assertThat(replies.size()).isEqualTo(2);
        assertThat(replies.get(1).get("kind").asString()).isEqualTo("업무 카드");
        assertThat(replies.get(1).get("task").get("title").asString()).isEqualTo("스레드 업무");
        // Replies are not in the original feed.
        assertThat(feed(staffA, channel).get("items").size()).isEqualTo(1);

        refused(card(staffA, channel, key(), task, null, reply), 422, "INVALID_PARENT_MESSAGE");
        refused(card(staffA, channel, key(), task, null, foreign), 404, "NOT_FOUND");
        refused(card(staffA, channel, key(), task, null, 999999999L), 404, "NOT_FOUND");
    }

    @Test void inputIsValidatedOnceTheSenderParticipates() throws Exception {
        long channel = channelId(owner, a, "카드 입력", staffA);
        long task = newTaskId(managerA, a, staffA, "입력 업무");
        String path = "/api/channels/" + channel + "/task-cards";
        refused(card(staffA, channel, key(), null, null, null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, key(), "abc", null, null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, key(), "0", null, null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, "", task, null, null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, "k".repeat(101), task, null, null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, key(), task, "   ", null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, key(), task, "가".repeat(4001), null), 422, "VALIDATION_FAILED");
        refused(card(staffA, channel, key(), task, null, "abc"), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", path, Map.of("clientMessageKey", key(), "taskId", Long.toString(task), "extra", "x")), 422, "VALIDATION_FAILED");
        var nullText = new HashMap<String, Object>();
        nullText.put("clientMessageKey", key()); nullText.put("taskId", Long.toString(task)); nullText.put("text", null);
        refused(write(staffA, "POST", path, nullText), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", path, Map.of("taskId", Long.toString(task))), 422, "VALIDATION_FAILED");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isZero();
        assertThat(card(staffA, channel, key(), task, "가".repeat(4000), null).statusCode()).isEqualTo(201);
    }

    @Test void aTextMessageStillReadsWithoutATask() throws Exception {
        long channel = channelId(owner, a, "텍스트 회귀", staffA);
        long task = newTaskId(managerA, a, staffA, "섞인 업무");
        var text = send(staffA, channel, key(), "그냥 글", null);
        assertThat(text.statusCode()).isEqualTo(201);
        assertThat(node(text).get("task").isNull()).isTrue();
        assertThat(node(text).get("kind").asString()).isEqualTo("텍스트");
        shared(staffA, channel, task);
        var items = feed(staffA, channel).get("items");
        assertThat(items.get(0).get("task").isNull()).isTrue();
        assertThat(items.get(0).get("body").asString()).isEqualTo("그냥 글");
        assertThat(items.get(1).get("task").get("accessible").asBoolean()).isTrue();
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages/" + id(node(text)))).get("task").isNull()).isTrue();
    }

    // ---- queries ---------------------------------------------------------------------------------------------

    /** A channel for everyone with cards of tasks of branch A, rows written directly so that the setup stays quick. */
    private long cardChannel(String name, int cards) throws Exception {
        long channel = channelId(owner, null, name, staffA, staffA2, staffB);
        for (int index = 0; index < cards; index++) {
            jdbc.update("INSERT INTO work_tasks(branch_id,title,created_by,assignee_employee_id,category) VALUES(?,?,?,?,'일반')",
                    a, name + " 업무 " + index, staffA, index % 2 == 0 ? staffA : staffA2);
            long task = count("SELECT id FROM work_tasks WHERE branch_id=? AND title=?", a, name + " 업무 " + index);
            tasks.add(task);
            jdbc.update("INSERT INTO messages(conversation_id,sender_employee_id,related_task_id,kind,client_message_key) VALUES(?,?,?,'TASK_CARD',?)",
                    channel, staffA, task, "bulk-" + index);
        }
        return channel;
    }

    /** Statements Hibernate prepared for one request, after a first request has warmed the session and the caches. */
    private long statementsFor(long person, String path) throws Exception {
        var statistics = factory.unwrap(SessionFactory.class).getStatistics();
        get(person, path);
        statistics.clear();
        var response = get(person, path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return statistics.getPrepareStatementCount();
    }

    @Test void theNumberOfQueriesDoesNotGrowWithTheNumberOfCards() throws Exception {
        long few = cardChannel("카드 적음", 2), many = cardChannel("카드 많음", 40);
        long small = statementsFor(staffA2, "/api/channels/" + few + "/messages");
        long large = statementsFor(staffA2, "/api/channels/" + many + "/messages");
        assertThat(large).as("statements for 40 cards against 2").isEqualTo(small);
        assertThat(feed(staffA2, many).get("items").size()).isEqualTo(40);
        // A reader who cannot open the tasks costs no more than one who can, and learns nothing from any of the 40.
        long blind = statementsFor(staffB, "/api/channels/" + many + "/messages");
        assertThat(blind).isLessThanOrEqualTo(large);
        for (var item : feed(staffB, many).get("items")) assertThat(item.get("task").get("accessible").asBoolean()).isFalse();
    }

    // ---- races -----------------------------------------------------------------------------------------------

    @Test void concurrentSharesWithOneKeyStoreOneCard() throws Exception {
        long channel = channelId(owner, a, "동시 카드", staffA);
        long task = newTaskId(managerA, a, staffA, "동시 업무");
        String key = key();
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 6; index++) {
                var session = login(staffA);
                open.add(session);
                var body = new HashMap<String, Object>();
                body.put("clientMessageKey", key); body.put("taskId", Long.toString(task));
                calls.add(() -> http(session.client(), "POST", "/api/channels/" + channel + "/task-cards", session.token(), body, null));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsExactly(200, 200, 200, 200, 200, 201);
            assertThat(results.stream().map(result -> node(result).get("id").asString()).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
        } finally {
            open.forEach(Session::close);
        }
    }

    @Test void aShareRacingARevocationEitherHappenedBeforeItOrNotAtAll() throws Exception {
        long channel = channelId(owner, null, "카드 회수 경합", staffA, dual);
        long task = newTaskId(managerA, a, staffA, "경합 업무");
        as(dual); as(owner);
        var results = race(List.<Callable<HttpResponse<String>>>of(() -> card(dual, channel, key(), task, null, null), () -> revoke(dual, a)));
        long stored = count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel);
        int share = results.get(0).statusCode();
        assertThat(results.get(1).statusCode()).isEqualTo(200);
        assertThat(share).isIn(201, 404);
        assertThat(stored).isEqualTo(share == 201 ? 1 : 0);
        // Whatever the order, the card is closed to dual afterwards.
        if (stored == 1) assertThat(lastCard(dual, channel).get("accessible").asBoolean()).isFalse();
    }
}
