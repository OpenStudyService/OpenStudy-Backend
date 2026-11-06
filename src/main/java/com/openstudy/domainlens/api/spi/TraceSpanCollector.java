package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Collects trace spans for steps that actually ran (not speculative branches).
 */
public interface TraceSpanCollector {
    interface Scope extends AutoCloseable { void close(); }
    TraceSpanCollector NOOP=new TraceSpanCollector(){public void start(String name){}public void attribute(String key,String value){}public void end(String name){}};
    default Scope request(java.util.UUID id,String owner){return ()->{};}
    default void failure(String code) {}
    default void finish(String state) {}
    default <T> T measure(String name,java.util.function.Supplier<T> action){start(name);try{return action.get();}catch(RuntimeException ex){failure(ex instanceof com.openstudy.domainlens.api.error.EngineException e?e.code().name():"INTERNAL_ERROR");throw ex;}finally{end(name);}}


    /** Begin a named span. */
    void start(@NonNull String name);

    /** Attach a string attribute to the current span. */
    void attribute(@NonNull String key, @Nullable String value);

    /** End the named span. */
    void end(@NonNull String name);
}
