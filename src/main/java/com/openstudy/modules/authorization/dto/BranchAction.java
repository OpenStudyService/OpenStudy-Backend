package com.openstudy.modules.authorization.dto;

/** Role and explicit grant are independent requirements; STAFF never inherits administrator commands. */
public enum BranchAction {
    READ(null, false, false),
    MANAGE_BRANCH("operations:write", true, false),
    MANAGE_PEOPLE("people:manage", true, false),
    EDIT_PRODUCT("operations:write", false, false),
    MANAGE_PRODUCT("operations:write", true, false),
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
