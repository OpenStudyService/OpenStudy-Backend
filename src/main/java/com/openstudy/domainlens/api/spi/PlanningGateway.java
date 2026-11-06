package com.openstudy.domainlens.api.spi;
import com.openstudy.domainlens.api.planning.*;
import com.openstudy.domainlens.api.prompt.GenerationBudget;
/** Opt-in generation contract; never supplies executable IR. */
public interface PlanningGateway {
    SemanticPlan plan(PlanningPrompt prompt, GenerationBudget budget, SemanticPlan previous, String failure);
}
