package com.openstudy.domainlens.api.spi;
/** Authorizes operational trace access; supplies no additional business/PII permissions. */
@FunctionalInterface public interface TraceAccessPolicy { void authorize(UserContext user); }
