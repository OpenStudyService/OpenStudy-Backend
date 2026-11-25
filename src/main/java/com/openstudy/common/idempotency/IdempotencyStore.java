package com.openstudy.common.idempotency;

import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Single access path for idempotency_records. Hashing and replay comparison stay with each command. */
@Component
@RequiredArgsConstructor
public class IdempotencyStore {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    /** Locks the stored request, if any, for the rest of the caller's transaction. */
    public Optional<IdempotencyRecord> lock(long actor, String operation, String scopeKey, String key) {
        return idempotencyRecordRepository.findByActorEmployeeIdAndOperationAndScopeKeyAndIdempotencyKey(
                actor,
                operation,
                scopeKey,
                key
        );
    }

    public void complete(
            long actor,
            String operation,
            String scopeKey,
            String key,
            byte[] requestHash,
            int httpStatus,
            String responseJson,
            Instant expiresAt
    ) {
        IdempotencyRecord record = new IdempotencyRecord(
                actor,
                operation,
                scopeKey,
                key,
                requestHash,
                httpStatus,
                responseJson,
                expiresAt
        );

        idempotencyRecordRepository.save(record);
    }

    /** Removes an expired record so the same key can start a new command; flushed before the caller re-inserts. */
    public void discard(IdempotencyRecord record) {
        idempotencyRecordRepository.delete(record);
        idempotencyRecordRepository.flush();
    }
}
