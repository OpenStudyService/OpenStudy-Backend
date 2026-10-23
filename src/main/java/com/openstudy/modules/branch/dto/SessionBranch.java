package com.openstudy.modules.branch.dto;

public record SessionBranch(String id, String branchId, long version, String createdAt, String updatedAt,
        String name, String address, String phone, String hours, String status) {
}
