package com.openstudy.domainlens.api.evaluation;
import com.openstudy.domainlens.api.spi.UserContext;
@FunctionalInterface public interface EvaluationAccessPolicy { void authorize(UserContext user); }
