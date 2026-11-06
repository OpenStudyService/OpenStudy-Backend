package com.openstudy.domainlens.api.semantic;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field or getter as a semantic relation to another entity.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SemanticRelation {

    /** Stable semantic identifier (consumer-defined). */
    String id();

    /** Meaning of the association (consumer vocabulary). */
    String meaning() default "";

    /** Direction hint (e.g. outgoing, incoming, bidirectional). */
    String direction() default "";

    /** How the relation may be used in joins (e.g. required, optional). */
    String joinUse() default "";

    /** Target semantic entity ID or JPA entity name for scalar foreign keys (e.g. "openstudy.branch"). */
    String targetEntity() default "";

    /** Target field path on the target entity for scalar foreign keys (default "id"). */
    String targetField() default "id";
}
