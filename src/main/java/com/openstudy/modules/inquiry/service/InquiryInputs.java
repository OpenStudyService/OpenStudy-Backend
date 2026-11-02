package com.openstudy.modules.inquiry.service;

import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.inquiry.entity.InquirySource;
import com.openstudy.modules.inquiry.entity.InquiryStatus;

/** Wire labels and field rules of the inquiry contract. */
public final class InquiryInputs {
    private static final Map<String, InquiryStatus> STATUSES = Map.of("접수", InquiryStatus.RECEIVED,
            "처리 중", InquiryStatus.IN_PROGRESS, "답변 완료", InquiryStatus.ANSWERED);
    private static final Map<String, InquirySource> SOURCES = Map.of("방문", InquirySource.IN_PERSON,
            "전화", InquirySource.PHONE, "기타", InquirySource.OTHER);

    private InquiryInputs() {}

    /** Any of the three labels; list filters and event history may name the answered state. */
    static InquiryStatus status(String value, String field) {
        var status = STATUSES.get(value);
        if (status == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return status;
    }

    /** Create and status-change requests accept only the two unresolved states: answering is the only way to 답변 완료. */
    static InquiryStatus unresolvedStatus(String value, String field) {
        var status = status(value, field);
        if (status == InquiryStatus.ANSWERED) throw ApiException.validation(field, "접수 또는 처리 중만 입력할 수 있습니다.");
        return status;
    }

    static InquirySource source(String value, String field) {
        var source = SOURCES.get(value);
        if (source == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return source;
    }

    public static String label(InquiryStatus status) {
        return switch (status) {
            case RECEIVED -> "접수";
            case IN_PROGRESS -> "처리 중";
            case ANSWERED -> "답변 완료";
        };
    }

    public static String label(InquirySource source) {
        return switch (source) {
            case IN_PERSON -> "방문";
            case PHONE -> "전화";
            case OTHER -> "기타";
        };
    }

    /** An explicit null is allowed (no assignee); anything else must be a well-formed ID string. */
    static Long optionalId(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        return id(text, field);
    }

    static long id(String value, String field) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }
}
