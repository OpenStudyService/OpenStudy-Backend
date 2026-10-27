package com.openstudy.modules.ticket.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.openstudy.modules.ticket.entity.ProductCategory;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
    List<ProductCategory> findAllByOrderByIdAsc();

    /** Serialize category edits so an update never races a product assignment to the same category. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProductCategory> findForUpdateById(long id);

    boolean existsByCode(String code);
}
