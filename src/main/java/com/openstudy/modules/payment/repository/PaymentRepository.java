package com.openstudy.modules.payment.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    /**
     * Row lock for a refund, taken after the idempotency lock and before the pass lock (contract transaction table).
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM payments WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);
}
