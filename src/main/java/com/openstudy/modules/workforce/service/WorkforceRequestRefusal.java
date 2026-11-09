package com.openstudy.modules.workforce.service;

import java.math.BigDecimal;
import java.util.List;

import com.openstudy.modules.workforce.dto.response.ShiftConflict;
import com.openstudy.modules.workforce.dto.response.WorkforceRequestConflictError;

/**
 * A refusal of an M12 request command whose answer carries more than the common error fields: the shifts in the way,
 * the days missing, the overlapping requests, or the version to reload with (workforce-requests-api.md "추가 오류
 * 코드"). Thrown inside the command, so the transaction rolls back like any other refusal.
 */
public class WorkforceRequestRefusal extends RuntimeException {
    private final int status;
    private final String code;
    private Long latestVersion;
    private String reloadUrl;
    private List<ShiftConflict> conflicts;
    private BigDecimal requiredDays;
    private BigDecimal availableDays;
    private List<String> overlappingRequestIds;
    private String pendingSwapId;
    private String shiftId;
    private Long approvedVersion;

    WorkforceRequestRefusal(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** 403 SELF_DECISION_FORBIDDEN: nobody decides their own request (2026-11-09 user decision). */
    static WorkforceRequestRefusal selfDecision() {
        return new WorkforceRequestRefusal(403, "SELF_DECISION_FORBIDDEN", "본인 신청은 다른 관리자가 처리해야 합니다.");
    }

    /** 409 INVALID_STATE with the version and path to reload, so a repeated command learns what happened. */
    static WorkforceRequestRefusal invalidState(String message, long latestVersion, String reloadUrl) {
        var refusal = new WorkforceRequestRefusal(409, "INVALID_STATE", message);
        refusal.latestVersion = latestVersion;
        refusal.reloadUrl = reloadUrl;
        return refusal;
    }

    static WorkforceRequestRefusal balance(BigDecimal required, BigDecimal available) {
        var refusal = new WorkforceRequestRefusal(409, "LEAVE_BALANCE_INSUFFICIENT", "휴가 잔여 일수가 부족합니다.");
        refusal.requiredDays = required;
        refusal.availableDays = available;
        return refusal;
    }

    static WorkforceRequestRefusal overlap(List<String> requestIds) {
        var refusal = new WorkforceRequestRefusal(409, "LEAVE_OVERLAP", "같은 기간에 신청하거나 승인된 휴가가 있습니다.");
        refusal.overlappingRequestIds = requestIds.isEmpty() ? null : List.copyOf(requestIds);
        return refusal;
    }

    static WorkforceRequestRefusal shifts(List<ShiftConflict> conflicts) {
        var refusal = new WorkforceRequestRefusal(409, "LEAVE_SHIFT_CONFLICT", "휴가 기간에 예정 근무가 있습니다. 근무를 정리한 뒤 승인해 주세요.");
        refusal.conflicts = List.copyOf(conflicts);
        return refusal;
    }

    /** The shift's own conflict answer (M8 ShiftConflict) for a swap: the replacement's or the original employee's time is taken. */
    static WorkforceRequestRefusal shiftConflict(List<ShiftConflict> conflicts) {
        var refusal = new WorkforceRequestRefusal(409, "SHIFT_CONFLICT", "같은 직원의 다른 근무 또는 휴가와 시간이 겹칩니다.");
        refusal.conflicts = List.copyOf(conflicts);
        return refusal;
    }

    static WorkforceRequestRefusal pendingSwap(long swap) {
        var refusal = new WorkforceRequestRefusal(409, "SWAP_PENDING", "이 근무에는 처리되지 않은 대체근무 신청이 있습니다.");
        refusal.pendingSwapId = Long.toString(swap);
        return refusal;
    }

    /** The shift is not what the manager saw, or it changed since the request: reload it (#109). */
    static WorkforceRequestRefusal shiftVersion(long shift, long latestVersion) {
        var refusal = new WorkforceRequestRefusal(409, "SHIFT_VERSION_CONFLICT", "근무가 바뀌었습니다. 최신 근무를 확인한 뒤 다시 처리해 주세요.");
        refusal.shiftId = Long.toString(shift);
        refusal.latestVersion = latestVersion;
        refusal.reloadUrl = "/api/shifts/" + shift;
        return refusal;
    }

    /** M12-D09: the shift changed after the approval, so the original assignment cannot be put back. */
    static WorkforceRequestRefusal restore(long shift, long latestVersion, long approvedVersion) {
        var refusal = new WorkforceRequestRefusal(409, "SWAP_RESTORE_CONFLICT", "승인 뒤 근무가 바뀌어 원래대로 되돌릴 수 없습니다.");
        refusal.shiftId = Long.toString(shift);
        refusal.latestVersion = latestVersion;
        refusal.approvedVersion = approvedVersion;
        refusal.reloadUrl = "/api/shifts/" + shift;
        return refusal;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Long latestVersion() { return latestVersion; }
    public String reloadUrl() { return reloadUrl; }
    public List<ShiftConflict> conflicts() { return conflicts; }
    public BigDecimal requiredDays() { return requiredDays; }
    public BigDecimal availableDays() { return availableDays; }
    public List<String> overlappingRequestIds() { return overlappingRequestIds; }
    public String pendingSwapId() { return pendingSwapId; }
    public String shiftId() { return shiftId; }
    public Long approvedVersion() { return approvedVersion; }

    /** The contract's WorkforceRequestConflictError of this refusal. */
    public WorkforceRequestConflictError error(String requestId) {
        return new WorkforceRequestConflictError(code, getMessage(), requestId, null,
                latestVersion, reloadUrl, conflicts, requiredDays, availableDays, overlappingRequestIds, pendingSwapId, shiftId,
                approvedVersion);
    }
}
