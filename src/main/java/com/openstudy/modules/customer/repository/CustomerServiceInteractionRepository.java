package com.openstudy.modules.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.customer.entity.CustomerServiceInteraction;

public interface CustomerServiceInteractionRepository extends JpaRepository<CustomerServiceInteraction, Long> {
    /** Whether this employee found this customer by exact match at this branch; all three must match. */
    boolean existsByCustomerIdAndBranchIdAndEmployeeId(long customerId, long branchId, long employeeId);
}
