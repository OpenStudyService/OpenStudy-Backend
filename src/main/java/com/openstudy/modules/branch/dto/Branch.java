package com.openstudy.modules.branch.dto;

import java.util.List;

public record Branch(String id, long version, String createdAt, String updatedAt, String branchId,
        String code, String name, String address, String phone, String hours,
        List<WeeklyHours> weeklyHours, String status) {
    public record WeeklyHours(Integer weekday, Boolean isClosed, Integer opensMinute, Integer closesMinute) {}
}
