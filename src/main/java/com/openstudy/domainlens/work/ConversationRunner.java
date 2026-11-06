package com.openstudy.domainlens.work;

import com.openstudy.domainlens.api.prompt.PreviousReferenceResolver;
import com.openstudy.domainlens.api.spi.UserContext;

/** Consumer bridge to its activated retrieval/pipeline implementation. */
@FunctionalInterface
public interface ConversationRunner {
    RequestService.Job prepare(String question, PreviousReferenceResolver.Request reference, UserContext user);
}
