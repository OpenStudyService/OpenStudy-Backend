package com.openstudy.modules.ticket.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.ticket.entity.ProductRevision;

public interface ProductRevisionRepository extends JpaRepository<ProductRevision, Long> {
    List<ProductRevision> findByOriginalProductIdOrderByProductVersionAscIdAsc(long originalProductId);

    /** The revision written for the product's current version, i.e. the terms a purchase made now refers to. */
    Optional<ProductRevision> findFirstByOriginalProductIdAndProductVersionOrderByIdDesc(long originalProductId, long productVersion);
}
