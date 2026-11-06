package com.openstudy.domainlens.api.spi;
import java.util.Set;
/** Registered analysis capabilities visible to the authenticated user. */
@FunctionalInterface
public interface AnalysisCapabilities { Set<String> supported(UserContext user); }
