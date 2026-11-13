package com.openstudy.modules.ai.service;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.api.ir.ComponentCatalog;
import com.openstudy.domainlens.api.prompt.PromptExamples;
import com.openstudy.domainlens.api.prompt.PromptLimits;
import com.openstudy.domainlens.api.retrieval.QuestionNormalizer;
import com.openstudy.domainlens.api.spi.ModelGateway;
import com.openstudy.domainlens.api.spi.TraceSpanCollector;
import com.openstudy.domainlens.catalog.CatalogActivation;
import com.openstudy.domainlens.catalog.EmbeddingIndex;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.ContextGate;
import com.openstudy.domainlens.query.IrValidator;
import com.openstudy.domainlens.query.JoinPlanner;
import com.openstudy.domainlens.query.JpaQueryExecutor;
import com.openstudy.domainlens.query.QueryPipeline;
import com.openstudy.domainlens.query.RetrievalService;
import com.openstudy.domainlens.query.Retriever;
import com.openstudy.domainlens.query.ViewBinder;
import com.openstudy.domainlens.security.PolicyEngine;
import jakarta.persistence.EntityManagerFactory;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.ir.ResultEnvelope;
import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.Cancellation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * #232: assembles the DomainLens generation path ({@link QueryPipeline}) from the OpenStudy catalog, policies, clock
 * and registered analyses. The model gateway is a parameter, so tests can replay a recorded answer and the chat can
 * use the live model; the rest of the pipeline (IR validation, policy-checked planning, JPA execution, view binding)
 * is the same in both.
 */
@Component
@RequiredArgsConstructor
public class OpenStudyQueryPipelineFactory {
    private final EntityManagerFactory entityManagerFactory;
    private final SemanticRegistry registry;
    private final PolicyEngine policies;
    private final OpenStudyClock clock;
    private final OpenStudyAnalyses analyses;
    private final Retriever retriever;
    private final EmbeddingIndex embeddingIndex;
    private final OpenStudyIntentAnalyzer intents;

    /** The pipeline over a fresh, activated catalog snapshot. */
    public QueryPipeline create(ModelGateway gateway) {
        var validator = new IrValidator(registry, policies, clock.normalizer(), analyses);
        var planner = new JoinPlanner(registry, policies, analyses);
        var executor = new JpaQueryExecutor(entityManagerFactory, registry, policies, validator);
        var binder = new ViewBinder(ComponentCatalog.openStudyDefault());
        return new QueryPipeline(gateway, validator, planner, executor, binder, clock, registry,
                OpenStudyPolicies.POLICY_VERSION, activate(binder), PromptExamples.empty(), PromptLimits.defaults())
                .grounding(intents);
    }

    /**
     * #233: saved generated queries run again without the model. Each query is validated and planned under the
     * requester's current policies (branch scope, sales grant) before JPA runs it, and the saved view is bound again.
     */
    public ResultEnvelope execute(List<QuerySpec> queries, ViewSpec view, UserContext user) {
        var validator = new IrValidator(registry, policies, clock.normalizer(), analyses);
        var planner = new JoinPlanner(registry, policies, analyses);
        var executor = new JpaQueryExecutor(entityManagerFactory, registry, policies, validator);
        var binder = new ViewBinder(ComponentCatalog.openStudyDefault());
        var datasets = new ArrayList<TypedDataset>();
        var cancellation = new Cancellation();
        Instant asOf = clock.asOf();
        Instant snapshot = Instant.now();
        for (var query : queries) {
            validator.query(query, user);
            datasets.addAll(executor.executeAll(planner.plan(query, user, asOf, snapshot), user, cancellation));
        }
        var binding = binder.bind(view, datasets);
        return new ResultEnvelope(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "COMPLETED", datasets,
                binding.view(), binding.warnings(), null, asOf, registry.version(), OpenStudyPolicies.POLICY_VERSION);
    }

    /** Permission-first semantic retrieval feeding the pipeline's prompt context. */
    public RetrievalService retrieval() {
        QuestionNormalizer normalizer = (ask, user) -> ask;
        return new RetrievalService(normalizer, retriever, new ContextGate(registry), TraceSpanCollector.NOOP);
    }

    private CatalogActivation activate(ViewBinder binder) {
        var activation = new CatalogActivation();
        var status = activation.activate(registry, embeddingIndex, OpenStudyPolicies.POLICY_VERSION, binder.catalog(), clock,
                OpenStudyCatalogConfiguration.metricImplementations(), List.of(), () -> true);
        if (!status.ready()) {
            throw new IllegalStateException("DomainLens catalog is not ready: " + status.checks());
        }
        return activation;
    }
}
