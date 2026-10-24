package com.openstudy.modules.authorization.dto;

public record PermissionDefinition(String code, String description, boolean adminOnly) {}
