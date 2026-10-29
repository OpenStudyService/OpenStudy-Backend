package com.openstudy.modules.customer.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.customer.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks and before child rows.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM customers WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /**
     * Branches where the customer has paid (any payment status), which widen the lookup scope beyond the registration
     * branch (M4-D05). Read from the table until the payment module publishes an entry point for it (#57).
     */
    @Query(value = "SELECT DISTINCT branch_id FROM payments WHERE customer_id = :id ORDER BY branch_id", nativeQuery = true)
    List<Number> paymentBranchIds(@Param("id") long id);
}
