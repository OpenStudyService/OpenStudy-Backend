package com.openstudy.domainlens.api.retrieval;
import com.openstudy.domainlens.api.spi.UserContext;
/** Consumer-owned scope aliases and date normalization before any external embedding request. */
@FunctionalInterface public interface QuestionNormalizer { String normalize(String sanitizedAsk,UserContext user); }
