package com.openstudy.modules.authorization.dto;

/** Role and explicit grant are independent requirements; STAFF never inherits administrator commands. */
public enum BranchAction {
    READ(null, false, false),
    MANAGE_BRANCH("operations:write", true, false),
    MANAGE_PEOPLE("people:manage", true, false),
    EDIT_PRODUCT("operations:write", false, false),
    MANAGE_PRODUCT("operations:write", true, false),
    /** M7 "MANAGER 이상": the branch role alone, with no separate grant (ADR-M7-001 M7-D01·D02·D04). */
    MANAGE_COLLABORATION(null, true, false),
    /** M5 seat ledger edits: operations:write at the seat's branch, allowed even while the branch is stopped. */
    WRITE_SEATS("operations:write", false, false),
    /** M5 seat stop/resume: MANAGER plus operations:write (seat-usage-api.md). */
    MANAGE_SEATS("operations:write", true, false),
    /** M6 room ledger with its stop and resume: operations:write at the room's branch, also while the branch is stopped. */
    WRITE_ROOMS("operations:write", false, false),
    /** M6 locker ledger with its stop, resume and branch rate: operations:write at the locker's branch, also while the branch is stopped. */
    WRITE_LOCKERS("operations:write", false, false),
    WRITE_MEMBERS("members:write", false, false),
    WRITE_FACILITIES("facilities:write", false, false),
    WRITE_NOTICES("notices:write", false, false),
    READ_PAYMENTS("payments:read", false, false),
    WRITE_PAYMENT_HISTORY("payments:write", false, false),
    NEW_PAYMENT("payments:write", false, true),
    READ_SALES("sales:read", false, false),
    READ_AI("ai:read", false, false),
    NEW_OPERATION("operations:write", false, true);

    public final String grant;
    public final boolean administrator;
    public final boolean requiresOperatingBranch;
    BranchAction(String grant, boolean administrator, boolean requiresOperatingBranch) {
        this.grant = grant;
        this.administrator = administrator;
        this.requiresOperatingBranch = requiresOperatingBranch;
    }
}
