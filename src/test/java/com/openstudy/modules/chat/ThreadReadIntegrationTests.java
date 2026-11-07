package com.openstudy.modules.chat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #155: the main feed and every thread have their own read position (collaboration-api.md, M7-D08·D09). Each test
 * goes through the API against MySQL, because what matters is what a reader is told, not what a row holds.
 */
@MySqlIntegrationTest
class ThreadReadIntegrationTests extends ChannelTestSupport {

    HttpResponse<String> readThread(long person, long channel, Object original, Object reply) throws Exception {
        return write(person, "POST", threadPath(channel, original), Map.of("replyId", reply.toString()));
    }

    static String threadPath(long channel, Object original) { return "/api/channels/" + channel + "/messages/" + original + "/read"; }

    JsonNode message(long person, long channel, long id) throws Exception { return ok(get(person, "/api/channels/" + channel + "/messages/" + id)); }

    void counts(JsonNode seen, long total, long main, long replies) {
        assertThat(seen.get("unreadCount").asLong()).as(seen.toString()).isEqualTo(total);
        assertThat(seen.get("mainUnreadCount").asLong()).as(seen.toString()).isEqualTo(main);
        assertThat(seen.get("replyUnreadCount").asLong()).as(seen.toString()).isEqualTo(replies);
    }

    void thread(long person, long channel, long original, long unread, Long position) throws Exception {
        var seen = message(person, channel, original);
        assertThat(seen.get("threadUnreadCount").asLong()).as(seen.toString()).isEqualTo(unread);
        if (position == null) assertThat(seen.get("threadLastReadMessageId").isNull()).as(seen.toString()).isTrue();
        else assertThat(seen.get("threadLastReadMessageId").asString()).isEqualTo(position.toString());
    }

    long threadRows(long channel, long person) {
        return count("SELECT COUNT(*) FROM conversation_thread_reads WHERE conversation_id=? AND employee_id=?", channel, person);
    }

    long baseline(long channel, long person) {
        return count("SELECT COALESCE(thread_read_baseline_message_id,0) FROM conversation_members WHERE conversation_id=? AND employee_id=?", channel, person);
    }

    // ---- independence ---------------------------------------------------------------------------------------

    @Test void readingALaterOriginalLeavesTheRepliesOfAnEarlierOneUnread() throws Exception {
        long channel = channelId(owner, a, "독립 본문", staffA);
        long first = sent(owner, channel, "본문 A");
        long reply = reply(owner, channel, "A의 답글 B", first);
        long later = sent(owner, channel, "본문 C");

        var moved = ok(read(staffA, channel, later));
        assertThat(moved.get("lastReadMessageId").asString()).isEqualTo(Long.toString(later));
        counts(moved, 1, 0, 1);
        counts(detail(staffA, channel), 1, 0, 1);
        thread(staffA, channel, first, 1, null);

        var opened = ok(readThread(staffA, channel, first, reply));
        assertThat(opened.get("messageId").asString()).isEqualTo(Long.toString(first));
        assertThat(opened.get("lastReadMessageId").asString()).isEqualTo(Long.toString(reply));
        assertThat(opened.get("lastReadAt").isNull()).isFalse();
        assertThat(opened.get("threadUnreadCount").asLong()).isZero();
        counts(opened, 0, 0, 0);
    }

    @Test void readingAThreadMovesNeitherOtherThreadsNorTheMainFeed() throws Exception {
        long channel = channelId(owner, a, "독립 스레드", staffA);
        long first = sent(owner, channel, "본문 A");
        long second = sent(owner, channel, "본문 B");
        long replyOfFirst = reply(owner, channel, "A의 답글", first);
        long replyOfSecond = reply(owner, channel, "B의 답글", second);

        var opened = ok(readThread(staffA, channel, first, replyOfFirst));
        assertThat(opened.get("threadUnreadCount").asLong()).isZero();
        counts(opened, 3, 2, 1);
        thread(staffA, channel, first, 0, replyOfFirst);
        thread(staffA, channel, second, 1, null);
        var seen = detail(staffA, channel);
        counts(seen, 3, 2, 1);
        assertThat(seen.get("lastReadMessageId").isNull()).isTrue();

        ok(readThread(staffA, channel, second, replyOfSecond));
        thread(staffA, channel, first, 0, replyOfFirst);
        thread(staffA, channel, second, 0, replyOfSecond);
        counts(detail(staffA, channel), 2, 2, 0);
    }

    @Test void aPositionIsTheRequestersOwnAndANewReplyInAReadThreadIsUnreadAgain() throws Exception {
        long channel = channelId(owner, a, "요청자", staffA, staffA2);
        long original = sent(owner, channel, "본문");
        long earlier = reply(owner, channel, "답글 1", original);
        long later = reply(owner, channel, "답글 2", original);

        ok(readThread(staffA, channel, original, earlier));
        thread(staffA, channel, original, 1, earlier);
        thread(staffA2, channel, original, 2, null);
        counts(detail(staffA2, channel), 3, 1, 2);
        assertThat(threadRows(channel, staffA2)).isZero();

        ok(readThread(staffA, channel, original, later));
        long newest = reply(owner, channel, "답글 3", original);
        thread(staffA, channel, original, 1, later);
        assertThat(ok(readThread(staffA, channel, original, newest)).get("threadUnreadCount").asLong()).isZero();
        refused(write(staffA, "POST", threadPath(channel, original), Map.of("replyId", Long.toString(later), "employeeId", Long.toString(staffA2))), 422, "VALIDATION_FAILED");
    }

    @Test void aReadStaysWithItsReaderAndRaisesNoAuditRow() throws Exception {
        long channel = channelId(owner, a, "이벤트 없음", staffA);
        long original = sent(owner, channel, "본문");
        long reply = reply(owner, channel, "답글", original);
        as(staffA);
        long audits = count("SELECT COUNT(*) FROM audit_events");
        ok(read(staffA, channel, original));
        ok(readThread(staffA, channel, original, reply));
        assertThat(count("SELECT COUNT(*) FROM audit_events")).isEqualTo(audits);
    }

    // ---- forward only ---------------------------------------------------------------------------------------

    @Test void aThreadPositionOnlyMovesForwardAndARepeatChangesNothing() throws Exception {
        long channel = channelId(owner, a, "단조", staffA);
        long original = sent(owner, channel, "본문");
        long one = reply(owner, channel, "1", original);
        long two = reply(owner, channel, "2", original);
        long three = reply(owner, channel, "3", original);

        var moved = ok(readThread(staffA, channel, original, two));
        assertThat(moved.get("threadUnreadCount").asLong()).isEqualTo(1);
        String at = moved.get("lastReadAt").asString();

        for (long earlier : List.of(one, two)) {
            var repeated = ok(readThread(staffA, channel, original, earlier));
            assertThat(repeated.get("lastReadMessageId").asString()).isEqualTo(Long.toString(two));
            assertThat(repeated.get("lastReadAt").asString()).isEqualTo(at);
            assertThat(repeated.get("threadUnreadCount").asLong()).isEqualTo(1);
        }
        assertThat(count("SELECT last_read_message_id FROM conversation_thread_reads WHERE conversation_id=? AND employee_id=?", channel, staffA)).isEqualTo(two);
        assertThat(ok(readThread(staffA, channel, original, three)).get("lastReadMessageId").asString()).isEqualTo(Long.toString(three));
    }

    @Test void concurrentUpdatesOfOneThreadSettleOnTheFurthestAndTheFirstRowIsCreatedOnce() throws Exception {
        long channel = channelId(owner, a, "동시 스레드", staffA);
        long original = sent(owner, channel, "본문");
        var replies = new ArrayList<Long>();
        for (int index = 0; index < 6; index++) replies.add(reply(owner, channel, "답글 " + index, original));
        long furthest = replies.getLast();
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (long reply : replies) {
                var session = login(staffA);
                open.add(session);
                calls.add(() -> http(session.client(), "POST", threadPath(channel, original), session.token(), Map.of("replyId", Long.toString(reply)), null));
            }
            var results = race(calls);
            assertThat(statuses(results)).containsOnly(200);
            assertThat(threadRows(channel, staffA)).isEqualTo(1);
            assertThat(count("SELECT last_read_message_id FROM conversation_thread_reads WHERE conversation_id=? AND employee_id=?", channel, staffA)).isEqualTo(furthest);
            var answers = new HashMap<Long, Long>();
            for (int index = 0; index < results.size(); index++)
                answers.put(replies.get(index), Long.parseLong(node(results.get(index)).get("lastReadMessageId").asString()));
            answers.forEach((asked, answered) -> assertThat(answered).isGreaterThanOrEqualTo(asked));
        } finally {
            open.forEach(Session::close);
        }
    }

    @Test void concurrentRepeatsOfTheFirstRequestOfAThreadAreAllAnswered() throws Exception {
        long channel = channelId(owner, a, "동시 첫 행", staffA);
        long original = sent(owner, channel, "본문");
        long reply = reply(owner, channel, "답글", original);
        var open = new ArrayList<Session>();
        try {
            var calls = new ArrayList<Callable<HttpResponse<String>>>();
            for (int index = 0; index < 6; index++) {
                var session = login(staffA);
                open.add(session);
                calls.add(() -> http(session.client(), "POST", threadPath(channel, original), session.token(), Map.of("replyId", Long.toString(reply)), null));
            }
            assertThat(statuses(race(calls))).containsOnly(200);
            assertThat(threadRows(channel, staffA)).isEqualTo(1);
        } finally {
            open.forEach(Session::close);
        }
    }

    // ---- counts ---------------------------------------------------------------------------------------------

    @Test void theSumAndTheSplitLeaveOutTheReadersOwnMessagesAndNeverTheOthers() throws Exception {
        long channel = channelId(owner, a, "합산", staffA);
        long theirs = sent(owner, channel, "남의 원문");
        long mine = sent(staffA, channel, "내 원문");
        reply(owner, channel, "남의 원문에 남의 답글", theirs);
        reply(staffA, channel, "남의 원문에 내 답글", theirs);
        long onMine = reply(owner, channel, "내 원문에 남의 답글", mine);
        reply(staffA, channel, "내 원문에 내 답글", mine);

        var seen = detail(staffA, channel);
        counts(seen, 3, 1, 2);
        counts(ok(get(staffA, "/api/channels")).get("items").get(0), 3, 1, 2);
        thread(staffA, channel, theirs, 1, null);
        thread(staffA, channel, mine, 1, null);
        // Sending moves no position: the reply the reader wrote does not cover the one before it.
        assertThat(detail(staffA, channel).get("lastReadMessageId").isNull()).isTrue();
        assertThat(threadRows(channel, staffA)).isZero();

        // What each side counts for the other: the sender's own reply is out for them, in for the reader.
        counts(detail(owner, channel), 3, 1, 2);
        ok(readThread(staffA, channel, mine, onMine));
        counts(detail(staffA, channel), 2, 1, 1);
    }

    @Test void onlyOriginalsCarryThreadFieldsAndTheFeedsAgreeWithThem() throws Exception {
        long channel = channelId(owner, a, "응답 필드", staffA);
        long original = sent(owner, channel, "본문");
        long reply = reply(owner, channel, "답글", original);
        long unanswered = sent(owner, channel, "답글 없는 본문");

        var answer = message(staffA, channel, reply);
        assertThat(answer.has("threadUnreadCount")).isTrue();
        assertThat(answer.get("threadUnreadCount").isNull()).isTrue();
        assertThat(answer.get("threadLastReadMessageId").isNull()).isTrue();
        var replies = ok(get(staffA, "/api/channels/" + channel + "/messages/" + original + "/replies")).get("items");
        assertThat(replies.get(0).get("threadUnreadCount").isNull()).isTrue();

        var feed = ok(get(staffA, "/api/channels/" + channel + "/messages")).get("items");
        assertThat(feed.get(0).get("threadUnreadCount").asLong()).isEqualTo(1);
        assertThat(feed.get(0).get("threadLastReadMessageId").isNull()).isTrue();
        assertThat(feed.get(1).get("threadUnreadCount").asLong()).isZero();
        ok(readThread(staffA, channel, original, reply));
        feed = ok(get(staffA, "/api/channels/" + channel + "/messages")).get("items");
        assertThat(feed.get(0).get("threadUnreadCount").asLong()).isZero();
        assertThat(feed.get(0).get("threadLastReadMessageId").asString()).isEqualTo(Long.toString(reply));
        assertThat(id(feed.get(1))).isEqualTo(unanswered);
    }

    @Test void aCardCountsLikeAnyMessageWhereItStands() throws Exception {
        long channel = channelId(owner, a, "카드 읽음", staffA);
        jdbc.update("INSERT INTO work_tasks(branch_id,title,created_by,assignee_employee_id,category) VALUES(?,'읽음 업무',?,?,'일반')", a, staffA, staffA);
        long task = count("SELECT id FROM work_tasks WHERE branch_id=? AND title='읽음 업무'", a);
        tasks.add(task);
        var card = write(owner, "POST", "/api/channels/" + channel + "/task-cards", Map.of("clientMessageKey", key(), "taskId", Long.toString(task)));
        assertThat(card.statusCode()).as(card.body()).isEqualTo(201);
        long original = id(node(card));
        var replied = write(owner, "POST", "/api/channels/" + channel + "/task-cards",
                Map.of("clientMessageKey", key(), "taskId", Long.toString(task), "parentId", Long.toString(original)));
        assertThat(replied.statusCode()).as(replied.body()).isEqualTo(201);
        long cardReply = id(node(replied));

        counts(detail(staffA, channel), 2, 1, 1);
        thread(staffA, channel, original, 1, null);
        refused(read(staffA, channel, cardReply), 422, "INVALID_READ_TARGET");
        ok(read(staffA, channel, original));
        counts(detail(staffA, channel), 1, 0, 1);
        assertThat(ok(readThread(staffA, channel, original, cardReply)).get("lastReadMessageId").asString()).isEqualTo(Long.toString(cardReply));
        counts(detail(staffA, channel), 0, 0, 0);
    }

    // ---- refused --------------------------------------------------------------------------------------------

    @Test void anythingButAReplyOfThatOriginalIsRefusedAndMovesNothing() throws Exception {
        long channel = channelId(owner, a, "거절", staffA);
        long other = channelId(owner, a, "다른 채널", staffA);
        long original = sent(owner, channel, "본문");
        long sibling = sent(owner, channel, "다른 본문");
        long reply = reply(owner, channel, "답글", original);
        long stranger = reply(owner, channel, "다른 스레드 답글", sibling);
        long foreign = sent(owner, other, "다른 채널 본문");
        long foreignReply = reply(owner, other, "다른 채널 답글", foreign);

        // The main feed takes originals only.
        refused(read(staffA, channel, reply), 422, "INVALID_READ_TARGET");
        // Not of this thread, or not a reply at all.
        refused(readThread(staffA, channel, original, stranger), 422, "INVALID_READ_TARGET");
        refused(readThread(staffA, channel, original, sibling), 422, "INVALID_READ_TARGET");
        refused(readThread(staffA, channel, original, original), 422, "INVALID_READ_TARGET");
        // A position under a reply.
        refused(readThread(staffA, channel, reply, reply), 422, "INVALID_READ_TARGET");
        // Another channel's IDs, on either side, and IDs that do not exist.
        refused(readThread(staffA, channel, foreign, foreignReply), 404, "NOT_FOUND");
        refused(readThread(staffA, channel, original, foreignReply), 404, "NOT_FOUND");
        refused(readThread(staffA, other, original, reply), 404, "NOT_FOUND");
        refused(readThread(staffA, channel, 999_999_999L, reply), 404, "NOT_FOUND");
        refused(readThread(staffA, channel, original, 999_999_999L), 404, "NOT_FOUND");
        // Malformed input.
        refused(readThread(staffA, channel, original, "abc"), 422, "VALIDATION_FAILED");
        refused(write(staffA, "POST", threadPath(channel, original), Map.of("messageId", Long.toString(reply))), 422, "VALIDATION_FAILED");

        assertThat(threadRows(channel, staffA)).isZero();
        assertThat(detail(staffA, channel).get("lastReadMessageId").isNull()).isTrue();
    }

    @Test void onlyAnOpenQualifiedParticipationReaches() throws Exception {
        long channel = channelId(owner, a, "참여 확인", staffA, staffA2);
        long original = sent(owner, channel, "본문");
        long reply = reply(owner, channel, "답글", original);

        // Whoever never joined, a manager of the branch included, also with input a participant would get 422 for.
        for (long person : List.of(staffB, managerB, dual, managerA)) {
            refused(readThread(person, channel, original, reply), 404, "NOT_FOUND");
            refused(write(person, "POST", threadPath(channel, original), Map.of("wrong", "field")), 404, "NOT_FOUND");
        }
        // A manager sees the settings and no message facts.
        var outside = detail(managerA, channel);
        counts(outside, 0, 0, 0);
        assertThat(outside.get("joined").asBoolean()).isFalse();
        assertThat(outside.get("lastReadMessageId").isNull()).isTrue();
        // Removed from the list.
        ok(replaceMembers(owner, channel, 1, owner, staffA));
        refused(readThread(staffA2, channel, original, reply), 404, "NOT_FOUND");
        // Membership revoked: the participation ended with it.
        ok(revoke(staffA, a));
        refused(readThread(staffA, channel, original, reply), 404, "NOT_FOUND");
        refused(read(staffA, channel, original), 404, "NOT_FOUND");
        assertThat(count("SELECT COUNT(*) FROM conversation_thread_reads WHERE conversation_id=?", channel)).isZero();
    }

    @Test void aDmIsNotAChannelHereEither() throws Exception {
        long low = Math.min(staffA, staffA2), high = Math.max(staffA, staffA2);
        jdbc.update("INSERT INTO conversations(kind,dm_employee_low_id,dm_employee_high_id,created_by) VALUES('DM',?,?,?)", low, high, staffA);
        long dm = count("SELECT id FROM conversations WHERE dm_employee_low_id=? AND dm_employee_high_id=?", low, high);
        for (long person : List.of(staffA, staffA2)) jdbc.update("INSERT INTO conversation_members(conversation_id,employee_id) VALUES(?,?)", dm, person);
        jdbc.update("INSERT INTO messages(conversation_id,sender_employee_id,body,client_message_key) VALUES(?,?,'원문','dm-1')", dm, staffA);
        long original = count("SELECT id FROM messages WHERE conversation_id=?", dm);
        jdbc.update("INSERT INTO messages(conversation_id,sender_employee_id,parent_message_id,body,client_message_key) VALUES(?,?,?,'답글','dm-2')", dm, staffA, original);
        long reply = count("SELECT MAX(id) FROM messages WHERE conversation_id=?", dm);
        refused(readThread(staffA2, dm, original, reply), 404, "NOT_FOUND");
        assertThat(threadRows(dm, staffA2)).isZero();
        assertThat(get(staffA2, "/api/channels").body()).doesNotContain("\"id\":\"" + dm + "\"");
    }

    // ---- joining again --------------------------------------------------------------------------------------

    @Test void joiningAgainReadsEverythingSentSoFarAndKeepsItOpen() throws Exception {
        long channel = channelId(managerA, a, "재참여", staffA);
        long first = sent(managerA, channel, "이전 원문");
        long earlier = reply(managerA, channel, "이전 답글", first);
        ok(readThread(staffA, channel, first, earlier));
        ok(replaceMembers(managerA, channel, 1, managerA));
        long away = sent(managerA, channel, "떠난 동안의 원문");
        long awayReply = reply(managerA, channel, "떠난 동안의 답글", first);
        long awayOther = reply(managerA, channel, "떠난 동안의 다른 스레드 답글", away);

        ok(replaceMembers(managerA, channel, 2, managerA, staffA));

        var seen = detail(staffA, channel);
        counts(seen, 0, 0, 0);
        assertThat(seen.get("lastReadMessageId").asString()).isEqualTo(Long.toString(awayOther));
        // Past the old row of that thread, and in a thread that never had one.
        thread(staffA, channel, first, 0, awayOther);
        thread(staffA, channel, away, 0, awayOther);
        // Read, and not out of sight.
        assertThat(items(ok(get(staffA, "/api/channels/" + channel + "/messages")))).containsExactly(first, away);
        assertThat(items(ok(get(staffA, "/api/channels/" + channel + "/messages/" + first + "/replies")))).containsExactly(earlier, awayReply);

        long next = sent(managerA, channel, "재참여 뒤 원문");
        long nextReply = reply(managerA, channel, "재참여 뒤 답글", first);
        counts(detail(staffA, channel), 2, 1, 1);
        thread(staffA, channel, first, 1, awayOther);
        // Asking for a reply the baseline covers writes nothing and answers the position in force.
        var covered = ok(readThread(staffA, channel, first, awayReply));
        assertThat(covered.get("lastReadMessageId").asString()).isEqualTo(Long.toString(awayOther));
        assertThat(covered.get("threadUnreadCount").asLong()).isEqualTo(1);
        assertThat(ok(readThread(staffA, channel, first, nextReply)).get("threadUnreadCount").asLong()).isZero();
        assertThat(ok(read(staffA, channel, next)).get("lastReadMessageId").asString()).isEqualTo(Long.toString(next));
        counts(detail(staffA, channel), 0, 0, 0);
    }

    @Test void aThreadTheBaselineCoversAnswersAPositionWithoutARow() throws Exception {
        long channel = channelId(managerA, a, "기준 위치 응답", staffA);
        long original = sent(managerA, channel, "원문");
        long reply = reply(managerA, channel, "답글", original);
        ok(replaceMembers(managerA, channel, 1, managerA));
        ok(replaceMembers(managerA, channel, 2, managerA, staffA));

        var covered = ok(readThread(staffA, channel, original, reply));
        assertThat(covered.get("lastReadMessageId").asString()).isEqualTo(Long.toString(reply));
        assertThat(covered.get("lastReadAt").isNull()).isTrue();
        assertThat(threadRows(channel, staffA)).isZero();
    }

    @Test void joiningAnEmptyChannelAgainLeavesThePositionsEmpty() throws Exception {
        long channel = channelId(managerA, a, "빈 채널", staffA);
        ok(replaceMembers(managerA, channel, 1, managerA));
        ok(replaceMembers(managerA, channel, 2, managerA, staffA));
        var seen = detail(staffA, channel);
        assertThat(seen.get("lastReadMessageId").isNull()).isTrue();
        assertThat(baseline(channel, staffA)).isZero();
        long first = sent(managerA, channel, "첫 메시지");
        reply(managerA, channel, "첫 답글", first);
        counts(detail(staffA, channel), 2, 1, 1);
    }

    @Test void onlyWhoComesBackMoves() throws Exception {
        long channel = channelId(managerA, a, "남은 사람", staffA, staffA2);
        long original = sent(managerA, channel, "원문");
        reply(managerA, channel, "답글", original);
        ok(replaceMembers(managerA, channel, 1, managerA, staffA));
        long later = sent(managerA, channel, "떠난 뒤 원문");

        ok(replaceMembers(managerA, channel, 2, managerA, staffA, staffA2, dual));

        // Someone who stayed on the list was not joining again, and someone new starts with everything unread.
        var stayed = detail(staffA, channel);
        counts(stayed, 3, 2, 1);
        assertThat(stayed.get("lastReadMessageId").isNull()).isTrue();
        assertThat(baseline(channel, staffA)).isZero();
        counts(detail(dual, channel), 3, 2, 1);
        var back = detail(staffA2, channel);
        counts(back, 0, 0, 0);
        assertThat(back.get("lastReadMessageId").asString()).isEqualTo(Long.toString(later));
    }

    @Test void endingAndRegainingAccessDoesNotJoinAgainAndKeepsThePositionsUntilItDoes() throws Exception {
        long channel = channelId(owner, a, "회수 후 복귀", staffA);
        long first = sent(owner, channel, "원문");
        long replied = reply(owner, channel, "답글", first);
        ok(readThread(staffA, channel, first, replied));
        ok(revoke(staffA, a));
        assertThat(leftAtIsSet(channel, staffA)).isEqualTo(1);
        long away = reply(owner, channel, "회수 동안의 답글", first);
        assertThat(baseline(channel, staffA)).isZero();
        assertThat(count("SELECT last_read_message_id FROM conversation_thread_reads WHERE conversation_id=? AND employee_id=?", channel, staffA)).isEqualTo(replied);

        // The membership comes back, the participation does not, until a manager puts them on the list.
        jdbc.update("UPDATE branch_memberships SET status='ACTIVE' WHERE employee_id=? AND branch_id=?", staffA, a);
        refused(readThread(staffA, channel, first, away), 404, "NOT_FOUND");
        ok(replaceMembers(owner, channel, version(channel), owner, staffA));
        counts(detail(staffA, channel), 0, 0, 0);
        thread(staffA, channel, first, 0, away);
    }

    // ---- what the V6 migration leaves behind ----------------------------------------------------------------

    /** The same statement as V6: the one channel-wide position becomes the baseline of every thread. */
    void migrate(long channel) {
        jdbc.update("UPDATE conversation_members SET thread_read_baseline_message_id = last_read_message_id WHERE conversation_id=?", channel);
    }

    void formerPosition(long channel, long person, long message) {
        jdbc.update("UPDATE conversation_members SET last_read_message_id=?, last_read_at=UTC_TIMESTAMP(6) WHERE conversation_id=? AND employee_id=?", message, channel, person);
    }

    @Test void afterTheMigrationNothingThatWasReadTurnsUnreadAndTheTotalStaysTheSame() throws Exception {
        long channel = channelId(owner, a, "전환", staffA, staffA2);
        long original = sent(owner, channel, "원문");
        long read = reply(owner, channel, "읽은 답글", original);
        long position = sent(owner, channel, "읽은 원문");
        long unreadReply = reply(owner, channel, "안 읽은 답글", original);
        long unread = sent(owner, channel, "안 읽은 원문");
        formerPosition(channel, staffA, position);
        migrate(channel);

        // Past the old position: one reply and one original, which is what the channel-wide count said.
        counts(detail(staffA, channel), 2, 1, 1);
        thread(staffA, channel, original, 1, position);
        // A position of NULL stays everything unread.
        counts(detail(staffA2, channel), 5, 3, 2);
        thread(staffA2, channel, original, 2, null);
        assertThat(count("SELECT thread_read_baseline_message_id IS NULL FROM conversation_members WHERE conversation_id=? AND employee_id=?", channel, staffA2)).isEqualTo(1);

        // The baseline stands still: reading the main feed covers no further reply.
        ok(read(staffA, channel, unread));
        counts(detail(staffA, channel), 1, 0, 1);
        assertThat(baseline(channel, staffA)).isEqualTo(position);
        // Asking below the baseline moves nothing.
        var covered = ok(readThread(staffA, channel, original, read));
        assertThat(covered.get("lastReadMessageId").asString()).isEqualTo(Long.toString(position));
        assertThat(threadRows(channel, staffA)).isZero();
        assertThat(ok(readThread(staffA, channel, original, unreadReply)).get("threadUnreadCount").asLong()).isZero();
    }

    @Test void aFormerPositionOnAReplyKeepsWorkingByItsId() throws Exception {
        long channel = channelId(owner, a, "답글 위치", staffA);
        long original = sent(owner, channel, "원문");
        long position = reply(owner, channel, "위치가 된 답글", original);
        long unread = sent(owner, channel, "뒤의 원문");
        long unreadReply = reply(owner, channel, "뒤의 답글", original);
        formerPosition(channel, staffA, position);
        migrate(channel);

        var seen = detail(staffA, channel);
        counts(seen, 2, 1, 1);
        assertThat(seen.get("lastReadMessageId").asString()).isEqualTo(Long.toString(position));
        thread(staffA, channel, original, 1, position);
        // An original before that reply is behind the position: 200 with the value that stands, a reply still.
        assertThat(ok(read(staffA, channel, original)).get("lastReadMessageId").asString()).isEqualTo(Long.toString(position));
        assertThat(ok(read(staffA, channel, unread)).get("lastReadMessageId").asString()).isEqualTo(Long.toString(unread));
        assertThat(ok(readThread(staffA, channel, original, unreadReply)).get("unreadCount").asLong()).isZero();
    }

    // ---- across a deployment --------------------------------------------------------------------------------

    @Test void aCreateRepeatedFromARecordStoredBeforeTheSplitStillAnswers() throws Exception {
        var body = channelBody("지점", a, "배포 전 생성", staffA);
        String key = key();
        var first = createRaw(managerA, body, key);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        long channel = id(node(first));
        // The stored response as the previous release wrote it, without the two split counts.
        String stored = first.body().replace("\"mainUnreadCount\":0,\"replyUnreadCount\":0,", "");
        assertThat(stored).doesNotContain("mainUnreadCount");
        assertThat(jdbc.update("UPDATE idempotency_records SET response_json=? WHERE actor_employee_id=? AND idempotency_key=?", stored, managerA, key)).isEqualTo(1);

        var repeated = createRaw(managerA, body, key);
        assertThat(repeated.statusCode()).as(repeated.body()).isEqualTo(201);
        assertThat(id(node(repeated))).isEqualTo(channel);
        counts(node(repeated), 0, 0, 0);
    }
}
