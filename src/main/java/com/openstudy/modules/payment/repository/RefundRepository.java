package com.openstudy.modules.payment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.payment.entity.Refund;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findByPaymentIdIn(Collection<Long> paymentIds);

    /**
     * The adjustment of a closed day (M13-D04·D11, #115): when the refunded payment was approved on a day its branch has
     * closed, one row joins that closing. Written by table so the payment module does not depend on the sales module;
     * the refund's reason is not copied. The branch rows are locked by every command, so a closing and a refund of the
     * same branch never pass each other.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "INSERT INTO daily_closing_adjustments(closing_id, refund_id, payment_id, "
            + "amount_krw, processed_by, processed_at) SELECT c.id, r.id, r.payment_id, r.amount_krw, r.processed_by, r.refunded_at "
            + "FROM daily_closings c JOIN refunds r ON r.id = :refund WHERE c.branch_id = :branch AND c.business_date = :day",
            nativeQuery = true)
    int recordClosingAdjustment(@org.springframework.data.repository.query.Param("refund") long refund,
            @org.springframework.data.repository.query.Param("branch") long branch,
            @org.springframework.data.repository.query.Param("day") java.time.LocalDate day);
}
