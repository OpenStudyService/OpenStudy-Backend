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

    /** Advances an otherwise unchanged product's version; the caller refreshes the managed entity afterwards. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.version = p.version + 1 WHERE p.id = :id")
    int incrementVersion(@Param("id") long id);
}
