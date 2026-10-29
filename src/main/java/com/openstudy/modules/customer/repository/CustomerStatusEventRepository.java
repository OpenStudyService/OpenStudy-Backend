package com.openstudy.modules.customer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.customer.entity.CustomerStatusEvent;

public interface CustomerStatusEventRepository extends JpaRepository<CustomerStatusEvent, Long> {
    List<CustomerStatusEvent> findByCustomerIdOrderByIdAsc(long customerId);
}
