package com.openstudy.modules.customer.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Shared lock for a sale: a concurrent status change waits until the payment commits (M4 transaction table). */
    @Query(value = "SELECT id FROM customers WHERE id = :id FOR SHARE", nativeQuery = true)
    List<Long> lockShared(@Param("id") long id);

    /** The column collation ignores case and accents, so callers still compare the exact value (M5-D05). */
    Optional<Customer> findByMemberNo(String memberNo);

    /** Phone is not unique; the lookup shows at most ten customers in member number order (seat-usage-api.md). */
    List<Customer> findTop10ByPhoneOrderByMemberNoAsc(String phone);

    /**
     * Branches where the customer has paid (any payment status), which widen the lookup scope beyond the registration
     * branch (M4-D05). Read from the table until the payment module publishes an entry point for it (#57).
     */
    @Query(value = "SELECT DISTINCT branch_id FROM payments WHERE customer_id = :id ORDER BY branch_id", nativeQuery = true)
    List<Number> paymentBranchIds(@Param("id") long id);

    /** Customers merged into this one (M13-D07), in ID order: the lock order of a merge. */
    List<Customer> findByMergedIntoCustomerIdOrderByIdAsc(long target);

    long countByMergedIntoCustomerId(long target);

    /** A merge changes what the target shows, so its version moves even though its own row does not change. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Customer c SET c.version = c.version + 1 WHERE c.id = :id")
    int incrementVersion(@Param("id") long id);

    /** Phones shared by two or more customers that are not MERGED, most customers first (M13-D13). */
    @Query(value = "SELECT phone, COUNT(*) AS n FROM customers WHERE status <> 'MERGED' AND phone IS NOT NULL "
            + "GROUP BY phone HAVING COUNT(*) >= 2 ORDER BY n DESC, phone ASC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Object[]> duplicatePhones(@Param("limit") int limit, @Param("offset") long offset);

    @Query(value = "SELECT COUNT(*) FROM (SELECT phone FROM customers WHERE status <> 'MERGED' AND phone IS NOT NULL "
            + "GROUP BY phone HAVING COUNT(*) >= 2) g", nativeQuery = true)
    long countDuplicatePhones();

    @Query("SELECT c FROM Customer c WHERE c.phone IN :phones AND c.status <> com.openstudy.modules.customer.entity.CustomerStatus.MERGED "
            + "ORDER BY c.registeredAt ASC, c.id ASC")
    List<Customer> notMergedWithPhones(@Param("phones") Collection<String> phones);

    /** Payments (any status) per customer. */
    @Query(value = "SELECT customer_id, COUNT(*) FROM payments WHERE customer_id IN (:ids) GROUP BY customer_id", nativeQuery = true)
    List<Object[]> paymentCounts(@Param("ids") Collection<Long> ids);

    /** The latest payment or check-in per customer. */
    @Query(value = "SELECT customer_id, MAX(at) FROM (SELECT customer_id, created_at AS at FROM payments WHERE customer_id IN (:ids) "
            + "UNION ALL SELECT customer_id, checked_in_at AS at FROM seat_sessions WHERE customer_id IN (:ids)) a GROUP BY customer_id",
            nativeQuery = true)
    List<Object[]> lastActivities(@Param("ids") Collection<Long> ids);
}
