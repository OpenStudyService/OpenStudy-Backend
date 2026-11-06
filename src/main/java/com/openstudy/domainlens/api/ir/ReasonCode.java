package com.openstudy.domainlens.api.ir;

/** Stable non-execution reasons. Authority and operational failures are server-owned error codes. */
public enum ReasonCode {
    KNOWLEDGE_MISSING, KNOWLEDGE_INSUFFICIENT, REQUEST_INCOMPLETE, METRIC_AMBIGUOUS, REQUIRED_INPUT_MISSING,
    CAPABILITY_UNSUPPORTED, SEMANTIC_GROUNDING_FAILED, FORMULA_CONFLICT;
    public boolean permits(IrStatus status) {
        return switch(this) {
            case METRIC_AMBIGUOUS, REQUIRED_INPUT_MISSING, SEMANTIC_GROUNDING_FAILED -> status==IrStatus.NEEDS_CLARIFICATION;
            case KNOWLEDGE_MISSING, KNOWLEDGE_INSUFFICIENT, REQUEST_INCOMPLETE, CAPABILITY_UNSUPPORTED, FORMULA_CONFLICT -> status==IrStatus.UNSUPPORTED;
        };
    }
    public static ReasonCode defaultFor(IrStatus status) {
        return switch(status) {
            case READY -> null;
            case NEEDS_CLARIFICATION -> REQUIRED_INPUT_MISSING;
            case UNSUPPORTED -> CAPABILITY_UNSUPPORTED;
        };
    }
}
