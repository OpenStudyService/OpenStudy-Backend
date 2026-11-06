package com.openstudy.domainlens.api.error;

/**
 * Stable machine-readable error codes owned by the engine.
 * Expanded as subsystems land; keep domain-agnostic.
 */
public enum EngineErrorCode {

    /** Declared fixture digest or row counts do not match the live source. */
    FIXTURE_MISMATCH,

    /** The request cannot be expressed or executed by the engine. */
    UNSUPPORTED,

    /** Input IR, view, or parameters failed validation. */
    VALIDATION_ERROR,

    /** Readiness checks failed; the engine must not serve traffic. */
    IR_INVALID, IR_UNSUPPORTED, SCOPE_DENIED, BUDGET_EXCEEDED, JOIN_AMBIGUOUS,
    METRIC_INCOMPLETE, FORMULA_CONFLICT, BINDING_FALLBACK, CLOCK_OUT_OF_RANGE, PII_DENIED, VIEW_SOURCE_REJECTED,
    MODEL_REFUSED, MODEL_INCOMPLETE, MODEL_TRUNCATED, MODEL_FAILED, MODEL_TIMEOUT, QUERY_TIMEOUT,
    CANCELED, NOT_FOUND, INTERNAL_ERROR,
    NOT_READY
}
