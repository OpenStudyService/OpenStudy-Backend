package com.openstudy.domainlens.api.ir;

/**
 * Top-level IR document status.
 * READY documents may be executed; others must not run queries.
 */
public enum IrStatus {
    READY,
    NEEDS_CLARIFICATION,
    UNSUPPORTED
}
