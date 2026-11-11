package com.openstudy.modules.ai.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * #126 (ADR-M15-001 M15-D03·D08): the form a question is matched against the registered analyses in. Spacing and ASCII case
 * never change the meaning, and the reviewed synonyms and typos below become the catalog's own words. A word not listed
 * here is not guessed: the question goes on to clarification or "not supported" as before.
 */
public final class QuestionNormalizer {
    /**
     * Reviewed replacements, applied in order to the question without spaces. Every entry has a case in
     * QuestionNormalizerUnitTests; adding one goes through code review (M15-D03). Words that are also branch or place names
     * (e.g. 전주) are left out on purpose.
     */
    static final Map<String, String> REPLACEMENTS = new LinkedHashMap<>();
    static {
        // synonyms
        REPLACEMENTS.put("매상", "매출");
        REPLACEMENTS.put("락커", "사물함");
        REPLACEMENTS.put("라커", "사물함");
        REPLACEMENTS.put("빈좌석", "빈자리");
        REPLACEMENTS.put("근로시간", "근무시간");
        REPLACEMENTS.put("저번달", "지난달");
        REPLACEMENTS.put("전월", "지난달");
        REPLACEMENTS.put("저번주", "지난주");
        REPLACEMENTS.put("탑5", "TOP5");
        REPLACEMENTS.put("상위5", "TOP5");
        // typos
        REPLACEMENTS.put("매츨", "매출");
        REPLACEMENTS.put("스터디름", "스터디룸");
        REPLACEMENTS.put("스터디롬", "스터디룸");
    }

    private QuestionNormalizer() {}

    /** The question without whitespace, ASCII letters upper-cased, with the reviewed replacements applied. */
    public static String key(String question) {
        if (question == null) return "";
        String key = question.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        for (var replacement : REPLACEMENTS.entrySet()) key = key.replace(replacement.getKey(), replacement.getValue());
        return key;
    }

    /** Whether the question holds this catalog phrase, written with or without spaces. */
    public static boolean has(String key, String phrase) {
        return key.contains(phrase.replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
    }
}
