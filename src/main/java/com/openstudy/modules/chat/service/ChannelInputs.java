package com.openstudy.modules.chat.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.chat.entity.ConversationKind;
import com.openstudy.modules.chat.entity.MessageKind;

/** Wire labels and field rules of the channel contract. Text lengths count characters, not UTF-16 units. */
final class ChannelInputs {
    private static final int MAX_MEMBERS = 500;
    private static final String ID = "[1-9][0-9]{0,18}";

    private ChannelInputs() {}

    static ConversationKind kind(String value) {
        return switch (value) {
            case "전체" -> ConversationKind.GLOBAL_CHANNEL;
            case "지점" -> ConversationKind.BRANCH_CHANNEL;
            default -> throw ApiException.validation("kind", "허용되지 않은 값입니다.");
        };
    }

    static String label(ConversationKind kind) {
        return kind == ConversationKind.GLOBAL_CHANNEL ? "전체" : "지점";
    }

    static String label(MessageKind kind) {
        return kind == MessageKind.TEXT ? "텍스트" : "업무 카드";
    }

    /** Optional and nullable; 0~500 characters. */
    static String description(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text) || length(text) > 500) throw ApiException.validation("description", "설명은 500자 이하여야 합니다.");
        return text.strip();
    }

    /** Distinct well-formed employee IDs, 500 at most. An empty list is valid. */
    static Set<Long> employeeIds(Object value, String field) {
        if (!(value instanceof List<?> items) || items.size() > MAX_MEMBERS)
            throw ApiException.validation(field, "참여자는 500명 이하로 입력해 주세요.");
        Set<Long> result = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String text)) throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
            if (!result.add(id(text, field))) throw ApiException.validation(field, "중복 없는 목록을 입력해 주세요.");
        }
        return result;
    }

    /** Stored as sent, so nothing is stripped; blank means empty. Unpaired surrogates could not be stored unchanged. */
    static String messageText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || length(text) > 4000 || !wellFormed(text))
            throw ApiException.validation("text", "메시지는 1~4000자로 입력해 주세요.");
        return text;
    }

    static String clientKey(Object value) {
        if (!(value instanceof String key) || key.isBlank() || length(key) > 100 || !wellFormed(key))
            throw ApiException.validation("clientMessageKey", "1~100자 키가 필요합니다.");
        return key;
    }

    /** The cursor of a feed: no value reads from the newest message. */
    static long before(String value) {
        return value == null ? Long.MAX_VALUE : id(value, "before");
    }

    static int limit(String value) {
        if (value == null) return 50;
        if (!value.matches("[1-9][0-9]{0,2}") || Integer.parseInt(value) > 100)
            throw ApiException.validation("limit", "1~100 사이로 입력해 주세요.");
        return Integer.parseInt(value);
    }

    static long id(String value, String field) {
        if (!value.matches(ID)) throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }

    private static int length(String text) { return text.codePointCount(0, text.length()); }

    private static boolean wellFormed(String text) {
        return text.codePoints().noneMatch(point -> point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE);
    }
}
