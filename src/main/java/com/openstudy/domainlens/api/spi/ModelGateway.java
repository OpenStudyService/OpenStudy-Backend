package com.openstudy.domainlens.api.spi;

import com.openstudy.domainlens.api.ir.IrDocument;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Produces an {@link IrDocument} from a natural-language ask.
 * Implementations may use playback fixtures or a live chat model; the engine does not depend on ChatModel.
 */
public interface ModelGateway {
    default boolean supportsPlanning(){return false;}
    default com.openstudy.domainlens.api.planning.SemanticPlan plan(
            com.openstudy.domainlens.api.planning.PlanningPrompt prompt,
            com.openstudy.domainlens.api.prompt.GenerationBudget budget,
            com.openstudy.domainlens.api.planning.SemanticPlan previous,String failure) {
        throw new UnsupportedOperationException("Semantic planning is not enabled");
    }


    default java.time.Duration requestTimeout(){return java.time.Duration.ofSeconds(60);}
    default IrDocument generate(com.openstudy.domainlens.api.prompt.PromptPackage prompt,com.openstudy.domainlens.api.prompt.GenerationBudget budget){return generate(prompt);}
    default IrDocument repair(com.openstudy.domainlens.api.prompt.PromptPackage prompt,IrDocument previous,String failure,com.openstudy.domainlens.api.prompt.GenerationBudget budget){return repair(prompt,previous,failure);}
    /** A gateway may opt in to bounded pre-execution corrections; the pipeline chooses the attempt limit. */
    default boolean supportsRepair(){return false;}
    default IrDocument repair(com.openstudy.domainlens.api.prompt.PromptPackage prompt, IrDocument previous, String failure) { return generate(prompt); }

    /** Structured outbound boundary. Existing playback/stub implementations remain source compatible. */
    default @NonNull IrDocument generate(com.openstudy.domainlens.api.prompt.PromptPackage prompt) {
        return generate(prompt.data().question(), prompt.contextRefs());
    }


    /**
     * Generate an IR document for the given ask and semantic context refs.
     *
     * @param ask          user question (already refined by the pipeline)
     * @param contextRefs  semantic node ids available to the model
     * @return IR document (object root); never {@code null}
     */
    @NonNull IrDocument generate(@NonNull String ask, @NonNull List<String> contextRefs);
}
