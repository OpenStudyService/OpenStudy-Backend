package com.openstudy.modules.customer.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.customer.entity.CustomerMerge;

public interface CustomerMergeRepository extends JpaRepository<CustomerMerge, Long> {
    /** Merges where the customer was the target or was absorbed, newest first. */
    @Query("SELECT m FROM CustomerMerge m WHERE m.targetCustomerId = :id OR m.absorbedCustomerId = :id OR m.viaCustomerId = :id "
            + "ORDER BY m.mergedAt DESC, m.id DESC")
    List<CustomerMerge> involving(@Param("id") long id);

    /** Employee names by entity name, so the customer module does not depend on the employee module. */
    @Query("SELECT e.id, e.name FROM Employee e WHERE e.id IN :ids")
    List<Object[]> employeeNames(@Param("ids") Collection<Long> ids);
}
