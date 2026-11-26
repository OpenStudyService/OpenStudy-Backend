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

    private static final List<String> SECRET_KEYS = List.of(
            "password",
            "token",
            "secret",
            "hash",
            "csrf",
            "session",
            "credential"
    );
    private static final List<String> CONTACT_KEYS = List.of("phone", "contact");
    private static final int VISIBLE_DIGITS = 4;

    private AuditSnapshotMasking() {
    }

    /** A masked copy; the input is not changed. */
    static JsonNode mask(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        JsonNode copy = node.deepCopy();
        maskInPlace(copy);

        return copy;
    }

    private static void maskInPlace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            maskObject(object);
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                maskInPlace(child);
            }
        }
    }

    private static void maskObject(ObjectNode object) {
        List<String> names = new ArrayList<>();
        object.properties().forEach(entry -> names.add(entry.getKey()));

        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            JsonNode value = object.get(name);
            boolean secret = SECRET_KEYS.stream().anyMatch(key::contains);
            boolean contact = CONTACT_KEYS.stream().anyMatch(key::contains);

            if (secret) {
                object.put(name, HIDDEN);
            } else if (contact && value.isString()) {
                object.put(name, maskAllButLastFourDigits(value.asString()));
            } else {
                maskInPlace(value);
            }
        }
    }

    /** Every digit but the last four becomes '*'; separators stay, e.g. 010-1234-5678 → ***-****-5678. */
    static String maskAllButLastFourDigits(String value) {
        long digits = value.chars()
                .filter(Character::isDigit)
                .count();
        StringBuilder masked = new StringBuilder(value.length());
        long seen = 0;

        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                seen++;
                masked.append(seen > digits - VISIBLE_DIGITS ? c : '*');
            } else {
                masked.append(c);
            }
        }

        return masked.toString();
    }
}
