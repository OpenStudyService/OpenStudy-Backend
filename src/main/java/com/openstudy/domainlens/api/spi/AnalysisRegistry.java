package com.openstudy.domainlens.api.spi;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * Registry of multi-step registered analyses provided by the consumer.
 */
public interface AnalysisRegistry {
    record Definition(String id,String version,String source,String grain,java.util.Map<String,java.util.List<com.openstudy.domainlens.api.ir.TypedDataset.Column>> outputs,boolean executable) {
        public Definition {var copy=new java.util.LinkedHashMap<String,java.util.List<com.openstudy.domainlens.api.ir.TypedDataset.Column>>();outputs.forEach((k,v)->copy.put(k,java.util.List.copyOf(v)));outputs=java.util.Map.copyOf(copy);}
    }
    default java.util.Optional<Definition> definition(String id){return java.util.Optional.empty();}


    /** Whether the given analysis id is registered. */
    boolean supports(@NonNull String analysisId);

    /** All registered analysis ids. */
    @NonNull Set<String> analysisIds();
}
