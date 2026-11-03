package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.workforce.entity.ShiftEvent;

public interface ShiftEventRepository extends JpaRepository<ShiftEvent, Long> {
    List<ShiftEvent> findByShiftIdInAndEventTypeOrderByIdAsc(Collection<Long> shiftIds, ShiftEvent.Type eventType);
}
