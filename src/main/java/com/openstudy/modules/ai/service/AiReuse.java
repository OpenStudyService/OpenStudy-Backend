package com.openstudy.modules.ai.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;
import com.openstudy.domainlens.api.ir.ResultEnvelope;
import com.openstudy.domainlens.api.ir.TypedDataset;

import static com.openstudy.modules.ai.service.OpenStudyAnalyses.*;

/**
 * #125 (analysis-reuse-api.md, ADR-M15-001 M15-D04·D05): what a refinement may change for each registered analysis, the
 * refinement body, the conditions an execution ran under, and table pages over a re-run result. Pure functions; the
 * permission checks and the run itself stay in AiWorkspaceService.
 */
final class AiReuse {
    static final int MAX_PRODUCTS = 20;
    private static final Set<String> FIELDS = Set.of("period", "branchIds", "productIds", "clientRequestKey");
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private AiReuse() {}

    /** {@code editableFilters}: a null {@code maxDays} means the analysis looks at now and its period cannot change. */
    record Editable(Integer maxDays, boolean singleBranch, boolean products) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("period", maxDays == null ? null : Map.of("maxDays", maxDays));
            map.put("branches", Map.of("single", singleBranch));
            map.put("products", products);
            return map;
        }
    }

    /** The table in "분석별 수정 가능 조건"; one-branch analyses are OpenStudyAnalyses.SINGLE_BRANCH. */
    static final Map<String, Editable> EDITABLE = Map.of(
            ID_POC_001, new Editable(366, false, false),
            ID_POC_002, new Editable(92, true, false),
            ID_POC_003, new Editable(366, false, true),
            ID_POC_004, new Editable(null, true, false),
            ID_POC_005, new Editable(31, false, false),
            ID_POC_006, new Editable(92, false, true),
            ID_POC_007, new Editable(366, false, false),
            ID_POC_008, new Editable(366, false, false),
            ID_POC_009, new Editable(null, false, false),
            ID_POC_010, new Editable(92, false, false));

    static Editable editable(String analysisId) {
        return EDITABLE.getOrDefault(analysisId, new Editable(null, false, false));
    }

    /** A refinement body; a null field keeps the source execution's value. */
    record Refinement(LocalDate from, LocalDate to, Set<Long> branchIds, Set<Long> productIds, String clientRequestKey) {}

    /** Step 4 of the contract order: the body's shape and the conditions this analysis lets a refinement change. */
    static Refinement parse(Map<String, Object> body, Editable editable) {
        if (body == null) body = Map.of();
        for (String key : body.keySet()) {
            if (!FIELDS.contains(key)) throw ApiException.validation(key, "필터 수정은 기간·지점·상품만 바꿀 수 있습니다. 분석을 바꾸려면 새로 질문해 주세요.");
        }
        if (!body.containsKey("period") && !body.containsKey("branchIds") && !body.containsKey("productIds")) {
            throw ApiException.validation("period", "기간·지점·상품 중 하나 이상을 보내 주세요.");
        }
        LocalDate from = null;
        LocalDate to = null;
        if (body.containsKey("period")) {
            if (editable.maxDays() == null) throw notEditable("period", "이 분석은 지금 기준이라 기간을 바꿀 수 없습니다.");
            if (!(body.get("period") instanceof Map<?, ?> period) || period.size() != 2
                    || !(period.get("from") instanceof String start) || !(period.get("to") instanceof String end)) {
                throw ApiException.validation("period", "기간은 {from, to} 날짜로 보내 주세요.");
            }
            try {
                from = LocalDate.parse(start);
                to = LocalDate.parse(end);
            } catch (DateTimeParseException e) {
                throw ApiException.validation("period", "날짜는 YYYY-MM-DD 형식으로 보내 주세요.");
            }
            if (to.isBefore(from)) throw ApiException.validation("period", "끝 날짜가 시작 날짜보다 앞설 수 없습니다.");
        }
        Set<Long> branches = null;
        if (body.containsKey("branchIds")) {
            branches = ids(body.get("branchIds"), "branchIds");
            if (branches.isEmpty()) throw ApiException.validation("branchIds", "지점을 하나 이상 골라 주세요.");
            if (editable.singleBranch() && branches.size() != 1) {
                throw ApiException.validation("branchIds", "이 분석은 한 지점만 볼 수 있습니다.");
            }
        }
        Set<Long> products = null;
        if (body.containsKey("productIds")) {
            if (!editable.products()) throw notEditable("productIds", "이 분석은 상품 조건을 바꿀 수 없습니다.");
            products = ids(body.get("productIds"), "productIds");
            if (products.size() > MAX_PRODUCTS) {
                throw ApiException.validation("productIds", "상품은 최대 " + MAX_PRODUCTS + "개까지 고를 수 있습니다.");
            }
        }
        String key = null;
        if (body.get("clientRequestKey") != null) {
            if (!(body.get("clientRequestKey") instanceof String text) || !CLIENT_KEY.matcher(text.trim()).matches()) {
                throw ApiException.validation("clientRequestKey", "요청 키는 영문·숫자와 . : _ - 기호로 1~100자를 보내 주세요.");
            }
            key = text.trim();
        }
        return new Refinement(from, to, branches, products, key);
    }

    /** Step 5: a changed period stays within the analysis's maximum. */
    static void checkLength(Refinement refinement, Editable editable) {
        if (refinement.from() == null) return;
        if (ChronoUnit.DAYS.between(refinement.from(), refinement.to()) + 1 > editable.maxDays()) {
            throw ApiException.invalid("PERIOD_TOO_LONG", "기간이 이 분석에서 볼 수 있는 범위를 넘었습니다.", "period",
                    "기간은 최대 " + editable.maxDays() + "일까지 고를 수 있습니다.");
        }
    }

    private static ApiException notEditable(String field, String message) {
        return ApiException.invalid("FILTER_NOT_EDITABLE", "이 분석에서 바꿀 수 없는 조건입니다.", field, message);
    }

    /** IDs as the contract's decimal strings; numbers are taken too. Duplicates collapse. */
    private static Set<Long> ids(Object value, String field) {
        if (!(value instanceof List<?> list)) throw ApiException.validation(field, "ID 목록으로 보내 주세요.");
        Set<Long> ids = new LinkedHashSet<>();
        for (Object item : list) {
            String text = item instanceof Number number && !(item instanceof Double || item instanceof Float)
                    ? Long.toString(number.longValue()) : item instanceof String s ? s : "";
            if (!ID.matcher(text).matches()) throw ApiException.validation(field, "ID 형식을 확인해 주세요.");
            ids.add(Long.parseLong(text));
        }
        return ids;
    }

    /** {@code AiConditions}: what one execution was computed with. A null {@code from} is an analysis without a period. */
    record Conditions(String analysisId, LocalDate from, LocalDate to, boolean periodEdited, Set<Long> branchIds,
            Set<Long> productIds, String refundBasis, Boolean zeroFill) {

        /** The period as Asia/Seoul dates, both ends inclusive; an end at midnight belongs to the day before. */
        static Conditions of(String analysisId, OpenStudyClock.KstPeriod period, boolean periodEdited, Set<Long> branchIds,
                Set<Long> productIds, String refundBasis, Boolean zeroFill) {
            LocalDate from = period == null ? null : period.startDateKst();
            LocalDate to = period == null ? null : period.endExclusiveKst().toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? period.endDateExclusiveKst().minusDays(1) : period.endDateExclusiveKst();
            return new Conditions(analysisId, from, to, periodEdited, Set.copyOf(branchIds), Set.copyOf(productIds),
                    refundBasis, zeroFill);
        }

        Conditions with(Refinement refinement) {
            boolean period = refinement.from() != null;
            return new Conditions(analysisId, period ? refinement.from() : from, period ? refinement.to() : to,
                    periodEdited || period, refinement.branchIds() != null ? Set.copyOf(refinement.branchIds()) : branchIds,
                    refinement.productIds() != null ? Set.copyOf(refinement.productIds()) : productIds, refundBasis, zeroFill);
        }

        /** The refinable conditions that differ from {@code before}: PERIOD, BRANCHES, PRODUCTS. */
        List<String> changedFrom(Conditions before) {
            List<String> changed = new ArrayList<>();
            if (!java.util.Objects.equals(from, before.from) || !java.util.Objects.equals(to, before.to)) changed.add("PERIOD");
            if (!branchIds.equals(before.branchIds)) changed.add("BRANCHES");
            if (!productIds.equals(before.productIds)) changed.add("PRODUCTS");
            return changed;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("analysisId", analysisId);
            map.put("period", from == null ? null : Map.of("from", from.toString(), "to", to.toString()));
            map.put("periodSource", from == null ? null : periodEdited ? "EDITED" : "DEFAULT");
            map.put("branchIds", branchIds.stream().sorted().map(String::valueOf).toList());
            map.put("productIds", productIds.stream().sorted().map(String::valueOf).toList());
            map.put("refundBasis", refundBasis);
            map.put("zeroFill", zeroFill);
            return map;
        }
    }

    /** {@code AiContext} of a refinement: it builds on its source and names what it changed. */
    static Map<String, Object> refinementContext(String sourceExecutionId, Conditions applied, List<String> changed) {
        List<String> inherited = new ArrayList<>(List.of("ANALYSIS"));
        if (applied.from() != null && !changed.contains("PERIOD")) inherited.add("PERIOD");
        if (!changed.contains("BRANCHES")) inherited.add("BRANCHES");
        if (!changed.contains("PRODUCTS")) inherited.add("PRODUCTS");
        if (applied.refundBasis() != null) inherited.add("REFUND_BASIS");
        if (applied.zeroFill() != null) inherited.add("ZERO_FILL");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("basedOnExecutionId", sourceExecutionId);
        map.put("inherited", inherited);
        map.put("changed", changed);
        map.put("reset", false);
        map.put("resetReason", null);
        return map;
    }

    /**
     * {@code GET /ai/executions/{id}} with a table query: one dataset sorted within the result's 1,000-row cap and cut to
     * a page. Without any of these queries the result comes back whole, as before #125.
     */
    static ResultEnvelope page(ResultEnvelope envelope, String datasetId, Integer page, Integer size, String sort,
            String direction) {
        if (datasetId == null && page == null && size == null && sort == null && direction == null) return envelope;
        int number = page == null ? 1 : page;
        int rowsPerPage = size == null ? 20 : size;
        if (number < 1) throw ApiException.validation("page", "페이지는 1 이상이어야 합니다.");
        if (rowsPerPage < 1 || rowsPerPage > 50) throw ApiException.validation("size", "한 페이지는 1~50행입니다.");
        boolean ascending = "asc".equals(direction);
        if (direction != null && !ascending && !"desc".equals(direction)) {
            throw ApiException.validation("direction", "정렬 방향은 asc 또는 desc입니다.");
        }
        TypedDataset dataset = datasetId == null
                ? envelope.datasets().stream().findFirst().orElseThrow(() -> ApiException.validation("datasetId", "결과에 데이터셋이 없습니다."))
                : envelope.datasets().stream().filter(d -> d.id().equals(datasetId)).findFirst()
                        .orElseThrow(() -> ApiException.validation("datasetId", "결과에 없는 데이터셋입니다."));
        List<Map<String, Object>> rows = new ArrayList<>(dataset.rows());
        if (sort != null) {
            if (dataset.columns().stream().noneMatch(column -> column.alias().equals(sort))) {
                throw ApiException.validation("sort", "이 데이터셋에 없는 열입니다.");
            }
            Comparator<Object> values = ascending ? AiReuse::compareValues : (a, b) -> compareValues(b, a);
            rows.sort(Comparator.comparing(row -> row.get(sort), Comparator.nullsLast(values)));
        }
        int start = (int) Math.min((long) (number - 1) * rowsPerPage, rows.size());
        var paged = new TypedDataset(dataset.id(), dataset.columns(), rows.subList(start, Math.min(start + rowsPerPage, rows.size())),
                rows.size(), number, rowsPerPage, dataset.snapshotAt(), dataset.asOf());
        var datasets = envelope.datasets().stream().map(d -> d == dataset ? paged : d).toList();
        return new ResultEnvelope(envelope.requestId(), envelope.executionId(), envelope.status(), datasets, envelope.view(),
                envelope.warnings(), envelope.errorCode(), envelope.asOf(), envelope.snapshotVersion(), envelope.policyVersion(),
                envelope.formulaBindings(), envelope.displayValues(), envelope.notice());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()));
        if (a instanceof Comparable x && a.getClass().isInstance(b)) return x.compareTo(b);
        return a.toString().compareTo(b.toString());
    }
}
