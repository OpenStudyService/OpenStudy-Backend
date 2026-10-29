package com.openstudy.modules.ticket.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.ticket.entity.Pass;

public interface PassRepository extends JpaRepository<Pass, Long> {
    List<Pass> findByPurchasePaymentIdIn(Collection<Long> paymentIds);

    /**
     * Passes with real usage: a seat session, or a USE row in the balance ledger (ADR-M4-002 "미사용·만료 판정").
     * Usage is judged from history, never from the stored status. JPA-01 native exception: the seat and ledger
     * modules have no entities yet.
     */
    @Query(value = "SELECT p.id FROM passes p WHERE p.id IN (:ids) AND (EXISTS (SELECT 1 FROM seat_sessions s WHERE s.pass_id = p.id) "
            + "OR EXISTS (SELECT 1 FROM pass_balance_events e WHERE e.pass_id = p.id AND e.kind = 'USE'))", nativeQuery = true)
    List<Number> usedIds(@Param("ids") Collection<Long> ids);
}
