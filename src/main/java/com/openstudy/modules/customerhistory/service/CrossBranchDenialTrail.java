package com.openstudy.modules.customerhistory.service;

import java.util.LinkedHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.modules.authorization.service.AuthorizationService;

/**
 * The trail of a refused cross-branch summary, in a transaction of its own so that it outlives the refused one. Call it
 * only after that transaction has ended: inside it, this would wait for the locks of the very request it records. The
 * request's reason is never stored here (space-operations-api.md).
 */
@Service
@RequiredArgsConstructor
public class CrossBranchDenialTrail {
    private final AuditTrail audit;
    private final AuthorizationService policy;
    private final ObjectMapper json;

    /** Both IDs come from the client: one that the columns cannot hold is kept as text, so it cannot turn the 403 into a 500. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long actor, long customerId, long branch, String requestId) {
        var unmatched = new LinkedHashMap<String, String>();
        if (customerId < 1) unmatched.put("customerId", Long.toString(customerId));
        boolean knownBranch = policy.branchExists(branch);
        if (!knownBranch) unmatched.put("branchId", Long.toString(branch));
        audit.record(AuditEvent.denied(actor, knownBranch ? branch : null, CrossBranchSummaryService.ENTITY, customerId < 1 ? null : customerId,
                "CROSS_BRANCH_SUMMARY_DENIED", unmatched.isEmpty() ? null : json.writeValueAsString(unmatched), requestId));
    }
}
