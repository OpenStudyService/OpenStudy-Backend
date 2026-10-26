package com.openstudy.modules.workspace.dto;

/** No changed record, target employee, credential, or branch collection belongs in an event. */
public record WorkspaceEvent(String type, String resource, String entityId, String branchId, String at) {}
