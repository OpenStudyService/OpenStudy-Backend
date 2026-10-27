package com.openstudy.common.persistence;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * JDBC reported a unique-key violation as {@link DuplicateKeyException}; a JPA flush reports the same MySQL error as
 * a plain {@link DataIntegrityViolationException}. Callers that map duplicates to a domain conflict wrap the insert
 * here so only MySQL error 1062 (ER_DUP_ENTRY) is narrowed; every other integrity error passes through unchanged.
 */
public final class DuplicateKeys {
    private static final int ER_DUP_ENTRY = 1062;

    private DuplicateKeys() {}

    public static <T> T translate(Supplier<T> insert) {
        try {
            return insert.get();
        } catch (DataIntegrityViolationException exception) {
            if (exception instanceof DuplicateKeyException || !duplicate(exception)) throw exception;
            throw new DuplicateKeyException(exception.getMessage(), exception);
        }
    }

    private static boolean duplicate(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == ER_DUP_ENTRY) return true;
        }
        return false;
    }
}
