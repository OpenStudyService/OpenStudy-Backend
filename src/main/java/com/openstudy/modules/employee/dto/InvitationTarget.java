package com.openstudy.modules.employee.dto;

/** Credential-free, current employee/membership projection used by guarded invitation commands. */
public record InvitationTarget(long employeeId, boolean owner, String accountStatus,
        Long membershipId, String membershipRole, String membershipStatus) {}
