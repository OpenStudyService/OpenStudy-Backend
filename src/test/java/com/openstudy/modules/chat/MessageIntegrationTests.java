package com.openstudy.modules.chat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #76: messages, threads, cursors and the read position (collaboration-api.md). Access is checked on every path for
 * every kind of outsider; the races run real concurrent requests against MySQL.
 */
@MySqlIntegrationTest
@TestPropertySource(properties = "openstudy.workspace.scheduling.enabled=false")
class MessageIntegrationTests extends ChannelTestSupport {

    // ---- access ---------------------------------------------------------------------------------------------

    /** Every message path, also with input that would be a 422 for a participant. */
    private void shutOut(long person, long channel, long message, long reply) throws Exception {
        String base = "/api/channels/" + channel;
        for (String path : List.of(base + "/messages", base + "/messages?before=abc", base + "/messages?limit=0",
                base + "/messages/" + message, base + "/messages/" + reply, base + "/messages/" + message + "/replies",
                base + "/messages/" + message + "/replies?before=abc", base + "/messages/999999999"))
            refused(get(person, path), 404, "NOT_FOUND");
        refused(send(person, channel, key(), "비참여자 전송", null), 404, "NOT_FOUND");
        refused(send(person, channel, key(), "비참여자 답글", message), 404, "NOT_FOUND");
        refused(send(person, channel, key(), "   ", null), 404, "NOT_FOUND");
        refused(read(person, channel, message), 404, "NOT_FOUND");
        refused(write(person, "POST", base + "/read", Map.of("wrong", "field")), 404, "NOT_FOUND");
        refused(write(person, "POST", base + "/messages/" + message + "/read", Map.of("replyId", Long.toString(reply))), 404, "NOT_FOUND");
    }

    @Test void aNonParticipantGets404OnEveryMessagePathEvenAsAdministrator() throws Exception {
        long channel = channelId(owner, a, "차단", staffA);
        long message = sent(staffA, channel, "비밀 원문");
        long reply = reply(staffA, channel, "비밀 답글", message);
        for (long person : List.of(staffA2, staffB, managerB, dual, managerA)) shutOut(person, channel, message, reply);
        // An OWNER who manages everything is no exception once out of the channel.
        ok(replaceMembers(owner, channel, 1, staffA));
        shutOut(owner, channel, message, reply);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=? AND last_read_message_id IS NOT NULL", channel)).isZero();
        // The same answer as for a channel that does not exist: the status tells nothing.
        refused(get(staffB, "/api/channels/999999999/messages"), 404, "NOT_FOUND");
    }

    @Test void aChannelForEveryoneGuardsItsMessagesTheSameWay() throws Exception {
        long channel = channelId(owner, null, "전체 차단", staffA);
        long message = sent(staffA, channel, "전체 원문");
        long reply = reply(staffA, channel, "전체 답글", message);
        for (long person : List.of(staffA2, managerA, staffB)) shutOut(person, channel, message, reply);
    }

    @Test void aDmIs404OnEveryApiEvenToItsOwnParticipants() throws Exception {
        long low = Math.min(staffA, staffA2), high = Math.max(staffA, staffA2);
        jdbc.update("INSERT INTO conversations(kind,dm_employee_low_id,dm_employee_high_id,created_by) VALUES('DM',?,?,?)", low, high, staffA);
        long dm = count("SELECT id FROM conversations WHERE dm_employee_low_id=? AND dm_employee_high_id=?", low, high);
        for (long person : List.of(staffA, staffA2)) jdbc.update("INSERT INTO conversation_members(conversation_id,employee_id) VALUES(?,?)", dm, person);
        jdbc.update("INSERT INTO messages(conversation_id,sender_employee_id,body,client_message_key) VALUES(?,?,'DM 본문','dm-key')", dm, staffA);
        long message = count("SELECT id FROM messages WHERE conversation_id=?", dm);
        for (long person : List.of(staffA, owner)) {
            String base = "/api/channels/" + dm;
            refused(get(person, base), 404, "NOT_FOUND");
            refused(get(person, base + "/members"), 404, "NOT_FOUND");
            refused(write(person, "PATCH", base, Map.of("version", 1, "name", "변경")), 404, "NOT_FOUND");
            refused(replaceMembers(person, dm, 1, staffA), 404, "NOT_FOUND");
            refused(get(person, base + "/messages"), 404, "NOT_FOUND");
            refused(get(person, base + "/messages/" + message), 404, "NOT_FOUND");
            refused(get(person, base + "/messages/" + message + "/replies"), 404, "NOT_FOUND");
            refused(send(person, dm, key(), "DM 전송", null), 404, "NOT_FOUND");
            refused(read(person, dm, message), 404, "NOT_FOUND");
            assertThat(get(person, "/api/channels").body()).doesNotContain("\"id\":\"" + dm + "\"");
        }
        assertThat(get(owner, "/api/channels/manageable?pageSize=100").body()).doesNotContain("\"id\":\"" + dm + "\"");
        // Ending a participation leaves the DM alone: M14 decides what happens to it.
        ok(revoke(staffA, a));
        assertThat(leftAtIsSet(dm, staffA)).isZero();
    }

    @Test void aMessageIsFoundOnlyThroughItsOwnChannel() throws Exception {
        long mine = channelId(owner, a, "내 채널", staffA);
        long other = channelId(owner, a, "다른 채널", staffA2);
        long foreign = sent(staffA2, other, "다른 채널의 원문");
        refused(get(staffA, "/api/channels/" + mine + "/messages/" + foreign), 404, "NOT_FOUND");
        refused(get(staffA, "/api/channels/" + mine + "/messages/" + foreign + "/replies"), 404, "NOT_FOUND");
        refused(read(staffA, mine, foreign), 404, "NOT_FOUND");
        refused(send(staffA, mine, key(), "다른 채널 답글", foreign), 404, "NOT_FOUND");
        // The owner joined both, and the rule still follows the path.
        refused(get(owner, "/api/channels/" + mine + "/messages/" + foreign), 404, "NOT_FOUND");
    }

    // ---- body -----------------------------------------------------------------------------------------------

    @Test void emptyTextIsRefusedAndTheLimitsCountCharacters() throws Exception {
        long channel = channelId(owner, a, "본문 규칙", staffA);
        for (String text : List.of("", " ", "   \n\t  ", "　")) refused(send(staffA, channel, key(), text, null), 422, "VALIDATION_FAILED");
        refused(send(staffA, channel, key(), "가".repeat(4001), null), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", "/api/channels/" + channel + "/messages", Map.of("clientMessageKey", key())), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", "/api/channels/" + channel + "/messages", Map.of("clientMessageKey", key(), "text", 5)), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", "/api/channels/" + channel + "/messages", Map.of("text", "키 없음")), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", "/api/channels/" + channel + "/messages", Map.of("clientMessageKey", key(), "text", "모르는 필드", "task", "1")), 422, "VALIDATION_FAILED");
        refused(send(staffA, channel, "k".repeat(101), "긴 키", null), 422, "VALIDATION_FAILED");
        refused(send(staffA, channel, "   ", "빈 키", null), 422, "VALIDATION_FAILED");
        // JDBC would turn these into "?" and the retry of the same input would then conflict with itself.
        String surrogate = "\\ud83d";
        refused(writeRaw(staffA, "/api/channels/" + channel + "/messages", "{\"clientMessageKey\":\"" + key() + "\",\"text\":\"짝 없음 " + surrogate + "\"}"), 422, "VALIDATION_FAILED");
        refused(writeRaw(staffA, "/api/channels/" + channel + "/messages", "{\"clientMessageKey\":\"" + surrogate + "\",\"text\":\"본문\"}"), 422, "VALIDATION_FAILED");
        refused(send(staffA, channel, key(), "잘못된 답글", "abc"), 422, "VALIDATION_FAILED");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isZero();

        assertThat(send(staffA, channel, key(), "가".repeat(4000), null).statusCode()).isEqualTo(201);
        assertThat(send(staffA, channel, "k".repeat(100), "😀".repeat(4000), null).statusCode()).isEqualTo(201);
    }

    @Test void theBodyIsStoredAndReturnedExactlyAsSent() throws Exception {
        long channel = channelId(owner, a, "그대로", staffA);
        String text = "  <script>alert('x')</script> &amp; <b onclick=\"a()\">굵게</b>\n\t\"따옴표\" 😀  ";
        var response = send(staffA, channel, key(), text, null);
        assertThat(response.statusCode()).isEqualTo(201);
        var sent = node(response);
        assertThat(sent.get("body").asString()).isEqualTo(text);
        assertThat(sent.get("kind").asString()).isEqualTo("텍스트");
        assertThat(sent.get("task").isNull()).isTrue();
        assertThat(sent.get("parentId").isNull()).isTrue();
        assertThat(sent.get("replyCount").asLong()).isZero();
        assertThat(sent.get("sender").get("name").asString()).isEqualTo("다 직원");
        assertThat(sent.get("channelId").asString()).isEqualTo(Long.toString(channel));
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id=?", String.class, id(sent))).isEqualTo(text);
        var read = ok(get(owner, "/api/channels/" + channel + "/messages/" + id(sent)));
        assertThat(read.get("body").asString()).isEqualTo(text);
        assertThat(read.get("sentAt").asString()).isEqualTo(sent.get("sentAt").asString());
    }

    @Test void aChannelOfAStoppedBranchKeepsWorking() throws Exception {
        long channel = channelId(owner, a, "운영 중지", staffA);
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", a);
        sent(staffA, channel, "운영 중지여도 협업");
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages")).get("items").size()).isEqualTo(1);
    }

    // ---- client message key ---------------------------------------------------------------------------------

    @Test void aRepeatedKeyReturnsTheOriginalAndADifferentInputConflicts() throws Exception {
        long channel = channelId(owner, a, "재전송", staffA);
        long parent = sent(owner, channel, "원문");
        String key = key();
        var first = send(staffA, channel, key, "한 번만", null);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue("Location").orElseThrow()).isEqualTo("/api/channels/" + channel + "/messages/" + id(node(first)));

        var again = send(staffA, channel, key, "한 번만", null);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(node(again)).isEqualTo(node(first));
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND client_message_key=?", channel, key)).isEqualTo(1);

        refused(send(staffA, channel, key, "다른 본문", null), 409, "IDEMPOTENCY_CONFLICT");
        refused(send(staffA, channel, key, "한 번만", parent), 409, "IDEMPOTENCY_CONFLICT");
        refused(send(staffA, channel, key, "한 번만 ", null), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=? AND client_message_key=?", channel, key)).isEqualTo(1);

        String replyKey = key();
        var reply = send(staffA, channel, replyKey, "답글 한 번", parent);
        assertThat(reply.statusCode()).isEqualTo(201);
        var replyAgain = send(staffA, channel, replyKey, "답글 한 번", parent);
        assertThat(replyAgain.statusCode()).isEqualTo(200);
        assertThat(id(node(replyAgain))).isEqualTo(id(node(reply)));
        refused(send(staffA, channel, replyKey, "답글 한 번", null), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test void aKeyIsScopedToTheChannelAndTheSender() throws Exception {
        long one = channelId(owner, a, "키 범위 1", staffA, staffA2);
        long two = channelId(owner, a, "키 범위 2", staffA);
        String key = key();
        assertThat(send(staffA, one, key, "본문", null).statusCode()).isEqualTo(201);
        assertThat(send(staffA2, one, key, "본문", null).statusCode()).isEqualTo(201);
        assertThat(send(staffA, two, key, "본문", null).statusCode()).isEqualTo(201);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE client_message_key=?", key)).isEqualTo(3);
    }

    @Test void aRetryAfterRemovalIs404NotTheOriginal() throws Exception {
        long channel = channelId(owner, a, "제거 후 재전송", staffA);
        String key = key();
        assertThat(send(staffA, channel, key, "본문", null).statusCode()).isEqualTo(201);
        ok(replaceMembers(owner, channel, 1, owner));
        refused(send(staffA, channel, key, "본문", null), 404, "NOT_FOUND");
    }

    // ---- threads --------------------------------------------------------------------------------------------

    @Test void aReplyAttachesToAnOriginalOfTheSameChannelOnly() throws Exception {
        long channel = channelId(owner, a, "스레드", staffA);
        long original = sent(owner, channel, "원문");
        long reply = reply(staffA, channel, "답글", original);
        var replyView = ok(get(staffA, "/api/channels/" + channel + "/messages/" + reply));
        assertThat(replyView.get("parentId").asString()).isEqualTo(Long.toString(original));
        refused(send(staffA, channel, key(), "답글의 답글", reply), 422, "INVALID_PARENT_MESSAGE");
        refused(send(staffA, channel, key(), "없는 원문", 999_999_999L), 404, "NOT_FOUND");
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages/" + original)).get("replyCount").asLong()).isEqualTo(1);

        var originals = ok(get(staffA, "/api/channels/" + channel + "/messages"));
        assertThat(items(originals)).containsExactly(original);
        assertThat(originals.get("items").get(0).get("replyCount").asLong()).isEqualTo(1);
        assertThat(items(ok(get(staffA, "/api/channels/" + channel + "/messages/" + original + "/replies")))).containsExactly(reply);
        // A reply has no replies of its own, and asking is not an error.
        assertThat(items(ok(get(staffA, "/api/channels/" + channel + "/messages/" + reply + "/replies")))).isEmpty();
    }

    // ---- cursor ---------------------------------------------------------------------------------------------

    private JsonNode page(long person, String path, Object before, int limit) throws Exception {
        return ok(get(person, path + "?limit=" + limit + (before == null ? "" : "&before=" + before)));
    }

    /** Walks a feed to its end and returns the IDs newest page first, in each page's own order. */
    private List<Long> walk(long person, String path, int limit) throws Exception {
        var seen = new ArrayList<Long>();
        Object cursor = null;
        for (int guard = 0; guard < 100; guard++) {
            var page = page(person, path, cursor, limit);
            seen.addAll(0, items(page));
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.get("nextCursor").isNull()).isTrue();
                return seen;
            }
            assertThat(page.get("nextCursor").asString()).isEqualTo(page.get("items").get(0).get("id").asString());
            cursor = page.get("nextCursor").asString();
        }
        throw new AssertionError("Feed did not end");
    }

    @Test void pagesAreOldestFirstAndMeetWithoutGapsOrRepeats() throws Exception {
        long channel = channelId(owner, a, "커서", staffA);
        String path = "/api/channels/" + channel + "/messages";
        var all = new ArrayList<Long>();
        for (int index = 0; index < 7; index++) {
            all.add(sent(staffA, channel, "메시지 " + index));
            reply(staffA, channel, "끼어든 답글 " + index, all.getLast());
        }
        var first = page(staffA, path, null, 3);
        assertThat(items(first)).containsExactlyElementsOf(all.subList(4, 7));
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        var second = page(staffA, path, first.get("nextCursor").asString(), 3);
        assertThat(items(second)).containsExactlyElementsOf(all.subList(1, 4));
        var third = page(staffA, path, second.get("nextCursor").asString(), 3);
        assertThat(items(third)).containsExactly(all.getFirst());
        assertThat(third.get("hasMore").asBoolean()).isFalse();
        assertThat(third.get("nextCursor").isNull()).isTrue();
        assertThat(walk(staffA, path, 2)).containsExactlyElementsOf(all);
        assertThat(walk(staffA, path, 1)).containsExactlyElementsOf(all);
    }

    @Test void aPageThatEndsExactlyAtTheLastMessageHasNoMore() throws Exception {
        long channel = channelId(owner, a, "경계", staffA);
        String path = "/api/channels/" + channel + "/messages";
        var all = new ArrayList<Long>();
        for (int index = 0; index < 4; index++) all.add(sent(staffA, channel, "메시지 " + index));
        var first = page(staffA, path, null, 2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        var second = page(staffA, path, first.get("nextCursor").asString(), 2);
        assertThat(items(second)).containsExactlyElementsOf(all.subList(0, 2));
        assertThat(second.get("hasMore").asBoolean()).isFalse();
        assertThat(second.get("nextCursor").isNull()).isTrue();

        var everything = page(staffA, path, null, 4);
        assertThat(everything.get("hasMore").asBoolean()).isFalse();
        // The oldest message's ID as a cursor is a valid request with nothing before it.
        var nothing = page(staffA, path, all.getFirst(), 2);
        assertThat(items(nothing)).isEmpty();
        assertThat(nothing.get("hasMore").asBoolean()).isFalse();
        assertThat(nothing.get("nextCursor").isNull()).isTrue();

        long empty = channelId(owner, a, "빈 채널", staffA);
        var none = ok(get(staffA, "/api/channels/" + empty + "/messages"));
        assertThat(items(none)).isEmpty();
        assertThat(none.get("hasMore").asBoolean()).isFalse();
    }

    @Test void messagesArrivingBetweenPagesNeitherRepeatNorGoMissing() throws Exception {
        long channel = channelId(owner, a, "도착", staffA);
        String path = "/api/channels/" + channel + "/messages";
        var all = new ArrayList<Long>();
        for (int index = 0; index < 6; index++) all.add(sent(staffA, channel, "메시지 " + index));
        var first = page(staffA, path, null, 2);
        for (int index = 0; index < 3; index++) sent(owner, channel, "새 메시지 " + index);
        var second = page(staffA, path, first.get("nextCursor").asString(), 2);
        for (int index = 0; index < 3; index++) sent(owner, channel, "더 새 메시지 " + index);
        var third = page(staffA, path, second.get("nextCursor").asString(), 2);
        var seen = new ArrayList<Long>(items(third));
        seen.addAll(items(second));
        seen.addAll(items(first));
        assertThat(seen).containsExactlyElementsOf(all);
        assertThat(third.get("hasMore").asBoolean()).isFalse();
    }

    @Test void repliesPageByTheSameRules() throws Exception {
        long channel = channelId(owner, a, "답글 커서", staffA);
        long original = sent(owner, channel, "원문");
        long elsewhere = sent(owner, channel, "다른 원문");
        var replies = new ArrayList<Long>();
        for (int index = 0; index < 5; index++) {
            replies.add(reply(staffA, channel, "답글 " + index, original));
            reply(staffA, channel, "다른 스레드 " + index, elsewhere);
        }
        String path = "/api/channels/" + channel + "/messages/" + original + "/replies";
        assertThat(walk(staffA, path, 2)).containsExactlyElementsOf(replies);
        var first = page(staffA, path, null, 2);
        sent(owner, channel, "새 원문");
        reply(staffA, channel, "새 답글", original);
        assertThat(items(page(staffA, path, first.get("nextCursor").asString(), 2))).containsExactlyElementsOf(replies.subList(1, 3));
    }

    @Test void aMalformedCursorOrLimitIs422AndAForeignCursorIsOnlyABoundary() throws Exception {
        long channel = channelId(owner, a, "입력", staffA);
        long other = channelId(owner, a, "남의 채널", staffA2);
        long oldest = sent(staffA, channel, "오래된 메시지");
        long foreign = sent(staffA2, other, "남의 메시지");
        long newest = sent(staffA, channel, "새 메시지");
        String path = "/api/channels/" + channel + "/messages";
        for (String query : List.of("?before=abc", "?before=0", "?before=-1", "?before=", "?before=01", "?before=1.5",
                "?before=99999999999999999999", "?limit=0", "?limit=101", "?limit=-1", "?limit=abc", "?limit="))
            refused(get(staffA, path + query), 422, "VALIDATION_FAILED");
        refused(get(staffA, path + "/" + oldest + "/replies?before=abc"), 422, "VALIDATION_FAILED");
        var bounded = ok(get(staffA, path + "?before=" + foreign));
        assertThat(items(bounded)).containsExactly(oldest);
        assertThat(bounded.toString()).doesNotContain("남의 메시지");
        assertThat(items(ok(get(staffA, path + "?limit=100")))).containsExactly(oldest, newest);
    }

    @Test void theDefaultLimitIsFifty() throws Exception {
        long channel = channelId(owner, a, "기본값", staffA);
        for (int index = 0; index < 51; index++) jdbc.update(
                "INSERT INTO messages(conversation_id,sender_employee_id,body,client_message_key) VALUES(?,?,?,?)", channel, staffA, "본문", key());
        var page = ok(get(staffA, "/api/channels/" + channel + "/messages"));
        assertThat(page.get("items").size()).isEqualTo(50);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
    }

    // ---- read position --------------------------------------------------------------------------------------

    @Test void theReadPositionOnlyMovesForwardAndOnlyForTheRequester() throws Exception {
        long channel = channelId(owner, a, "읽음", staffA, staffA2);
        long first = sent(owner, channel, "1");
        long second = sent(owner, channel, "2");
        long third = sent(owner, channel, "3");

        var moved = ok(read(staffA, channel, second));
        assertThat(moved.get("lastReadMessageId").asString()).isEqualTo(Long.toString(second));
        assertThat(moved.get("unreadCount").asLong()).isEqualTo(1);
        String at = moved.get("lastReadAt").asString();

        var back = ok(read(staffA, channel, first));
        assertThat(back.get("lastReadMessageId").asString()).isEqualTo(Long.toString(second));
        assertThat(back.get("lastReadAt").asString()).isEqualTo(at);
        assertThat(back.get("unreadCount").asLong()).isEqualTo(1);
        var same = ok(read(staffA, channel, second));
        assertThat(same.get("lastReadAt").asString()).isEqualTo(at);
        assertThat(count("SELECT last_read_message_id FROM conversation_members WHERE conversation_id=? AND employee_id=?", channel, staffA)).isEqualTo(second);

        assertThat(ok(read(staffA, channel, third)).get("unreadCount").asLong()).isZero();
        assertThat(detail(staffA2, channel).get("lastReadMessageId").isNull()).isTrue();
        assertThat(detail(staffA2, channel).get("unreadCount").asLong()).isEqualTo(3);
        refused(read(staffA, channel, 999_999_999L), 404, "NOT_FOUND");
        refused(read(staffA, channel, "abc"), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", "/api/channels/" + channel + "/read", Map.of("messageId", Long.toString(third), "employeeId", Long.toString(staffA2))), 422, "VALIDATION_FAILED");
    }

    @Test void unreadCountsOriginalsAfterThePositionAndEveryUnopenedReplyFromOthers() throws Exception {
        long channel = channelId(owner, a, "안 읽음", staffA);
        long first = sent(owner, channel, "남의 원문");
        sent(staffA, channel, "내 원문");
        long reply = reply(owner, channel, "남의 답글", first);
        reply(staffA, channel, "내 답글", first);
        assertThat(detail(staffA, channel).get("unreadCount").asLong()).isEqualTo(2);
        var listed = ok(get(staffA, "/api/channels")).get("items");
        assertThat(listed.get(0).get("unreadCount").asLong()).isEqualTo(2);
        assertThat(listed.get(0).get("lastMessageAt").isNull()).isFalse();

        // The main feed's position says nothing about replies, and a reply cannot be it.
        refused(read(staffA, channel, reply), 422, "INVALID_READ_TARGET");
        assertThat(ok(read(staffA, channel, first)).get("unreadCount").asLong()).isEqualTo(1);
        long later = sent(owner, channel, "이후 원문");
        assertThat(detail(staffA, channel).get("unreadCount").asLong()).isEqualTo(2);
        assertThat(ok(read(staffA, channel, later)).get("lastReadMessageId").asString()).isEqualTo(Long.toString(later));
        reply(owner, channel, "나중의 이전 스레드 답글", first);
        assertThat(detail(staffA, channel).get("unreadCount").asLong()).isEqualTo(2);
    }

    // ---- task cards stay unbroken ---------------------------------------------------------------------------

    @Test void aTaskCardRowReadsAsACardAndTakesRepliesAndKeys() throws Exception {
        long channel = channelId(owner, a, "카드", staffA);
        jdbc.update("INSERT INTO work_tasks(branch_id,title,created_by,assignee_employee_id,category) VALUES(?,'지점 업무',?,?,'일반')", a, staffA, staffA);
        long task = count("SELECT id FROM work_tasks WHERE branch_id=? AND title='지점 업무'", a);
        tasks.add(task);
        jdbc.update("INSERT INTO messages(conversation_id,sender_employee_id,related_task_id,kind,client_message_key) VALUES(?,?,?,'TASK_CARD','card-key')", channel, owner, task);
        long card = count("SELECT id FROM messages WHERE conversation_id=?", channel);

        var item = ok(get(staffA, "/api/channels/" + channel + "/messages")).get("items").get(0);
        assertThat(item.get("kind").asString()).isEqualTo("업무 카드");
        assertThat(item.get("body").isNull()).isTrue();
        assertThat(item.get("task").get("accessible").asBoolean()).isTrue();
        assertThat(item.get("task").get("title").asString()).isEqualTo("지점 업무");
        assertThat(ok(get(staffA, "/api/channels/" + channel + "/messages/" + card)).get("kind").asString()).isEqualTo("업무 카드");
        assertThat(detail(staffA, channel).get("unreadCount").asLong()).isEqualTo(1);
        assertThat(reply(staffA, channel, "카드에 단 답글", card)).isPositive();
        // A text can never replay a card that holds the same key.
        refused(send(owner, channel, "card-key", "텍스트", null), 409, "IDEMPOTENCY_CONFLICT");
    }

    // ---- races ----------------------------------------------------------------------------------------------

    @Test void concurrentRepeatsOfOneKeyStoreOneMessage() throws Exception {
        long channel = channelId(owner, a, "동시 재전송", staffA);
        String key = key();
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 6; index++) {
                var session = login(staffA);
                open.add(session);
                calls.add(() -> sendWith(session, channel, key, "동시", null));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsExactly(200, 200, 200, 200, 200, 201);
            assertThat(results.stream().map(result -> node(result).get("id").asString()).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
        } finally {
            open.forEach(Session::close);
        }
    }

    @Test void concurrentSendsKeepEveryMessageInOneOrder() throws Exception {
        long channel = channelId(owner, a, "동시 전송", staffA, staffA2, dual);
        var senders = List.of(owner, staffA, staffA2, dual);
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (long sender : senders) {
                var session = login(sender);
                open.add(session);
                for (int index = 0; index < 5; index++) {
                    String text = sender + " #" + index;
                    calls.add(() -> sendWith(session, channel, key(), text, null));
                }
            }
            var results = race(calls);
            assertThat(statuses(results)).containsOnly(201);
            var sentIds = new HashSet<Long>();
            for (var result : results) sentIds.add(id(node(result)));
            assertThat(sentIds).hasSize(20);

            String path = "/api/channels/" + channel + "/messages";
            var seen = new ArrayList<Long>();
            Object cursor = null;
            while (true) {
                var page = ok(get(owner, path + "?limit=7" + (cursor == null ? "" : "&before=" + cursor)));
                seen.addAll(0, items(page));
                if (!page.get("hasMore").asBoolean()) break;
                cursor = page.get("nextCursor").asString();
            }
            assertThat(seen).hasSize(20).doesNotHaveDuplicates().isSorted();
            assertThat(new HashSet<>(seen)).isEqualTo(sentIds);
            // The guard makes the ID order the commit order, so the time of sending never runs backwards.
            var times = jdbc.queryForList("SELECT sent_at FROM messages WHERE conversation_id=? ORDER BY id", java.sql.Timestamp.class, channel);
            assertThat(times).isSorted();
        } finally {
            open.forEach(Session::close);
        }
    }

    @Test void aSendRacingARemovalEitherHappenedBeforeItOrNotAtAll() throws Exception {
        for (int round = 0; round < 6; round++) {
            long channel = channelId(owner, a, "제거 경합 " + round, staffA);
            var sender = login(staffA);
            var manager = login(owner);
            try {
                var results = race(List.<Callable<HttpResponse<String>>>of(
                        () -> sendWith(sender, channel, key(), "경합", null),
                        () -> http(manager.client(), "PUT", "/api/channels/" + channel + "/members", manager.token(),
                                Map.of("version", 1, "employeeIds", ids(owner)), null)));
                var send = results.get(0);
                assertThat(results.get(1).statusCode()).isEqualTo(200);
                assertThat(send.statusCode()).as(send.body()).isIn(201, 404);
                long rows = count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel);
                assertThat(rows).isEqualTo(send.statusCode() == 201 ? 1 : 0);
                if (rows == 1) assertThat(count("SELECT COUNT(*) FROM messages m JOIN conversation_members c ON c.conversation_id=m.conversation_id "
                        + "AND c.employee_id=m.sender_employee_id WHERE m.conversation_id=? AND m.sent_at<=c.left_at", channel)).isEqualTo(1);
                refused(sendWith(sender, channel, key(), "제거 후", null), 404, "NOT_FOUND");
            } finally {
                sender.close(); manager.close();
            }
        }
    }

    @Test void aSendRacingARevocationEitherHappenedBeforeItOrNotAtAll() throws Exception {
        for (int round = 0; round < 4; round++) {
            long target = person("EMPLOYEE", "회수 대상 " + round, "ACTIVE");
            member(target, a, "STAFF");
            long channel = channelId(owner, a, "회수 경합 " + round, target);
            var sender = login(target);
            as(owner);
            try {
                var results = race(List.<Callable<HttpResponse<String>>>of(() -> sendWith(sender, channel, key(), "경합", null), () -> revoke(target, a)));
                var send = results.get(0);
                assertThat(results.get(1).statusCode()).as(results.get(1).body()).isEqualTo(200);
                assertThat(send.statusCode()).as(send.body()).isIn(201, 404);
                assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(send.statusCode() == 201 ? 1 : 0);
                assertThat(leftAtIsSet(channel, target)).isEqualTo(1);
            } finally {
                sender.close();
            }
        }
    }

    @Test void concurrentReadPositionsSettleOnTheFurthest() throws Exception {
        long channel = channelId(owner, a, "동시 읽음", staffA);
        var messages = new ArrayList<Long>();
        for (int index = 0; index < 6; index++) messages.add(sent(owner, channel, "메시지 " + index));
        long furthest = messages.getLast();
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (long message : messages) {
                var session = login(staffA);
                open.add(session);
                calls.add(() -> http(session.client(), "POST", "/api/channels/" + channel + "/read", session.token(), Map.of("messageId", Long.toString(message)), null));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsOnly(200);
            assertThat(count("SELECT last_read_message_id FROM conversation_members WHERE conversation_id=? AND employee_id=?", channel, staffA)).isEqualTo(furthest);
            // Whatever order they ran in, nobody was answered with a position behind their own request.
            var answers = new HashMap<Long, Long>();
            for (int index = 0; index < results.size(); index++)
                answers.put(messages.get(index), Long.parseLong(node(results.get(index)).get("lastReadMessageId").asString()));
            answers.forEach((asked, answered) -> assertThat(answered).isGreaterThanOrEqualTo(asked));
        } finally {
            open.forEach(Session::close);
        }
    }
}
