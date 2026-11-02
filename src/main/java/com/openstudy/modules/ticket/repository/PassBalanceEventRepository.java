package com.openstudy.modules.ticket.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.ticket.entity.PassBalanceEvent;

public interface PassBalanceEventRepository extends JpaRepository<PassBalanceEvent, Long> {
    List<PassBalanceEvent> findByPassIdInOrderByIdAsc(Collection<Long> passIds);
}
