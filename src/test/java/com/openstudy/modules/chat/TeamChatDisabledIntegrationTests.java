package com.openstudy.modules.chat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #167 (ADR-M10-002 M10-D10): with the default setting no {@code /api/channels/**} operation exists. Channel rows written
 * before stay in their tables and are never returned, and reducing an employee's access still ends their participation.
 */
@MySqlIntegrationTest
@TestPropertySource(properties = "openstudy.team-chat.enabled=false")
class TeamChatDisabledIntegrationTests extends ChannelTestSupport {

    long storedChannel(Long branch, String name, long... members) {
        jdbc.update("INSERT INTO conversations(kind,branch_id,name,created_by) VALUES(?,?,?,?)",
                branch == null ? "GLOBAL_CHANNEL" : "BRANCH_CHANNEL", branch, name, owner);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM conversations WHERE created_by=?", Long.class, owner);
        for (long member : members) jdbc.update("INSERT INTO conversation_members(conversation_id,employee_id) VALUES(?,?)", id, member);
        jdbc.update("INSERT INTO messages(conversation_id,sender_employee_id,kind,body,client_message_key) VALUES(?,?,'TEXT',?,?)",
                id, owner, "남은 메시지 원문", key());
        return id;
    }

    @Test void everyChannelOperationIsAnUnknownPathAndNoStoredDataLeaves() throws Exception {
        long channel = storedChannel(a, "남은 지점 채널", owner, staffA);
        long message = jdbc.queryForObject("SELECT MAX(id) FROM messages WHERE conversation_id=?", Long.class, channel);
        String base = "/api/channels/" + channel;

        var reads = List.of("/api/channels", "/api/channels?scope=" + a, "/api/channels/manageable", "/api/channels/member-candidates",
                base, base + "/members", base + "/messages", base + "/messages/" + message, base + "/messages/" + message + "/replies");
        for (long person : List.of(owner, staffA)) {
            for (String path : reads) {
                var response = get(person, path);
                assertThat(response.statusCode()).as(path + " " + response.body()).isEqualTo(404);
                assertThat(response.body()).doesNotContain("남은 지점 채널", "남은 메시지 원문");
            }
        }
        var writes = List.of(
                write(owner, "POST", "/api/channels", channelBody("지점", a, "새 채널", staffA)),
                write(owner, "PATCH", base, Map.of("version", 1, "name", "바꾼 이름")),
                write(owner, "PUT", base + "/members", Map.of("version", 1, "employeeIds", ids(owner))),
                write(staffA, "POST", base + "/messages", Map.of("clientMessageKey", key(), "text", "보내기")),
                write(staffA, "POST", base + "/read", Map.of("messageId", Long.toString(message))),
                write(staffA, "POST", base + "/messages/" + message + "/read", Map.of("replyId", Long.toString(message))),
                write(staffA, "POST", base + "/task-cards", Map.of("clientMessageKey", key(), "taskId", "1")));
        for (var response : writes) assertThat(response.statusCode()).as(response.uri() + " " + response.body()).isEqualTo(404);

        // Nothing was written: the stored rows are exactly what the fixture put there.
        assertThat(count("SELECT COUNT(*) FROM conversations WHERE created_by=?", owner)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM messages WHERE conversation_id=?", channel)).isEqualTo(1);
        assertThat(count("SELECT version FROM conversations WHERE id=?", channel)).isZero();
        assertThat(count("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=? AND last_read_message_id IS NOT NULL", channel)).isZero();
    }

    @Test void anUnauthenticatedRequestIsStillRefusedFirst() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        assertThat(http(client, "GET", "/api/channels", null, null, null).statusCode()).isEqualTo(401);
        client.close();
    }

    @Test void reducingAccessStillEndsParticipationWhileTeamChatIsOff() throws Exception {
        long channelA = storedChannel(a, "A 채널", owner, dual);
        long channelB = storedChannel(b, "B 채널", owner, dual);
        assertThat(ok(revoke(dual, a)).get("membership").get("status").asString()).isEqualTo("회수");
        assertThat(leftAtIsSet(channelA, dual)).isEqualTo(1);
        assertThat(leftAtIsSet(channelB, dual)).isZero();
        assertThat(count("SELECT version FROM conversations WHERE id=?", channelA)).isEqualTo(1);

        long target = person("EMPLOYEE", "비활성 대상", "ACTIVE");
        member(target, a, "STAFF");
        long global = storedChannel(null, "전체 채널", owner, target);
        ok(accountStatus(owner, target, "비활성"));
        assertThat(leftAtIsSet(global, target)).isEqualTo(1);
    }
}
