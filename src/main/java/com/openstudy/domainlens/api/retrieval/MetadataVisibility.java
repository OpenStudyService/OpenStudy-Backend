package com.openstudy.domainlens.api.retrieval;
import com.openstudy.domainlens.api.spi.UserContext;
/** Consumer restriction for scope-specific aliases/examples, in addition to central field/source policy. */
@FunctionalInterface public interface MetadataVisibility { boolean allows(UserContext user,String nodeId); }
