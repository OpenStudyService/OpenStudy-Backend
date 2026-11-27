package com.openstudy.modules.seat.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** The rows behind the occupancy trend (#116, M13-D09), read as UTC text so no driver time zone shifts a DATETIME(6). */
@Repository
@RequiredArgsConstructor
public class SeatOccupancyRepository {

    private static final String UTC = "'%Y-%m-%dT%H:%i:%s.%fZ'";
    private static final String SEATS_SQL = "SELECT DATE_FORMAT(created_at, " + UTC + ")"
            + " FROM seats WHERE branch_id = ?";
    private static final String STAYS_SQL = "SELECT DATE_FORMAT(effective_checked_in_at, " + UTC + "), "
            + "DATE_FORMAT(effective_checked_out_at, " + UTC + ") FROM seat_sessions WHERE branch_id = ? "
            + "AND effective_checked_in_at < ? AND (effective_checked_out_at IS NULL OR effective_checked_out_at > ?)";

    private final EntityManager entityManager;

    /** When each seat of the branch was registered. */
    public List<Instant> findSeatRegistrations(long branch) {
        List<Instant> result = new ArrayList<>();
        List<?> values = entityManager.createNativeQuery(SEATS_SQL)
                .setParameter(1, branch)
                .getResultList();

        for (Object value : values) {
            result.add(Instant.parse(value.toString()));
        }

        return result;
    }

    /** The stays of the branch that meet [from, to): effective check-in and check-out (null while in progress). */
    @SuppressWarnings("unchecked")
    public List<Instant[]> findStays(long branch, Instant from, Instant to) {
        List<Instant[]> result = new ArrayList<>();
        List<Object[]> rows = entityManager.createNativeQuery(STAYS_SQL)
                .setParameter(1, branch)
                .setParameter(2, to)
                .setParameter(3, from)
                .getResultList();

        for (Object[] row : rows) {
            Instant checkedIn = Instant.parse(row[0].toString());
            Instant checkedOut = row[1] == null ? null : Instant.parse(row[1].toString());
            result.add(new Instant[]{checkedIn, checkedOut});
        }

        return result;
    }
}
