package com.openstudy.modules.workforce;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real simultaneous clock commands: one attendance in progress per employee, one winner among commands on the same
 * attendance, a clock-in that waited for a row seeing what committed meanwhile (a cancelled shift is not linked), and
 * clock commands crossing shift and task commands without a deadlock.
 */
class AttendanceConcurrencyIntegrationTests extends ShiftIntegrationSupport {
    static final Set<String> COMMAND_CONFLICTS = Set.of("ATTENDANCE_ALREADY_WORKING", "ATTENDANCE_NOT_WORKING", "ATTENDANCE_ON_BREAK",
            "ATTENDANCE_NOT_ON_BREAK", "VERSION_CONFLICT", "SHIFT_CONFLICT");
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void cleanTasks() {
        for (long id : branches) {
            jdbc.update("DELETE e FROM task_events e JOIN work_tasks t ON t.id=e.task_id WHERE t.branch_id=?", id);
            jdbc.update("DELETE i FROM task_checklist_items i JOIN work_tasks t ON t.id=i.task_id WHERE t.branch_id=?", id);
            jdbc.update("DELETE FROM work_tasks WHERE branch_id=?", id);
        }
    }

    /** Closes whatever the person has in progress, through the API. */
    void closeUp(Session session) throws Exception {
        var open = current(session);
        if (open.isNull()) return;
        long version = open.get("version").asLong();
        if ("휴게 중".equals(open.get("status").asString())) version = ok(breakEnd(session, version)).get("version").asLong();
        ok(clockOut(session, version));
    }

    @Test void simultaneousClockInsLeaveExactlyOneAttendanceInProgress() throws Exception {
        try (var one = login(worker); var two = login(worker)) {
            for (int round = 0; round < 3; round++) {
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    var session = index % 2 == 0 ? one : two;
                    long branch = index % 3 == 0 ? second : first;
                    calls.add(() -> clockIn(session, branch));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(code -> code == 201).count()).as(statuses(responses).toString()).isEqualTo(1);
                for (var response : responses) if (response.statusCode() != 201) refused(response, 409, "ATTENDANCE_ALREADY_WORKING");
                assertThat(working(worker)).isEqualTo(1);
                closeUp(one);
                assertThat(working(worker)).isZero();
            }
            assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", worker)).isEqualTo(3);
        }
    }

    @Test void simultaneousBreaksAndClockOutsOnOneAttendanceLetExactlyOneThrough() throws Exception {
        try (var one = login(staff); var two = login(staff)) {
            for (int round = 0; round < 4; round++) {
                long id = created(clockIn(one, first)).get("id").asLong();
                var responses = race(List.<Callable<HttpResponse<String>>>of(() -> breakStart(one, 1), () -> breakStart(two, 1), () -> clockOut(one, 1), () -> clockOut(two, 1)));
                assertThat(statuses(responses).stream().filter(code -> code == 200).count()).as(statuses(responses).toString()).isEqualTo(1);
                for (var response : responses) if (response.statusCode() != 200) {
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
                    assertThat(node(response).get("code").asString()).as(response.body()).isIn(COMMAND_CONFLICTS);
                }
                long open = count("SELECT COUNT(*) FROM attendance_breaks WHERE attendance_id=? AND ended_at IS NULL", id);
                boolean onBreak = responses.get(0).statusCode() == 200 || responses.get(1).statusCode() == 200;
                assertThat(open).isEqualTo(onBreak ? 1 : 0);
                assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE id=? AND status=?", id, onBreak ? "WORKING" : "LEFT")).isEqualTo(1);
                if (onBreak) {
                    var ends = race(List.<Callable<HttpResponse<String>>>of(() -> breakEnd(one, 2), () -> breakEnd(two, 2), () -> breakEnd(one, 2)));
                    assertThat(statuses(ends).stream().filter(code -> code == 200).count()).as(statuses(ends).toString()).isEqualTo(1);
                    assertThat(count("SELECT COUNT(*) FROM attendance_breaks WHERE attendance_id=? AND ended_at IS NULL", id)).isZero();
                    assertThat(count("SELECT COUNT(*) FROM attendance_breaks WHERE attendance_id=?", id)).isEqualTo(1);
                    ok(clockOut(one, 3));
                }
                assertThat(working(staff)).isZero();
            }
        }
    }

    /**
     * Without the guard, a writer holds the employee row and records an attendance in progress at the other branch. The
     * clock-in waits, then sees that attendance by its locking read and answers the contract's 409, not the unique key.
     */
    @Test void aClockInWaitingForTheEmployeeRowSeesTheAttendanceCommittedMeanwhile() throws Exception {
        try (var two = login(worker)) {
            var inside = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writer = Executors.newSingleThreadExecutor();
            var template = new TransactionTemplate(transactions);
            var wrote = writer.submit(() -> template.execute(status -> {
                jdbc.queryForList("SELECT id FROM employees WHERE id=? FOR UPDATE", worker);
                jdbc.update("INSERT INTO attendance_records(employee_id,branch_id,clock_in_at,status) VALUES(?,?,UTC_TIMESTAMP(),'WORKING')", worker, second);
                inside.countDown();
                try { release.await(); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                return 1;
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            var request = Executors.newSingleThreadExecutor().submit(() -> clockIn(two, first));
            waitForLockWait();
            assertThat(request.isDone()).as("the clock-in waits for the employee row").isFalse();
            release.countDown();
            wrote.get(30, TimeUnit.SECONDS);
            refused(request.get(60, TimeUnit.SECONDS), 409, "ATTENDANCE_ALREADY_WORKING");
            assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", worker)).isEqualTo(1);
            assertThat(current(two).get("branchId").asString()).isEqualTo(Long.toString(second));
            writer.shutdown();
        }
    }

    /**
     * The shift is read FOR SHARE: a clock-in that meets a cancellation still being committed waits for it and then does
     * not link the cancelled shift; if that cancellation rolls back instead, the shift is linked.
     */
    @Test void aClockInWaitingOnACancellationNeverLinksTheCancelledShift() throws Exception {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofHours(1));
        try (var lead = login(manager); var own = login(staff)) {
            for (boolean commits : List.of(true, false)) {
                var body = Map.<String, Object>of("branchId", Long.toString(first), "employeeId", Long.toString(staff),
                        "startsAt", start.toString(), "endsAt", start.plus(Duration.ofHours(3)).toString());
                long shift = created(register(lead, body)).get("id").asLong();
                var inside = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var writer = Executors.newSingleThreadExecutor();
                var template = new TransactionTemplate(transactions);
                var wrote = writer.submit(() -> template.execute(status -> {
                    jdbc.queryForList("SELECT id FROM shifts WHERE id=? FOR UPDATE", shift);
                    jdbc.update("UPDATE shifts SET status='CANCELLED', version=version+1 WHERE id=?", shift);
                    inside.countDown();
                    try { release.await(); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                    if (!commits) status.setRollbackOnly();
                    return 1;
                }));
                assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
                var request = Executors.newSingleThreadExecutor().submit(() -> clockIn(own, first));
                waitForLockWait();
                assertThat(request.isDone()).as("the clock-in waits for the shift row").isFalse();
                release.countDown();
                wrote.get(30, TimeUnit.SECONDS);
                var made = created(request.get(60, TimeUnit.SECONDS));
                if (commits) assertThat(made.get("shiftId").isNull()).as("the cancellation committed first").isTrue();
                else assertThat(made.get("shiftId").asString()).as("the cancellation rolled back").isEqualTo(Long.toString(shift));
                closeUp(own);
                if (!commits) assertThat(count("SELECT COUNT(*) FROM shifts WHERE id=? AND status='SCHEDULED'", shift)).isEqualTo(1);
                writer.shutdown();
            }
        }
    }

    /**
     * Clock-ins, breaks and clock-outs of several people, shift registrations for the same people at both branches and a
     * task, all at once: every answer is one of the contract, never a deadlock's 500, and nobody has two attendances
     * in progress or two open breaks.
     */
    @Test void clockCommandsCrossingShiftAndTaskCommandsDoNotDeadlock() throws Exception {
        try (var own = login(staff); var peer = login(colleague); var two = login(worker); var twoAgain = login(worker);
                var lead = login(manager); var other = login(secondManager); var boss = login(owner)) {
            for (int round = 0; round < 3; round++) {
                String day = "2026-12-1" + round;
                created(clockIn(own, first));
                created(clockIn(peer, first));
                ok(breakStart(peer, 1));
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                calls.add(() -> clockIn(two, first));
                calls.add(() -> clockIn(twoAgain, second));
                calls.add(() -> breakStart(own, 1));
                calls.add(() -> clockOut(own, 1));
                calls.add(() -> breakEnd(peer, 2));
                calls.add(() -> clockOut(peer, 2));
                calls.add(() -> clockIn(lead, first));
                calls.add(() -> register(lead, shift(first, staff, day + " 09:00", day + " 18:00")));
                calls.add(() -> register(other, shift(second, worker, day + " 09:00", day + " 18:00")));
                calls.add(() -> register(boss, shift(first, worker, day + " 12:00", day + " 20:00")));
                calls.add(() -> http(lead.client(), "POST", "/api/tasks", lead.token(), Map.of("branchId", Long.toString(first), "title", "출근 확인",
                        "assigneeId", Long.toString(staff), "category", "근무"), key()));
                var responses = race(calls);
                for (var response : responses) {
                    assertThat(response.statusCode()).as(response.body()).isIn(200, 201, 409);
                    if (response.statusCode() == 409) assertThat(node(response).get("code").asString()).as(response.body()).isIn(COMMAND_CONFLICTS);
                }
                assertThat(statuses(responses.subList(0, 2))).as("one clock-in of the worker").containsExactlyInAnyOrder(201, 409);
                assertThat(responses.get(6).statusCode()).isEqualTo(201);
                assertThat(responses.get(7).statusCode()).isEqualTo(201);
                assertThat(responses.get(10).statusCode()).isEqualTo(201);
                for (long person : List.of(staff, colleague, worker, manager)) {
                    assertThat(working(person)).isLessThanOrEqualTo(1);
                    assertThat(count("SELECT COUNT(*) FROM attendance_breaks b JOIN attendance_records a ON a.id=b.attendance_id "
                            + "WHERE a.employee_id=? AND b.ended_at IS NULL", person)).isLessThanOrEqualTo(1);
                }
                for (var session : List.of(own, peer, two, lead)) closeUp(session);
            }
        }
    }

    /** Until the request is really blocked on a row. Without the privilege to see that, a pause does the same job. */
    void waitForLockWait() throws Exception {
        Instant limit = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(limit)) {
            try {
                var waiting = jdbc.queryForList("SELECT trx_query FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'", String.class);
                if (!waiting.isEmpty()) {
                    System.out.println("[attendance-lock-wait] " + waiting);
                    return;
                }
            } catch (RuntimeException exception) {
                System.out.println("[attendance-lock-wait] innodb_trx is not readable here; pausing instead");
                Thread.sleep(1500);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The request never waited for the row");
    }
}
