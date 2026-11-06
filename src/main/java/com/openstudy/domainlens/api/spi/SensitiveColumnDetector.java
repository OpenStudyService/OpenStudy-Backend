package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Detects potentially sensitive columns via name/type heuristics plus explicit lists.
 */
public interface SensitiveColumnDetector {

    /**
     * @param columnName   physical or logical column name
     * @param sqlTypeName  optional SQL type name (e.g. BLOB); may be {@code null}
     * @return {@code true} if the column should be treated as sensitive
     */
    boolean isSensitive(@NonNull String columnName, @Nullable String sqlTypeName);
}
