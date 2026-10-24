package com.openstudy.modules.authorization.dto;
import java.time.Instant;
import java.util.List;
public record PermissionPreset(String id, long version, Instant createdAt, Instant updatedAt,
        String name, String role, boolean system, List<String> permissions) {}
