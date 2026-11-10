package com.openstudy.modules.sales.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.sales.entity.DailyClosing;

/**
 * Daily closings and the reads around them (#115). Adjustments are written by the refund's transaction in the payment
 * module and read here by table; employee names are read by entity name, so no other module's class is used.
 */
public interface DailyClosingRepository extends JpaRepository<DailyClosing, Long> {
    Optional<DailyClosing> findByBranchIdAndBusinessDate(long branchId, LocalDate businessDate);

    @Query("SELECT c FROM DailyClosing c WHERE c.branchId IN :branches AND c.businessDate >= :from AND c.businessDate <= :to "
            + "ORDER BY c.businessDate DESC, c.branchId ASC")
    List<DailyClosing> between(@Param("branches") Collection<Long> branches, @Param("from") LocalDate from, @Param("to") LocalDate to,
            Pageable page);

    @Query("SELECT COUNT(c) FROM DailyClosing c WHERE c.branchId IN :branches AND c.businessDate >= :from AND c.businessDate <= :to")
    long countBetween(@Param("branches") Collection<Long> branches, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Adjustments of a closing in processing order: id, refund, payment, amount, processor, time, the refund's reason. */
    @Query(value = "SELECT a.id, a.refund_id, a.payment_id, a.amount_krw, a.processed_by, "
            + "DATE_FORMAT(a.processed_at, '%Y-%m-%dT%H:%i:%s.%fZ'), r.reason FROM daily_closing_adjustments a "
            + "JOIN refunds r ON r.id = a.refund_id WHERE a.closing_id = :closing ORDER BY a.processed_at ASC, a.id ASC", nativeQuery = true)
    List<Object[]> adjustments(@Param("closing") long closing);

    @Query(value = "SELECT closing_id, COUNT(*) FROM daily_closing_adjustments WHERE closing_id IN (:ids) GROUP BY closing_id",
            nativeQuery = true)
    List<Object[]> adjustmentCounts(@Param("ids") Collection<Long> ids);

    /** Approved amount and count per payment method of a branch's approvals in [from, to). */
    @Query(value = "SELECT method, COALESCE(SUM(amount_krw), 0), COUNT(*) FROM payments WHERE branch_id = :branch "
            + "AND approved_at >= :from AND approved_at < :to GROUP BY method", nativeQuery = true)
    List<Object[]> approvalsByMethod(@Param("branch") long branch, @Param("from") Instant from, @Param("to") Instant to);

    /** Refunds per original payment method, by refund time (processedDate). */
    @Query(value = "SELECT p.method, COALESCE(SUM(r.amount_krw), 0), COUNT(*) FROM refunds r JOIN payments p ON p.id = r.payment_id "
            + "WHERE p.branch_id = :branch AND r.refunded_at >= :from AND r.refunded_at < :to GROUP BY p.method", nativeQuery = true)
    List<Object[]> refundsByMethodProcessed(@Param("branch") long branch, @Param("from") Instant from, @Param("to") Instant to);

    /** Refunds per original payment method, by the original approval time (paymentDate). */
    @Query(value = "SELECT p.method, COALESCE(SUM(r.amount_krw), 0), COUNT(*) FROM refunds r JOIN payments p ON p.id = r.payment_id "
            + "WHERE p.branch_id = :branch AND p.approved_at >= :from AND p.approved_at < :to GROUP BY p.method", nativeQuery = true)
    List<Object[]> refundsByMethodPaid(@Param("branch") long branch, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT e.id, e.name FROM Employee e WHERE e.id IN :ids")
    List<Object[]> employeeNames(@Param("ids") Collection<Long> ids);
}
