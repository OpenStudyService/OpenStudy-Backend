package com.openstudy.modules.payment.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.payment.entity.Refund;

public interface RefundRepository extends JpaRepository<Refund, Long> {
    List<Refund> findByPaymentIdIn(Collection<Long> paymentIds);
}
