package com.openstudy.domainlens.api.semantic;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a semantic metric bound to a typed implementation reference.
 * May be placed on a type, method, or field.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SemanticMetric {

    /** Stable semantic identifier (consumer-defined). */
    String id();

    /** Reference key of the {@code MetricImpl} SPI bean that computes this metric. */
    String implementationRef() default "";

    /** Grain at which the metric is valid (consumer vocabulary). */
    String grain() default "";

    /** Unit of measure. */
    String unit() default "";

    /** Default filter/condition identifiers applied when unspecified. */
    String[] defaultConditions() default {};

    /** Metric definition version. */
    String version() default "";
}
