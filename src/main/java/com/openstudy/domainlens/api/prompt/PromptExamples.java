package com.openstudy.domainlens.api.prompt;

import java.util.Optional;

/** Consumer-approved question/IR pairs; IDs must also be registered catalog examples. */
@FunctionalInterface
public interface PromptExamples {
    Optional<PromptPackage.Example> find(String id);
    static PromptExamples empty() { return id -> Optional.empty(); }
}
