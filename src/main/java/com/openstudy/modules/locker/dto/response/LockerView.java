package com.openstudy.modules.locker.dto.response;

/** Contract schema {@code Locker}. assignmentState is computed for each response and activeAssignment is null before a start. */
public record LockerView(String id, long version, String branchId, String lockerNo, long ratePer4WeeksKrw, String status,
        String assignmentState, LockerActiveAssignment activeAssignment) {}
