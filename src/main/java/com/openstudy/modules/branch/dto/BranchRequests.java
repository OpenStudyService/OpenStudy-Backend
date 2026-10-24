package com.openstudy.modules.branch.dto;

import java.util.List;
import jakarta.validation.constraints.*;

public final class BranchRequests {
    private BranchRequests() {}
    public record Create(@NotBlank @Size(max=120) String name, @NotBlank @Size(max=300) String address,
            @NotNull @Size(max=30) String phone, @NotNull List<Branch.WeeklyHours> weeklyHours,
            @NotNull String status) {}
    public record Update(@NotNull Long version,
            @Size(min=1,max=120) String name,
            @Size(min=1,max=300) String address,
            @Size(max=30) String phone,
            List<Branch.WeeklyHours> weeklyHours) {}
    public record Status(@NotNull Long version, @NotNull String status) {}
}
