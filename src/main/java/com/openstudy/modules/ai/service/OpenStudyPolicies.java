package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.retrieval.EmbeddingGateway;
import com.openstudy.domainlens.api.spi.FieldPolicy;
import com.openstudy.domainlens.api.spi.ReadPolicy;
import com.openstudy.domainlens.api.spi.RowPolicy;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.catalog.EmbeddingIndex;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.Retriever;
import com.openstudy.domainlens.security.PolicyEngine;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DomainLens policy engine and semantic retriever wiring for OpenStudy ({@code OS-DL-0501}, {@code ADR-M10-001}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenStudyPolicies {
    public static final String POLICY_VERSION = "openstudy-policy-v1";

    private static final Set<String> FORBIDDEN_FIELD_IDS = Set.of(
            "customer.id",
            "customer.member_no",
            "customer.name",
            "customer.phone",
            "customer.email",
            "customer.memo",
            "employee.email",
            "employee.password_hash",
            "branch.phone",
            "work_task.body",
            "refund.reason"
    );

    @Bean
    public PolicyEngine openStudyPolicyEngine(SemanticRegistry registry) {
        List<ReadPolicy> readPolicies = List.of(
                new ReadPolicy() {
                    @Override public String id() { return "openstudy.read.ai"; }
                    @Override
                    public boolean allows(UserContext user, String sourceId) {
                        return hasGrant(user, "ai:read");
                    }
                },
                new ReadPolicy() {
                    @Override public String id() { return "openstudy.read.sales"; }
                    @Override
                    public boolean allows(UserContext user, String sourceId) {
                        return hasGrant(user, "ai:read") && hasGrant(user, "sales:read");
                    }
                },
                new ReadPolicy() {
                    @Override public String id() { return "openstudy.read.pii.denied"; }
                    @Override
                    public boolean allows(UserContext user, String sourceId) {
                        return false;
                    }
                }
        );

        List<RowPolicy> rowPolicies = List.of(
                new RowPolicy() {
                    @Override public String id() { return "openstudy.branchScope"; }
                    @Override
                    public Filter predicate(UserContext user, String sourceId) {
                        if (user == null || user.policyBinding() == null) {
                            return new Filter.Leaf("branch.id", Filter.Operator.IN, List.of(-1L));
                        }
                        Object rawBranches = user.policyBinding().scope().get("branchIds");
                        if (rawBranches instanceof Collection<?> col && !col.isEmpty()) {
                            List<Object> values = col.stream()
                                    .filter(Number.class::isInstance)
                                    .map(n -> (Object) ((Number) n).longValue())
                                    .toList();
                            String branchField = switch (sourceId) {
                                case "branch" -> "branch.id";
                                default -> sourceId + ".branch_id";
                            };
                            return new Filter.Leaf(branchField, Filter.Operator.IN, values);
                        }
                        return null;
                    }
                },
                new RowPolicy() {
                    @Override public String id() { return "openstudy.globalScope"; }
                    @Override
                    public Filter predicate(UserContext user, String sourceId) {
                        return null;
                    }
                }
        );

        List<FieldPolicy> fieldPolicies = List.of(
                new FieldPolicy() {
                    @Override public String id() { return "openstudy.field.public"; }
                    @Override
                    public boolean allows(String fieldId, String operation) {
                        return fieldId != null && !FORBIDDEN_FIELD_IDS.contains(fieldId);
                    }
                },
                new FieldPolicy() {
                    @Override public String id() { return "openstudy.field.employee"; }
                    @Override
                    public boolean allows(String fieldId, String operation) {
                        return fieldId != null
                                && !FORBIDDEN_FIELD_IDS.contains(fieldId)
                                && !fieldId.endsWith(".email")
                                && !fieldId.endsWith(".password_hash");
                    }
                },
                new FieldPolicy() {
                    @Override public String id() { return "openstudy.field.pii.denied"; }
                    @Override
                    public boolean allows(String fieldId, String operation) {
                        return false;
                    }
                }
        );

        return new PolicyEngine(
                registry,
                POLICY_VERSION,
                readPolicies,
                fieldPolicies,
                rowPolicies,
                action -> true
        );
    }

    @Bean
    public Retriever openStudyRetriever(
            SemanticRegistry registry,
            PolicyEngine policyEngine,
            EmbeddingIndex embeddingIndex,
            EmbeddingGateway embeddingGateway
    ) {
        return new Retriever(
                registry,
                policyEngine,
                embeddingIndex,
                embeddingGateway,
                (user, id) -> !FORBIDDEN_FIELD_IDS.contains(id),
                Retriever.Limits.defaults()
        ).connected(true);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasGrant(UserContext user, String grant) {
        if (user == null) return false;
        if (user.roles() != null && user.roles().contains("OWNER")) return true;
        if (user.policyBinding() != null && user.policyBinding().scope() != null) {
            Object grants = user.policyBinding().scope().get("grants");
            if (grants instanceof Collection<?> col) {
                return col.contains(grant);
            }
        }
        return false;
    }
}
