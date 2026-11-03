package com.openstudy.modules.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.customer.entity.CustomerInteractionSource;
import com.openstudy.modules.customer.entity.CustomerServiceInteraction;

public interface CustomerServiceInteractionRepository extends JpaRepository<CustomerServiceInteraction, Long> {
    /** Whether this employee left a row of this source for this customer at this branch; all four must match. */
    boolean existsByCustomerIdAndBranchIdAndEmployeeIdAndSource(long customerId, long branchId, long employeeId,
            CustomerInteractionSource source);
}
