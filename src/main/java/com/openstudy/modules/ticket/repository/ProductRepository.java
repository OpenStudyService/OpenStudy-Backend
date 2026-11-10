package com.openstudy.modules.ticket.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.ticket.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
    @EntityGraph(attributePaths = {"category", "branchIds"})
    List<Product> findAllWithDetailsByOrderByIdAsc();

    @EntityGraph(attributePaths = {"category", "branchIds"})
    Optional<Product> findWithDetailsById(long id);

    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks and before child rows.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM products WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /** Shared lock for a purchase transaction: a concurrent stop or branch change waits for the sale to finish. */
    @Query(value = "SELECT id FROM products WHERE id = :id FOR SHARE", nativeQuery = true)
    List<Long> lockShared(@Param("id") long id);

    /** The limit decides the sale's lock mode before any product lock is taken (M13-D02). */
    @Query("SELECT p.salesLimit FROM Product p WHERE p.id = :id")
    java.util.Optional<Integer> salesLimitOf(@Param("id") long id);

    /**
     * Approved (not refunded, not failed) payments of a product over every branch: the sold quantity of the limit
     * (M13-D02). Read by entity name so the ticket module does not depend on the payment module.
     */
    @Query("SELECT COUNT(p) FROM Payment p WHERE p.productId = :id AND CAST(p.status AS string) = 'APPROVED'")
    long soldQuantity(@Param("id") long id);

    @Query("SELECT p.productId, COUNT(p) FROM Payment p WHERE p.productId IN :ids AND CAST(p.status AS string) = 'APPROVED' "
            + "GROUP BY p.productId")
    List<Object[]> soldQuantities(@Param("ids") java.util.Collection<Long> ids);

    /** Advances an otherwise unchanged product's version; the caller refreshes the managed entity afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.version = p.version + 1 WHERE p.id = :id")
    int incrementVersion(@Param("id") long id);
}
