package com.openstudy.domainlens.api.work;
/** Public bootstrap constants; consumers do not import persistence implementation classes. */
public final class StorageSchema {
    public static final String ENTITY_PACKAGE="com.openstudy.domainlens.work.persistence";
    public static final String MIGRATION_LOCATION="classpath:db/domainlens/migration";
    public static final int VERSION=1;
    private StorageSchema() {}
}
