package com.openstudy.domainlens.api.ir;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Domain-independent Intermediate Representation document.
 * <p>
 * Top-level is an object (not an array) so structured-output providers that reject
 * root arrays can accept it. Server-side envelope fields (requestId, tenant, asOf,
 * policy versions) are not part of the LLM-produced document.
 * <p>
 * Structural contracts are defined by D08; semantic IDs and grains are consumer-owned.
 *
 * @param schemaVersion         IR contract version
 * @param status                READY / NEEDS_CLARIFICATION / UNSUPPORTED
 * @param clarification         user-facing clarification prompt when needed
 * @param clarificationOptions  up to three {id,label} choices
 * @param unsupportedReason     machine code when status is UNSUPPORTED
 * @param queries               dataset query specs (executed only when READY)
 * @param view                  view binding queries to UI components
 * @param contextRefs           semantic node ids used to produce this document
 */
public record IrDocument(
        @NonNull String schemaVersion,
        @NonNull IrStatus status,
        @Nullable String clarification,
        @NonNull List<ClarificationOption> clarificationOptions,
        @Nullable String unsupportedReason,
        @NonNull List<QuerySpec> queries,
        @Nullable ViewSpec view,
        @NonNull List<String> contextRefs,
        @Nullable ReasonCode reasonCode
) {
    public IrDocument { clarificationOptions=List.copyOf(clarificationOptions); queries=List.copyOf(queries); contextRefs=List.copyOf(contextRefs); }
    public IrDocument(String schemaVersion,IrStatus status,String clarification,List<ClarificationOption> options,String reason,List<QuerySpec> queries,ViewSpec view,List<String> refs) {
        this(schemaVersion,status,clarification,options,reason,queries,view,refs,VERSION.equals(schemaVersion)?ReasonCode.defaultFor(status):null);
    }
    public static final String VERSION = "1.1";
    public static final String LEGACY_VERSION = "1.0";
}
