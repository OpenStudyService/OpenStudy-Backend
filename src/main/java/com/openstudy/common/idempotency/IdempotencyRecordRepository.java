package com.openstudy.common.idempotency;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    /** SELECT ... FOR UPDATE: also gap-locks a missing key so concurrent first requests serialize. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdempotencyRecord> findByActorEmployeeIdAndOperationAndScopeKeyAndIdempotencyKey(
            long actorEmployeeId, String operation, String scopeKey, String idempotencyKey);
}
