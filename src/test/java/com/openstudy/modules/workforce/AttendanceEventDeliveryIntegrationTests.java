package com.openstudy.modules.workforce;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import com.openstudy.modules.workforce.ShiftEventDeliveryIntegrationTests.Stream;
import com.openstudy.modules.workspace.service.WorkspaceEventService;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An attendance event names the attendance, and through it the employee and when they clocked in or out, so it reaches
 * only who may read that attendance: the OWNER, the MANAGER of its branch and its own employee (workforce-api.md
 * "workspace 이벤트"). Same-branch staff and other branches' managers hear nothing; other branch events are unchanged.
 */
@TestPropertySource(properties = {"openstudy.workspace.scheduling.enabled=false", "openstudy.workspace.heartbeat-millis=600000"})
class AttendanceEventDeliveryIntegrationTests extends ShiftIntegrationSupport {
    @Autowired WorkspaceEventService events;
    final List<Stream> streams = new ArrayList<>();
    final List<Session> sessions = new ArrayList<>();

    @AfterEach void closeStreams() {
        events.tick();
        streams.forEach(Stream::close); streams.clear();
        sessions.forEach(Session::close); sessions.clear();
    }

    @Test void everyClockCommandReachesTheOwnerTheBranchManagerAndTheEmployeeOnly() throws Exception {
        events.tick();
        var boss = stream(owner, "all");
        var lead = stream(manager, Long.toString(first));
        var own = stream(staff, Long.toString(first));
        var peer = stream(colleague, Long.toString(first));
        var both = stream(worker, "all");
        var otherLead = stream(secondManager, "all");
        var me = session(staff);
        long id = created(clockIn(me, first)).get("id").asLong();
        events.tick();
        for (var stream : List.of(boss, lead, own)) {
            assertThat(stream.next()).contains("event:invalidate", "\"resource\":\"attendance-records\"", "\"entityId\":\"" + id + "\"",
                    "\"branchId\":\"" + first + "\"");
            stream.none();
        }
        for (var stream : List.of(peer, both, otherLead)) stream.none();
        ok(breakStart(me, 1));
        ok(breakEnd(me, 2));
        ok(clockOut(me, 3));
        events.tick();
        for (var stream : List.of(boss, lead, own)) {
            for (int index = 0; index < 3; index++) assertThat(stream.next()).contains("\"resource\":\"attendance-records\"", "\"entityId\":\"" + id + "\"");
            stream.none();
        }
        for (var stream : List.of(peer, both, otherLead)) {
            stream.none();
            assertThat(String.join("", stream.received)).doesNotContain("attendance-records", "\"entityId\":\"" + id + "\"");
        }
        // Other branch resources still reach every member.
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) VALUES (?,'tasks',?,'INVALIDATE')", first, 77);
        events.tick();
        assertThat(peer.next()).contains("\"resource\":\"tasks\"", "\"entityId\":\"77\"");
    }

    @Test void anAttendanceAtAnotherBranchDoesNotReachThisBranchsManager() throws Exception {
        events.tick();
        var lead = stream(manager, "all");
        var own = stream(worker, "all");
        var other = stream(secondManager, "all");
        var stranger = stream(outsider, Long.toString(second));
        long id = created(clockIn(session(worker), second)).get("id").asLong();
        events.tick();
        assertThat(own.next()).contains("\"resource\":\"attendance-records\"", "\"entityId\":\"" + id + "\"", "\"branchId\":\"" + second + "\"");
        assertThat(other.next()).contains("\"resource\":\"attendance-records\"", "\"entityId\":\"" + id + "\"");
        lead.none();
        stranger.none();
    }

    Session session(long person) throws Exception {
        var session = login(person);
        sessions.add(session);
        return session;
    }

    Stream stream(long person, String scope) throws Exception {
        var session = session(person);
        var response = session.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/workspace/events?scope=" + scope))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        var stream = new Stream(response.body());
        streams.add(stream);
        assertThat(stream.next()).contains("event:resync");
        return stream;
    }
}
