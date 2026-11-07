package com.openstudy.modules.workforce;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.openstudy.modules.workforce.repository.AttendanceRecordRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * The guard and the employee row serialize clock-ins, so a real race never reaches uk_attendance_records_1 (see
 * {@link AttendanceConcurrencyIntegrationTests}). The locked check is blinded once here to force the insert into the
 * key, the path a clock-in would take if it ever skipped those locks: the answer is the check's 409, not a 500, and
 * nothing of the command stays.
 */
class AttendanceDuplicateKeyIntegrationTests extends ShiftIntegrationSupport {
    @MockitoSpyBean AttendanceRecordRepository records;

    @Test void aClockInThatGetsPastTheCheckIsRefusedByTheUniqueKeyAndRolledBack() throws Exception {
        try (var two = login(worker)) {
            long kept = created(clockIn(two, first)).get("id").asLong();
            Mockito.doReturn(Optional.empty()).when(records).lockedCurrent(anyLong());
            String key = key();
            try {
                refused(clockIn(two, second, key), 409, "ATTENDANCE_ALREADY_WORKING");
            } finally {
                // Back to the repository itself: the spy delegates again once its stubbing is gone.
                Mockito.reset(records);
            }
            assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", worker)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND idempotency_key=?", worker, key)).isZero();
            assertThat(current(two).get("id").asLong()).isEqualTo(kept);
            // Nothing was stored under the key, so it starts a real clock-in once the first attendance is closed.
            ok(clockOut(two, 1));
            assertThat(created(clockIn(two, second, key)).get("branchId").asString()).isEqualTo(Long.toString(second));
        }
    }
}
