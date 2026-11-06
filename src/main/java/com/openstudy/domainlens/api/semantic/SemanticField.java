package com.openstudy.domainlens.api.semantic;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or getter as a semantic attribute.
 * Supports both field and method (getter) targets.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SemanticField {

    /** Stable semantic identifier (consumer-defined). */
    String id();

    /** Human-readable display name. */
    String name() default "";

    /** Longer description for retrieval and prompting. */
    String description() default "";

    /** Alternate labels / synonyms. */
    String[] aliases() default {};

    /** Semantic role (e.g. identifier, label, measure) — consumer vocabulary. */
    String role() default "";

    /** Unit of measure when applicable. */
    String unit() default "";

    /** Allowed operations (e.g. eq, gt, contains) — consumer vocabulary. */
    String[] allowedOps() default {};

    /** Exact string allowlist; empty means no enumerated restriction. */
    String[] allowedValues() default {};

    /** When false, the field is not exposed to end users. */
    boolean exposed() default true;
}
