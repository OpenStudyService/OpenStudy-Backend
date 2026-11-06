package com.openstudy.domainlens.api.spi;

import java.time.Instant;
import java.time.ZoneId;
import org.jspecify.annotations.NonNull;

/**
 * Clock used for as-of evaluation and relative date normalization.
 * <p>
 * <b>The engine provides NO default asOf value.</b> Consumers must register a
 * {@code ClockPort} bean; relative periods are resolved against {@link #asOf()}.
 */
public interface ClockPort {

    /** Reference instant for "as of" evaluation. No engine-side default exists. */
    @NonNull Instant asOf();

    /** Zone used when rendering display-oriented dates. Calendar normalization uses the
     * consumer's separately supplied calendar zone, never the JVM default zone. */
    @NonNull ZoneId displayZone();
}
