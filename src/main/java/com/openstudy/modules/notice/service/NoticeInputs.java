package com.openstudy.modules.notice.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.notice.entity.NoticeStatus;
import com.openstudy.modules.notice.entity.NoticeTarget;

/** Wire labels and field rules of the notice contract. */
public final class NoticeInputs {
    private static final Map<String, NoticeStatus> STATUSES = Map.of("임시 저장", NoticeStatus.DRAFT,
            "게시 중", NoticeStatus.PUBLISHED, "게시 종료", NoticeStatus.ARCHIVED);
    private static final Map<String, NoticeTarget> TARGETS = Map.of("전체", NoticeTarget.ALL, "선택 지점", NoticeTarget.SELECTED_BRANCHES);

    private NoticeInputs() {}

    public static NoticeStatus status(String value, String field) {
        var status = STATUSES.get(value);
        if (status == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return status;
    }

    /** A notice is registered as a draft or already published; archiving is a status change of a published notice. */
    static NoticeStatus initialStatus(String value, String field) {
        var status = status(value, field);
        if (status == NoticeStatus.ARCHIVED) throw ApiException.validation(field, "임시 저장 또는 게시 중만 입력할 수 있습니다.");
        return status;
    }

    static NoticeTarget target(String value, String field) {
        var target = TARGETS.get(value);
        if (target == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return target;
    }

    public static String label(NoticeStatus status) {
        return switch (status) {
            case DRAFT -> "임시 저장";
            case PUBLISHED -> "게시 중";
            case ARCHIVED -> "게시 종료";
        };
    }

    public static String label(NoticeTarget target) {
        return switch (target) {
            case ALL -> "전체";
            case SELECTED_BRANCHES -> "선택 지점";
        };
    }

    /** 1~500 distinct well-formed branch IDs. */
    static Set<Long> branchIds(Object value, String field) {
        if (!(value instanceof List<?> items) || items.isEmpty() || items.size() > 500)
            throw ApiException.validation(field, "대상 지점을 1~500개 입력해 주세요.");
        Set<Long> result = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String text) || !text.matches("[1-9][0-9]{0,18}"))
                throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
            try {
                if (!result.add(Long.parseLong(text))) throw ApiException.validation(field, "중복 없는 지점 목록을 입력해 주세요.");
            } catch (NumberFormatException exception) {
                throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
            }
        }
        return result;
    }

    static boolean flag(Object value, String field) {
        if (!(value instanceof Boolean flag)) throw ApiException.validation(field, "true 또는 false를 입력해 주세요.");
        return flag;
    }
}
