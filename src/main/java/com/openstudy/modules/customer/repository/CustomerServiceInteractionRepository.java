package com.openstudy.modules.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.customer.entity.CustomerServiceInteraction;

public interface CustomerServiceInteractionRepository extends JpaRepository<CustomerServiceInteraction, Long> {
}
