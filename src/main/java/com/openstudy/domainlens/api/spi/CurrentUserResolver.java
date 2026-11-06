package com.openstudy.domainlens.api.spi;
/** Re-reads current account status, roles and scope for background work. */
@FunctionalInterface public interface CurrentUserResolver { UserContext resolve(String subjectId); }
