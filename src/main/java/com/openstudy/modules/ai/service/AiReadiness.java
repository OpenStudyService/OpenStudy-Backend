package com.openstudy.modules.ai.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.api.retrieval.EmbeddingGateway;
import com.openstudy.domainlens.catalog.SemanticRegistry;

/**
 * #93 (ADR-M10-003): the AI is ready only when the whole catalog is consistent. Every catalog metric must lead to a
 * registered analysis (which computes through the M9 aggregation services, M10-D05), every registered analysis must
 * have its metric, every metric's source must be in the catalog, and no forbidden source (customers, sessions,
 * invitations, inquiries, team chat) may be. One problem makes the whole catalog not ready: nothing is half enabled.
 * Without the local embedding model only retrieval hints are missing, so that is a warning, not a reason.
 */
@Service
@RequiredArgsConstructor
public class AiReadiness {
    /** Team chat tables beyond the ones already forbidden (ADR-M10-002 M10-D10). */
    static final Set<String> TEAM_CHAT_SOURCES = Set.of("conversations", "conversation_members", "conversation_thread_reads", "messages");

    private final SemanticRegistry registry;
    private final OpenStudyAnalyses analyses;
    private final EmbeddingGateway embeddings;

    public record Status(boolean ready, List<String> reasons, List<String> warnings) {}

    public Status status() {
        var reasons = problems(registry, analyses::offers, OpenStudyAnalyses.REGISTERED);
        var warnings = new ArrayList<String>();
        if ("unavailable".equals(embeddings.model())) {
            warnings.add("임베딩 모델이 설치되지 않아 의미 검색 후보 없이 동작합니다.");
        }
        return new Status(reasons.isEmpty(), List.copyOf(reasons), List.copyOf(warnings));
    }

    public static List<String> problems(SemanticRegistry registry, Predicate<String> offered, Set<String> registered) {
        var reasons = new ArrayList<String>();
        var linked = new HashSet<String>();
        registry.metrics().values().forEach(metric -> {
            if (!offered.test(metric.implementationRef())) {
                reasons.add("지표 " + metric.id() + "가 등록 분석과 연결되지 않았습니다.");
            } else {
                linked.add(metric.implementationRef());
            }
            if (!registry.sources().containsKey(metric.source())) {
                reasons.add("지표 " + metric.id() + "의 원천 " + metric.source() + "이 카탈로그에 없습니다.");
            }
        });
        registered.stream().filter(id -> !linked.contains(id)).sorted()
                .forEach(id -> reasons.add("등록 분석 " + id + "에 연결된 지표가 없습니다."));
        var forbidden = new HashSet<>(OpenStudyCatalogConfiguration.FORBIDDEN_SOURCES);
        forbidden.addAll(TEAM_CHAT_SOURCES);
        registry.sources().keySet().stream().filter(id -> forbidden.contains(id) || forbidden.contains(id + "s")).sorted()
                .forEach(id -> reasons.add("허용되지 않은 원천 " + id + "이 카탈로그에 있습니다."));
        return reasons;
    }
}
