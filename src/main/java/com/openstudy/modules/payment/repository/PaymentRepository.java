package com.openstudy.modules.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {}
