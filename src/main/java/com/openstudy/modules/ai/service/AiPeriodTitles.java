package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.ir.ResultEnvelope;
import com.openstudy.domainlens.api.ir.ViewSpec;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Registered analyses title their views with their default period ("이번 달 전체 순매출 요약", "지난달 …"). When a
 * question, a follow-up or a refinement runs one over another period, the titles name that period instead, so last
 * month's sales never read as "이번 달".
 */
final class AiPeriodTitles {
    static final List<String> DEFAULT_WORDS = List.of("이번 달", "지난달", "이번 주", "지난주");

    private AiPeriodTitles() {}

    static OpenStudyAnalyses.PocExecutionResult retitle(OpenStudyAnalyses.PocExecutionResult result, OpenStudyClock.KstPeriod period) {
        String label = label(period);
        var envelope = result.envelope();
        ViewSpec view = envelope.view();
        ViewSpec retitled = view == null ? null : new ViewSpec(view.catalogVersion(), view.components().stream()
                .map(component -> {
                    if (!(component.options().get("title") instanceof String title)) return component;
                    var options = new LinkedHashMap<>(component.options());
                    options.put("title", replace(title, label));
                    return new ViewSpec.Component(component.id(), component.componentRef(), component.datasetId(),
                            component.bindings(), options, component.events());
                }).toList(), view.layout());
        var newEnvelope = new ResultEnvelope(envelope.requestId(), envelope.executionId(), envelope.status(), envelope.datasets(),
                retitled, envelope.warnings(), envelope.errorCode(), envelope.asOf(), envelope.snapshotVersion(),
                envelope.policyVersion(), envelope.formulaBindings(), envelope.displayValues(), envelope.notice());
        return new OpenStudyAnalyses.PocExecutionResult(result.pocCode(), result.analysisId(),
                result.question() == null ? null : replace(result.question(), label), result.semanticStatus(),
                result.expectedViewTypes(), newEnvelope, result.summary());
    }

    /** "2026년 10월" for a whole calendar month, otherwise "2026-10-05~2026-10-11" (both ends inclusive). */
    static String label(OpenStudyClock.KstPeriod period) {
        LocalDate from = period.startDateKst();
        LocalDate toExclusive = period.endDateExclusiveKst();
        if (from.getDayOfMonth() == 1 && toExclusive.equals(from.plusMonths(1))) {
            return from.getYear() + "년 " + from.getMonthValue() + "월";
        }
        return from + "~" + toExclusive.minusDays(1);
    }

    static String replace(String text, String label) {
        for (String word : DEFAULT_WORDS) {
            if (text.startsWith(word)) return label + text.substring(word.length());
        }
        return text;
    }
}
