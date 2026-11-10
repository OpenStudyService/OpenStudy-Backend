package com.openstudy.modules.audit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Masks the before/after snapshots of an audit row once more when it is read (account-collab-api.md "민감 값 처리"). Rows
 * are written without secrets already; this keeps a writer's mistake from reaching the screen. Keys are matched by name,
 * case-insensitively, at any depth.
 */
final class AuditSnapshotMasking {
    static final String HIDDEN = "***";
    private static final List<String> SECRET_KEYS = List.of("password", "token", "secret", "hash", "csrf", "session", "credential");
    private static final List<String> CONTACT_KEYS = List.of("phone", "contact");

    private AuditSnapshotMasking() {}

    /** A masked copy; the input is not changed. */
    static JsonNode mask(JsonNode node) {
        if (node == null || node.isNull()) return null;
        var copy = node.deepCopy();
        walk(copy);
        return copy;
    }

    private static void walk(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new ArrayList<String>();
            object.properties().forEach(entry -> names.add(entry.getKey()));
            for (String name : names) {
                String key = name.toLowerCase(Locale.ROOT);
                JsonNode value = object.get(name);
                if (SECRET_KEYS.stream().anyMatch(key::contains)) {
                    object.put(name, HIDDEN);
                } else if (CONTACT_KEYS.stream().anyMatch(key::contains)) {
                    if (value.isString()) object.put(name, lastFourDigits(value.asString()));
                    else walk(value);
                } else {
                    walk(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) walk(child);
        }
    }

    /** Every digit but the last four becomes '*'; separators stay, e.g. 010-1234-5678 → ***-****-5678. */
    static String lastFourDigits(String value) {
        long digits = value.chars().filter(Character::isDigit).count();
        var masked = new StringBuilder(value.length());
        long seen = 0;
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                seen++;
                masked.append(seen > digits - 4 ? c : '*');
            } else {
                masked.append(c);
            }
        }
        return masked.toString();
    }
}
