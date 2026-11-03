package com.openstudy.modules.workforce;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import com.openstudy.modules.workspace.service.WorkspaceEventService;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A shift event names the shift, and through it the employee, so it reaches only who may read that shift: the OWNER, the
 * MANAGER of its branch and its own employee (workforce-api.md "workspace 이벤트"). Other branch events are unchanged.
 */
@TestPropertySource(properties = {"openstudy.workspace.scheduling.enabled=false", "openstudy.workspace.heartbeat-millis=600000"})
class ShiftEventDeliveryIntegrationTests extends ShiftIntegrationSupport {
    @Autowired WorkspaceEventService events;
    final List<Stream> streams = new ArrayList<>();
    final List<Session> sessions = new ArrayList<>();

    @AfterEach void closeStreams() {
        events.tick();
        streams.forEach(Stream::close); streams.clear();
        sessions.forEach(Session::close); sessions.clear();
    }

    @Test void aShiftEventReachesTheOwnerTheBranchManagerAndTheEmployeeOnly() throws Exception {
        events.tick();
        var boss = stream(owner, "all");
        var lead = stream(manager, Long.toString(first));
        var own = stream(staff, Long.toString(first));
        var peer = stream(colleague, Long.toString(first));
        var otherLead = stream(secondManager, "all");
        var lead2 = session(manager);
        long id = registered(lead2, shift(first, staff, "2026-11-04 09:00", "2026-11-04 18:00"));
        events.tick();
        for (var stream : List.of(boss, lead, own)) {
            var frame = stream.next();
            assertThat(frame).contains("event:invalidate", "\"resource\":\"shifts\"", "\"entityId\":\"" + id + "\"", "\"branchId\":\"" + first + "\"");
            stream.none();
        }
        peer.none();
        otherLead.none();
        // The same for a change and a cancellation.
        ok(edit(lead2, id, change(1, "plannedBreakMinutes", 30)));
        ok(cancel(lead2, id, 2));
        events.tick();
        for (var stream : List.of(boss, lead, own)) {
            assertThat(stream.next()).contains("\"entityId\":\"" + id + "\"");
            assertThat(stream.next()).contains("\"entityId\":\"" + id + "\"");
            stream.none();
        }
        peer.none();
        assertThat(String.join("", peer.received)).doesNotContain("\"shifts\"", "\"entityId\":\"" + id + "\"");
        assertThat(String.join("", otherLead.received)).doesNotContain("\"shifts\"");
    }

    @Test void aShiftAtAnotherBranchDoesNotReachThisBranchsManager() throws Exception {
        events.tick();
        var lead = stream(manager, "all");
        var own = stream(worker, "all");
        var other = session(secondManager);
        long id = registered(other, shift(second, worker, "2026-11-04 09:00", "2026-11-04 18:00"));
        events.tick();
        assertThat(own.next()).contains("\"resource\":\"shifts\"", "\"entityId\":\"" + id + "\"");
        lead.none();
    }

    @Test void otherBranchEventsStillReachEveryMember() throws Exception {
        events.tick();
        var peer = stream(colleague, Long.toString(first));
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) VALUES (?,'tasks',?,'INVALIDATE')", first, 77);
        events.tick();
        assertThat(peer.next()).contains("\"resource\":\"tasks\"", "\"entityId\":\"77\"");
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

    static final class Stream implements AutoCloseable {
        final InputStream body;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final List<String> received = java.util.Collections.synchronizedList(new ArrayList<>());
        final Thread reader;

        Stream(InputStream body) {
            this.body = body;
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    var frame = new StringBuilder();
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!frame.isEmpty()) { received.add(frame.toString()); frames.add(frame.toString()); frame.setLength(0); }
                        } else frame.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // The stream was closed.
                } finally { frames.add("EOF"); }
            });
        }

        String next() throws Exception {
            String result = frames.poll(5, TimeUnit.SECONDS);
            assertThat(result).as("SSE frame within five seconds").isNotNull();
            return result;
        }

        void none() throws Exception { assertThat(frames.poll(300, TimeUnit.MILLISECONDS)).isNull(); }

        public void close() {
            try { body.close(); reader.join(500); } catch (IOException | InterruptedException ignored) { /* closing */ }
        }
    }
}
