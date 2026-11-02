package com.openstudy.modules.reservation.repository;

import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.reservation.entity.Room;
import com.openstudy.modules.reservation.entity.RoomState;

/** Branch-scoped room reads in the contract's fixed order (roomNo, id). */
@Repository
@RequiredArgsConstructor
public class RoomSearchRepository {
    private final EntityManager entityManager;

    public record Page(List<Room> rows, long total) {}

    /** A null status means "no restriction". */
    public Page find(Set<Long> branches, RoomState status, long offset, int limit) {
        if (branches.isEmpty()) return new Page(List.of(), 0);
        String where = " FROM Room r WHERE r.branchId IN :branches" + (status == null ? "" : " AND r.status = :status");
        var rows = entityManager.createQuery("SELECT r" + where + " ORDER BY r.roomNo ASC, r.id ASC", Room.class);
        var count = entityManager.createQuery("SELECT COUNT(r)" + where, Long.class);
        rows.setParameter("branches", branches);
        count.setParameter("branches", branches);
        if (status != null) {
            rows.setParameter("status", status);
            count.setParameter("status", status);
        }
        // The API allows any page; JPA offsets are ints, and no branch holds that many rooms.
        var found = offset > Integer.MAX_VALUE ? List.<Room>of() : rows.setFirstResult((int) offset).setMaxResults(limit).getResultList();
        return new Page(found, count.getSingleResult());
    }
}
