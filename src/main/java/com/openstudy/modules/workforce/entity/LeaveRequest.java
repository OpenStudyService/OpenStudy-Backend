package com.openstudy.modules.workforce.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The part of a leave request that a shift is checked against (M8-D06): who, which days and whether it was approved.
 * M8 only reads it; requesting and approving leave is M12, which maps the rest of the row. The table has no branch, so a
 * leave applies to the employee at every branch.
 */
@Entity
@Table(name = "leave_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveRequest {
    public enum Status { REQUESTED, APPROVED, REJECTED, CANCELLED }

    @Id
    private Long id;
    @Column(name = "employee_id", nullable = false, insertable = false, updatable = false)
    private long employeeId;
    @Column(name = "starts_on", nullable = false, insertable = false, updatable = false)
    private LocalDate startsOn;
    @Column(name = "ends_on", nullable = false, insertable = false, updatable = false)
    private LocalDate endsOn;
    @Enumerated(EnumType.STRING) @Column(nullable = false, insertable = false, updatable = false)
    private Status status;
}
