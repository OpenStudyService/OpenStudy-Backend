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
    private final EntityManager entityManager;

    /** When each seat of the branch was registered. */
    public List<Instant> seatsRegistered(long branch) {
        var result = new ArrayList<Instant>();
        for (Object value : entityManager.createNativeQuery("SELECT DATE_FORMAT(created_at, " + UTC + ") FROM seats WHERE branch_id = ?")
                .setParameter(1, branch).getResultList())
            result.add(Instant.parse(value.toString()));
        return result;
    }

    /** The stays of the branch that meet [from, to): effective check-in and check-out (null while in progress). */
    @SuppressWarnings("unchecked")
    public List<Instant[]> stays(long branch, Instant from, Instant to) {
        var result = new ArrayList<Instant[]>();
        List<Object[]> rows = entityManager.createNativeQuery("SELECT DATE_FORMAT(effective_checked_in_at, " + UTC + "), "
                + "DATE_FORMAT(effective_checked_out_at, " + UTC + ") FROM seat_sessions WHERE branch_id = ? "
                + "AND effective_checked_in_at < ? AND (effective_checked_out_at IS NULL OR effective_checked_out_at > ?)")
                .setParameter(1, branch).setParameter(2, to).setParameter(3, from).getResultList();
        for (Object[] row : rows)
            result.add(new Instant[]{Instant.parse(row[0].toString()), row[1] == null ? null : Instant.parse(row[1].toString())});
        return result;
    }
}
