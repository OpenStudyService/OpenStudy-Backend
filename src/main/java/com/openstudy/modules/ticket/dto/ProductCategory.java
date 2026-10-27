package com.openstudy.modules.ticket.dto;

public record ProductCategory(String id, long version, String createdAt, String updatedAt, String code, String name,
        String kind, String status) {}
