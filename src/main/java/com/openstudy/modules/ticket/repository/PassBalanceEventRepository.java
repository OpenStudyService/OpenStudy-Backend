package com.openstudy.modules.ticket.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.ticket.entity.PassBalanceEvent;

public interface PassBalanceEventRepository extends JpaRepository<PassBalanceEvent, Long> {
}
