package com.openstudy.modules.ticket.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.ticket.entity.ProductRevision;

public interface ProductRevisionRepository extends JpaRepository<ProductRevision, Long> {
    List<ProductRevision> findByOriginalProductIdOrderByProductVersionAscIdAsc(long originalProductId);
}
