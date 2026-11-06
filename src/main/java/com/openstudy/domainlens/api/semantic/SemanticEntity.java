package com.openstudy.domainlens.api.semantic;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type as a semantic entity in the domain catalog.
 * Domain IDs and names are supplied by the consumer; the engine does not hardcode them.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SemanticEntity {

    /** Stable semantic identifier (consumer-defined). */
    String id();

    /** Human-readable display name. */
    String name() default "";

    /** Longer description for retrieval and prompting. */
    String description() default "";

    /** Alternate labels / synonyms. */
    String[] aliases() default {};

    /** Owning domain key (consumer-defined). */
    String domain() default "";

    /** When false, the entity is catalogued but not exposed to end users. */
    boolean exposed() default true;
}
