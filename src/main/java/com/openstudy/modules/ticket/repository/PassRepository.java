package com.openstudy.modules.ticket.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.ticket.entity.Pass;
import com.openstudy.modules.ticket.entity.PassStatus;

public interface PassRepository extends JpaRepository<Pass, Long> {
    List<Pass> findByPurchasePaymentIdIn(Collection<Long> paymentIds);

    Optional<Pass> findByPurchasePaymentId(long paymentId);

    List<Pass> findByCustomerIdInAndStatusOrderByIdAsc(Collection<Long> customerIds, PassStatus status);

    List<Pass> findByCustomerIdAndPurchaseBranchIdInOrderByIdAsc(long customerId, Collection<Long> purchaseBranchIds);

    List<Pass> findByCustomerIdAndStatusInOrderByIdDesc(long customerId, Collection<PassStatus> statuses);

    /** The customer's passes and those of the customers merged into it (M13-D07, #114). */
    @Query("SELECT p FROM Pass p WHERE p.customerId IN (SELECT m.id FROM Customer m WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId) AND p.purchaseBranchId IN :branches ORDER BY p.id ASC")
    List<Pass> ofCustomerFamily(@Param("customerId") long customerId, @Param("branches") Collection<Long> branches);

    @Query("SELECT p FROM Pass p WHERE p.customerId IN (SELECT m.id FROM Customer m WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId) AND p.status IN :statuses ORDER BY p.id DESC")
    List<Pass> heldByCustomerFamily(@Param("customerId") long customerId, @Param("statuses") Collection<PassStatus> statuses);

    /**
     * Active passes of these customers and of the customers merged into them, each with the customer it is listed under:
     * the target for an absorbed customer's pass (2026-11-10 user decision, #114).
     */
    @Query("SELECT p, CASE WHEN p.customerId IN :ids THEN p.customerId ELSE c.mergedIntoCustomerId END FROM Pass p, Customer c "
            + "WHERE c.id = p.customerId AND (p.customerId IN :ids OR c.mergedIntoCustomerId IN :ids) AND p.status = :status "
            + "ORDER BY p.id ASC")
    List<Object[]> usableByCustomerFamilies(@Param("ids") Collection<Long> ids, @Param("status") PassStatus status);

    /** The customer a merged customer was merged into, or empty (M13-D07). */
    @Query("SELECT c.mergedIntoCustomerId FROM Customer c WHERE c.id = :id")
    Optional<Long> mergedInto(@Param("id") long id);

    /** The branch alone, so the row is first loaded by {@link #locked} and always holds the current values. */
    @Query("SELECT p.purchaseBranchId FROM Pass p WHERE p.id = :id")
    Optional<Long> purchaseBranchOf(@Param("id") long id);

    /** SELECT ... FOR UPDATE: a locking read returns the latest commit, whatever the transaction's snapshot is. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Pass p WHERE p.id = :id")
    Optional<Pass> locked(@Param("id") long id);

    /** Refund lock, taken after the payment row lock (contract transaction table). JPA-01 native exception. */
    @Query(value = "SELECT id FROM passes WHERE purchase_payment_id = :paymentId FOR UPDATE", nativeQuery = true)
    List<Long> lockByPayment(@Param("paymentId") long paymentId);

    /**
     * Passes with real usage: a seat session, or a USE row in the balance ledger (ADR-M4-002 "미사용·만료 판정").
     * Usage is judged from history, never from the stored status. JPA-01 native exception: seat_sessions
     * belongs to the seat module, whose entities this module must not reach.
     */
    @Query(value = "SELECT p.id FROM passes p WHERE p.id IN (:ids) AND (EXISTS (SELECT 1 FROM seat_sessions s WHERE s.pass_id = p.id) "
            + "OR EXISTS (SELECT 1 FROM pass_balance_events e WHERE e.pass_id = p.id AND e.kind = 'USE'))", nativeQuery = true)
    List<Number> usedIds(@Param("ids") Collection<Long> ids);
}
