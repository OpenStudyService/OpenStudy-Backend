package com.openstudy.modules.customerhistory.service;

import java.util.LinkedHashMap;
import java.util.Map;
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

    private static final long MIN_ID = 1;

    private final AuditTrail auditTrail;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    /**
     * Both IDs come from the client: one that the columns cannot hold is kept as text, so it cannot turn the 403 into
     * a 500.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long actor, long customerId, long branch, String requestId) {
        Map<String, String> unmatched = new LinkedHashMap<>();
        boolean validCustomerId = customerId >= MIN_ID;
        if (!validCustomerId) {
            unmatched.put("customerId", Long.toString(customerId));
        }

        boolean knownBranch = authorizationService.branchExists(branch);
        if (!knownBranch) {
            unmatched.put("branchId", Long.toString(branch));
        }

        Long branchId = knownBranch ? branch : null;
        Long entityId = validCustomerId ? customerId : null;
        String detail = unmatched.isEmpty() ? null : objectMapper.writeValueAsString(unmatched);

        auditTrail.record(AuditEvent.denied(
                actor,
                branchId,
                CrossBranchSummaryService.ENTITY,
                entityId,
                "CROSS_BRANCH_SUMMARY_DENIED",
                detail,
                requestId
        ));
    }
}
