package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.api.retrieval.EmbeddingGateway;
import com.openstudy.domainlens.catalog.SearchText;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.QueryIntent;
import com.openstudy.modules.ai.entity.AiPlanCacheEntity;
import com.openstudy.modules.ai.repository.AiPlanCacheRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2026-11-16: the query plans of AI questions, kept so that a question asked again, in any conversation and by anyone,
 * does not call the model again. Only plans are kept, never results: a reused plan is checked and run again under the
 * requester's permissions and branches, exactly like a new one.
 *
 * <p>Two questions are the same when their embeddings are close ({@link #MIN_SIMILARITY}) <em>and</em> the server reads
 * them the same way ({@link #reading}): metric, grouping, period, numbers and the words that turn a ranking or a
 * condition around. Similarity alone is not enough: "가장 많이 판매된" and "가장 적게 판매된" score 0.94 while a real
 * paraphrase can score lower (measured with koen-e5-tiny-q8, 2026-11-16 development log).
 *
 * <p>A plan lives {@link #TTL} from when it was made; a catalog, model or prompt change makes a new version key, and
 * plans of an older key are never read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPlanCache {

    public enum Kind {

        REGISTERED_PICK,
        GENERATED
    }

    /** Bump when a prompt or the meaning of a stored plan changes; older plans are then ignored. */
    static final String FORMAT = "plan-cache-1";
    static final double MIN_SIMILARITY = 0.90;
    static final Duration TTL = Duration.ofDays(30);
    static final int MAX_PER_KIND = 500;

    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private static final Pattern GROUPING = Pattern.compile("([가-힣A-Z0-9]+?)별");
    /** Words that turn the same question around: which end of a ranking, which basis, what is left out. */
    private static final List<String[]> MODIFIERS = List.of(
            new String[] {"MAX", "많", "높", "큰", "최대", "최고", "붐", "혼잡", "인기", "잘팔"},
            new String[] {"MIN", "적", "낮", "작", "최소", "최저", "한산", "여유", "안팔"},
            new String[] {"AVG", "평균"},
            new String[] {"EXCLUDE", "제외", "빼"},
            new String[] {"INCLUDE", "포함"},
            new String[] {"ORIGINAL_PAYMENT_DATE", "원결제일"},
            new String[] {"ZERO", "0원", "없는날"},
            new String[] {"NOW", "지금", "현재", "실시간"});

    /** A plan found for a question. */
    public record Hit(
            Long id,
            String analysisId,
            String intentDomain,
            String refundBasisOverride,
            boolean zeroFillOverride,
            List<QuerySpec> queries,
            ViewSpec view,
            double similarity
    ) {
    }

    private final AiPlanCacheRepository aiPlanCacheRepository;
    private final EmbeddingGateway embeddingGateway;
    private final OpenStudyIntentAnalyzer openStudyIntentAnalyzer;
    private final SemanticRegistry semanticRegistry;
    private final OpenStudyClock openStudyClock;

    /** The plans of this kind made with this model and prompt; {@code promptKey} names the prompt version. */
    public String versionKey(Kind kind, String modelName, String promptKey) {
        return SearchText.hash(
                String.join(
                        "|",
                        FORMAT,
                        kind.name(),
                        semanticRegistry.version(),
                        OpenStudyAnalyses.CATALOG_VERSION,
                        modelName == null ? "" : modelName,
                        promptKey == null ? "" : promptKey
                )
        );
    }

    /** The cached plan for a question, if one is close enough and read the same way. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Hit> find(Kind kind, String versionKey, String question) {
        try {
            if (question == null || question.isBlank()) {
                return Optional.empty();
            }

            Instant now = openStudyClock.asOf();
            List<AiPlanCacheEntity> candidates =
                    aiPlanCacheRepository.findByKindAndVersionKeyAndExpiresAtAfterOrderByLastUsedAtDesc(
                            kind.name(),
                            versionKey,
                            now,
                            PageRequest.of(0, MAX_PER_KIND)
                    );
            if (candidates.isEmpty()) {
                log.info("AI plan cache miss: kind={} no plans for this version", kind);
                return Optional.empty();
            }
            String reading = reading(question);
            Optional<AiPlanCacheEntity> exact =
                    candidates.stream()
                            .filter(c -> c.getQuestionKey().equals(toQuestionKey(versionKey, question)))
                            .findFirst();
            AiPlanCacheEntity best = exact.orElse(null);
            double bestScore = exact.isPresent() ? 1.0 : -1.0;
            double closest = -1.0;
            int readDifferently = 0;
            if (best == null) {
                List<Double> vector = embeddingGateway.embedQuery(question);
                for (AiPlanCacheEntity candidate : candidates) {
                    double score = cosine(vector, parse(candidate.getVectorJson()));
                    closest = Math.max(closest, score);
                    if (score < MIN_SIMILARITY || score <= bestScore) {
                        continue;
                    }

                    if (!reading.equals(reading(candidate.getQuestionText()))) {
                        readDifferently++;
                        continue;
                    }
                    best = candidate;
                    bestScore = score;
                }
            } else if (!reading.equals(reading(best.getQuestionText()))) {
                log.info("AI plan cache miss: kind={} same text read differently now", kind);
                return Optional.empty();
            }
            if (best == null) {
                log.info("AI plan cache miss: kind={} plans={} closest={} close-but-read-differently={}", kind,
                        candidates.size(), Math.round(closest * 1000) / 1000.0, readDifferently);
                return Optional.empty();
            }
            log.info("AI plan cache hit: kind={} plan={} similarity={}", kind, best.getId(),
                    Math.round(bestScore * 1000) / 1000.0);
            best.used(now);
            return Optional.of(toHit(best, bestScore));
        } catch (RuntimeException ex) {
            log.atWarn().setCause(ex).log("AI plan cache lookup skipped: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Keeps the analysis the model picked for a question. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storePick(
            String versionKey,
            String question,
            String analysisId,
            String intentDomain,
            String refundBasisOverride,
            boolean zeroFillOverride
    ) {
        store(Kind.REGISTERED_PICK, versionKey, question,
                entity -> entity.pick(analysisId, intentDomain, refundBasisOverride, zeroFillOverride));
    }

    /** Keeps the QuerySpec·ViewSpec the generation path compiled for a question. Never throws. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeGenerated(
            String versionKey,
            String question,
            List<QuerySpec> queries,
            ViewSpec view,
            String domain
    ) {
        store(Kind.GENERATED, versionKey, question,
                entity -> entity.specs(ContractJson.write(queries), ContractJson.write(view), domain));
    }

    private void store(
            Kind kind,
            String versionKey,
            String question,
            Consumer<AiPlanCacheEntity> payload
    ) {
        try {
            if (question == null || question.isBlank() || question.length() > 500) {
                return;
            }

            Instant now = openStudyClock.asOf();
            aiPlanCacheRepository.deleteExpired(now);
            String vector = ContractJson.write(embeddingGateway.embedQuery(question));
            String key = toQuestionKey(versionKey, question);
            AiPlanCacheEntity entity = aiPlanCacheRepository.findByQuestionKey(key).orElse(null);
            if (entity == null) {
                entity = new AiPlanCacheEntity(kind.name(), versionKey, key, question, vector, now, now.plus(TTL));
            } else {
                entity.renew(vector, now, now.plus(TTL));
            }
            payload.accept(entity);
            aiPlanCacheRepository.save(entity);
            log.info("AI plan cache stored: kind={} plan={}", kind, entity.getId());
            long excess = aiPlanCacheRepository.countByKind(kind.name()) - MAX_PER_KIND;
            if (excess > 0) {
                aiPlanCacheRepository.deleteAll(
                        aiPlanCacheRepository.findByKindOrderByLastUsedAtAsc(
                                kind.name(),
                                PageRequest.of(0, (int) excess)
                        )
                );
            }
        } catch (RuntimeException ex) {
            log.atWarn().log("AI plan cache store skipped: {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * How the server reads a question, for telling apart questions whose embeddings are close: the general metric,
     * grouping, period and order, the periods named, the numbers, the "…별" groupings and the turning words.
     */
    public String reading(String question) {
        String key = QuestionNormalizer.key(question);
        QueryIntent intent = openStudyIntentAnalyzer.analyze(question, null);
        ArrayList<String> parts = new ArrayList<String>();
        parts.add("metrics=" + new TreeSet<>(intent.metrics()));
        parts.add("dimensions=" + new TreeSet<>(intent.dimensions()));
        parts.add("period=" + (intent.period() == null ? "" : ContractJson.canonical(intent.period())));
        parts.add("order=" + intent.order() + ":" + intent.orderField() + ":" + intent.total());
        parts.add("periods=" + openStudyIntentAnalyzer.periodReadings(question));
        ArrayList<String> numbers = new ArrayList<String>();
        NUMBER.matcher(key).results().forEach(m -> numbers.add(m.group()));
        parts.add("numbers=" + numbers);
        TreeSet<String> groupings = new TreeSet<String>();
        GROUPING.matcher(key).results().forEach(m -> groupings.add(m.group(1)));
        parts.add("groupings=" + groupings);
        TreeSet<String> modifiers = new TreeSet<String>();
        for (String[] words : MODIFIERS) {
            for (int i = 1; i < words.length; i++) {
                if (key.contains(words[i])) {
                    modifiers.add(words[0]);
                }
            }
        }
        parts.add("modifiers=" + modifiers);
        return String.join("|", parts);
    }

    private static String toQuestionKey(String versionKey, String question) {
        return SearchText.hash(versionKey + "|" + QuestionNormalizer.key(question));
    }

    private Hit toHit(AiPlanCacheEntity entity, double similarity) {
        List<QuerySpec> queries = null;
        ViewSpec view = null;
        if (entity.getQuerySpecJson() != null) {
            queries = List.of(ContractJson.read(entity.getQuerySpecJson(), QuerySpec[].class));
            view = ContractJson.read(entity.getViewSpecJson(), ViewSpec.class);
        }
        return new Hit(
                entity.getId(),
                entity.getAnalysisId(),
                entity.getIntentDomain(),
                entity.getRefundBasisOverride(),
                entity.isZeroFillOverride(),
                queries,
                view,
                similarity
        );
    }

    private static List<Double> parse(String json) {
        return List.of(ContractJson.read(json, Double[].class));
    }

    static double cosine(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.isEmpty()) {
            return -1.0;
        }

        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return na == 0.0 || nb == 0.0 ? -1.0 : dot / Math.sqrt(na * nb);
    }
}
