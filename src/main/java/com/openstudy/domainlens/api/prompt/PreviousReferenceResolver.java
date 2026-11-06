package com.openstudy.domainlens.api.prompt;

import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.spi.UserContext;
import java.time.Instant;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Trusted server-side resolution; never accepts client-supplied conditions or authority. */
@FunctionalInterface
public interface PreviousReferenceResolver {
    record Request(UUID conversationId, UUID executionId, @Nullable String datasetId) {}
    record Conditions(UUID executionId, String datasetId, String source, String grain,
            @Nullable Filter filter, Instant asOf, Set<String> semanticRefs, Set<String> dimensions, Set<String> metrics, @Nullable Boolean total) {
        public Conditions(UUID executionId,String datasetId,String source,String grain,@Nullable Filter filter,Instant asOf,Set<String> semanticRefs) {
            this(executionId,datasetId,source,grain,filter,asOf,semanticRefs,Set.of(),Set.of(),false);
        }
        public Conditions(UUID executionId,String datasetId,String source,String grain,@Nullable Filter filter,Instant asOf,Set<String> semanticRefs,Set<String> dimensions,Set<String> metrics) {
            this(executionId,datasetId,source,grain,filter,asOf,semanticRefs,dimensions,metrics,false);
        }
        public Conditions {
            total=Boolean.TRUE.equals(total);
            semanticRefs=Set.copyOf(semanticRefs);dimensions=dimensions==null?Set.of():Set.copyOf(dimensions);metrics=metrics==null?Set.of():Set.copyOf(metrics);
            if(!semanticRefs.containsAll(dimensions) || !semanticRefs.containsAll(metrics))throw new IllegalArgumentException("Reference selections must be approved semantic references");
        }
        @Override public String toString() { return "Conditions[executionId="+executionId+"]"; }
    }
    Conditions resolve(Request request, UserContext user);
}
