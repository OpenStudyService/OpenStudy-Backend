package com.openstudy.domainlens.api.semantic;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds read / row / field policy identifiers to a type, field, or method.
 * Policy IDs are consumer-defined; the engine does not interpret role or store names.
 */
@Documented
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SemanticPolicy {

    /** Read/access policy identifier. */
    String readPolicy() default "";

    /** Row-scope policy identifier. */
    String rowPolicy() default "";

    /** Field-level policy identifier (select/filter/group/sort/output/csv). */
    String fieldPolicy() default "";
}
