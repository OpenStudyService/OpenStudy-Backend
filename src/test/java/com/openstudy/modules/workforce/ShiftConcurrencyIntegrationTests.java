package com.openstudy.modules.workforce;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * Real simultaneous requests: one employee's overlapping shifts are refused across branches, a request waiting for the
 * employee row sees what committed meanwhile, and crossing writes and other commands do not deadlock.
 */
class ShiftConcurrencyIntegrationTests extends ShiftIntegrationSupport {
    @Autowired PlatformTransactionManager transactions;

    @AfterEach void cleanTasks() {
        for (long id : branches) {
            jdbc.update("DELETE e FROM task_events e JOIN work_tasks t ON t.id=e.task_id WHERE t.branch_id=?", id);
            jdbc.update("DELETE i FROM task_checklist_items i JOIN work_tasks t ON t.id=i.task_id WHERE t.branch_id=?", id);
            jdbc.update("DELETE FROM work_tasks WHERE branch_id=?", id);
        }
    }

    /** One side tends to win an even start, so every other round gives the second call a head start. Answers stay (one, two). */
    List<HttpResponse<String>> raceInTurns(int round, Callable<HttpResponse<String>> one, Callable<HttpResponse<String>> two) throws Exception {
        if (round % 2 == 0) return race(List.of(one, two));
        return race(List.of(() -> { Thread.sleep(40); return one.call(); }, two));
    }

    @Test void onlyOneOfManySimultaneousOverlappingShiftsIsSaved() throws Exception {
        try (var lead = login(manager); var boss = login(owner)) {
            for (int round = 0; round < 3; round++) {
                String day = "2026-11-1" + round;
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                for (int index = 0; index < 8; index++) {
                    var session = index % 2 == 0 ? lead : boss;
                    // Shifted starts, so the shifts overlap without being the same request.
                    String start = day + " 0" + (index % 3 + 7) + ":00";
                    calls.add(() -> register(session, shift(first, staff, start, day + " 18:00")));
                }
                var responses = race(calls);
                assertThat(statuses(responses).stream().filter(code -> code == 201).count()).as(statuses(responses).toString()).isEqualTo(1);
                for (var response : responses) if (response.statusCode() != 201) refused(response, 409, "SHIFT_CONFLICT");
                assertThat(scheduled(staff)).isEqualTo(round + 1);
            }
        }
    }

    @Test void managersOfTwoBranchesCannotBothGiveOneEmployeeTheSameHours() throws Exception {
        try (var lead = login(manager); var other = login(secondManager)) {
            int firstWins = 0;
            for (int round = 0; round < 6; round++) {
                String day = "2026-11-" + (10 + round);
                var responses = raceInTurns(round,
                        () -> register(lead, shift(first, worker, day + " 09:00", day + " 18:00")),
                        () -> register(other, shift(second, worker, day + " 12:00", day + " 22:00")));
                assertThat(statuses(responses)).as(responses.toString()).containsExactlyInAnyOrder(201, 409);
                var loser = responses.get(0).statusCode() == 409 ? responses.get(0) : responses.get(1);
                refused(loser, 409, "SHIFT_CONFLICT");
                // Each manager sees the winner only as an overlap at another branch.
                assertThat(node(loser).at("/conflicts/0/label").asString()).isEqualTo("다른 지점 근무");
                if (responses.get(0).statusCode() == 201) firstWins++;
                assertThat(count("SELECT COUNT(*) FROM shifts WHERE employee_id=? AND starts_at < ? AND ends_at > ?", worker,
                        sql(day + " 22:00"), sql(day + " 09:00"))).isEqualTo(1);
            }
            System.out.println("[shift-race] first branch won " + firstWins + " of 6");
        }
    }

    @Test void aRegistrationWaitingForTheEmployeeRowSeesTheShiftCommittedMeanwhile() throws Exception {
        try (var lead = login(manager)) {
            var inside = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            // A writer that holds the employee row, as a shift command at the other branch does, and commits once the request waits.
            var writer = Executors.newSingleThreadExecutor();
            var template = new TransactionTemplate(transactions);
            var wrote = writer.submit(() -> template.execute(status -> {
                jdbc.queryForList("SELECT id FROM employees WHERE id=? FOR UPDATE", worker);
                jdbc.update("INSERT INTO shifts(employee_id,branch_id,starts_at,ends_at,created_by) VALUES(?,?,?,?,?)",
                        worker, second, sql("2026-11-04 10:00"), sql("2026-11-04 12:00"), secondManager);
                inside.countDown();
                try { release.await(); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                return 1;
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            var request = Executors.newSingleThreadExecutor().submit(() -> register(lead, shift(first, worker, "2026-11-04 11:00", "2026-11-04 13:00")));
            waitForLockWait();
            assertThat(request.isDone()).as("the request waits for the employee row").isFalse();
            release.countDown();
            wrote.get(30, TimeUnit.SECONDS);
            var response = request.get(60, TimeUnit.SECONDS);
            refused(response, 409, "SHIFT_CONFLICT");
            assertThat(node(response).at("/conflicts/0/overlapStartsAt").asString()).isEqualTo(at("2026-11-04 11:00"));
            assertThat(node(response).at("/conflicts/0/overlapEndsAt").asString()).isEqualTo(at("2026-11-04 12:00"));
            assertThat(shifts(worker)).isEqualTo(1);
            writer.shutdown();
        }
    }

    @Test void aChangeWaitingForTheEmployeeRowSeesTheShiftCommittedMeanwhile() throws Exception {
        try (var lead = login(manager)) {
            long id = registered(lead, shift(first, worker, "2026-11-04 08:00", "2026-11-04 10:00"));
            var inside = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writer = Executors.newSingleThreadExecutor();
            var template = new TransactionTemplate(transactions);
            var wrote = writer.submit(() -> template.execute(status -> {
                jdbc.queryForList("SELECT id FROM employees WHERE id=? FOR UPDATE", worker);
                jdbc.update("INSERT INTO shifts(employee_id,branch_id,starts_at,ends_at,created_by) VALUES(?,?,?,?,?)",
                        worker, second, sql("2026-11-04 11:00"), sql("2026-11-04 13:00"), secondManager);
                inside.countDown();
                try { release.await(); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                return 1;
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            var request = Executors.newSingleThreadExecutor().submit(() -> edit(lead, id, change(1, "endsAt", at("2026-11-04 12:00"))));
            waitForLockWait();
            release.countDown();
            wrote.get(30, TimeUnit.SECONDS);
            refused(request.get(60, TimeUnit.SECONDS), 409, "SHIFT_CONFLICT");
            assertThat(count("SELECT version FROM shifts WHERE id=?", id)).isZero();
            writer.shutdown();
        }
    }

    /**
     * Two managers planning each other, changes, cancellations, a task at the same branch and registrations at both branches,
     * all at once: every request ends with an answer of the contract, none with a deadlock's 500, and no employee is left
     * with overlapping shifts.
     */
    @Test void crossingShiftWritesAndOtherCommandsDoNotDeadlock() throws Exception {
        long deputy = person("EMPLOYEE", "첫 지점 부매니저");
        member(deputy, first, "MANAGER");
        member(manager, second, "STAFF");
        try (var lead = login(manager); var vice = login(deputy); var other = login(secondManager); var boss = login(owner)) {
            for (int round = 0; round < 3; round++) {
                String day = "2026-11-2" + round;
                long mine = registered(lead, shift(first, deputy, day + " 06:00", day + " 08:00"));
                long theirs = registered(vice, shift(first, manager, day + " 06:00", day + " 08:00"));
                var calls = new ArrayList<Callable<HttpResponse<String>>>();
                calls.add(() -> register(lead, shift(first, deputy, day + " 09:00", day + " 18:00")));
                calls.add(() -> register(vice, shift(first, manager, day + " 09:00", day + " 18:00")));
                calls.add(() -> register(other, shift(second, manager, day + " 12:00", day + " 20:00")));
                calls.add(() -> register(boss, shift(first, deputy, day + " 13:00", day + " 20:00")));
                calls.add(() -> edit(vice, mine, change(1, "endsAt", at(day + " 09:30"))));
                calls.add(() -> cancel(lead, theirs, 1));
                calls.add(() -> register(other, shift(second, worker, day + " 09:00", day + " 18:00")));
                calls.add(() -> register(lead, shift(first, worker, day + " 10:00", day + " 19:00")));
                calls.add(() -> http(lead.client(), "POST", "/api/tasks", lead.token(), Map.of("branchId", Long.toString(first), "title", "교대 확인",
                        "assigneeId", Long.toString(deputy), "category", "근무"), key()));
                var responses = race(calls);
                for (var response : responses)
                    assertThat(response.statusCode()).as(response.body()).isIn(200, 201, 409);
                for (var response : responses) if (response.statusCode() == 409)
                    assertThat(node(response).get("code").asString()).as(response.body()).isEqualTo("SHIFT_CONFLICT");
                assertThat(responses.get(8).statusCode()).isEqualTo(201);
                assertThat(responses.get(5).statusCode()).as("nothing else touches that shift").isEqualTo(200);
            }
            assertThat(count("SELECT COUNT(*) FROM shifts a JOIN shifts b ON a.employee_id=b.employee_id AND a.id<b.id "
                    + "WHERE a.status='SCHEDULED' AND b.status='SCHEDULED' AND a.starts_at<b.ends_at AND b.starts_at<a.ends_at "
                    + "AND a.employee_id IN (?,?,?)", manager, deputy, worker)).isZero();
        }
    }

    /** Until the request is really blocked on a row. Without the privilege to see that, a pause does the same job. */
    void waitForLockWait() throws Exception {
        Instant limit = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(limit)) {
            try {
                var waiting = jdbc.queryForList("SELECT trx_query FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'", String.class);
                if (!waiting.isEmpty()) {
                    // Today the foundation guard locks every employee row first, so this names that statement, not the shift's own.
                    System.out.println("[shift-lock-wait] " + waiting);
                    return;
                }
            } catch (RuntimeException exception) {
                System.out.println("[shift-lock-wait] innodb_trx is not readable here; pausing instead");
                Thread.sleep(1500);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("The request never waited for the employee row");
    }
}
