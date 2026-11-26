package com.openstudy.modules.customerhistory.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.customerhistory.dto.response.CrossBranchSummary;
import com.openstudy.modules.customerhistory.dto.response.CrossBranchUsage;
import com.openstudy.modules.locker.service.LockerAssignmentService;
import com.openstudy.modules.reservation.service.RoomReservationService;
import com.openstudy.modules.seat.service.SeatSessionService;
import com.openstudy.modules.ticket.service.PassService;

/**
 * The minimal summary of a customer across all branches for front-desk service (POL-01, FR-MEM-03, M6-D06). It answers one
 * request and grants nothing: the general lookup scope (M4-D05) is unchanged afterwards. The permission is decided first,
 * so a refused employee gets the same 403 for a customer who exists and one who does not, and an ID is not an existence
 * oracle. The summary changes nothing but its own trail, so it has no Idempotency-Key.
 */
@Service
@RequiredArgsConstructor
public class CrossBranchSummaryService {

    static final String ENTITY = "customers";

    private static final String SEAT = "좌석";
    private static final String ROOM = "스터디룸";
    private static final String LOCKER = "사물함";
    private static final int RECENT_USAGES = 50;
    private static final int MAX_REASON_LENGTH = 500;

    private final CustomerService customerService;
    private final PassService passService;
    private final SeatSessionService seatSessionService;
    private final RoomReservationService roomReservationService;
    private final LockerAssignmentService lockerAssignmentService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final CrossBranchDenialTrail crossBranchDenialTrail;
    private final Clock clock;

    /** Deliberately not transactional: the refusal is recorded after the command's transaction has rolled back. */
    public CrossBranchSummary getSummary(long actor, long id, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("branchId", "reason");
        long branch = input.id("branchId");
        String reason = input.text("reason", MAX_REASON_LENGTH);

        try {
            return authorizedCommands.activeEmployee(actor, () -> {}, state -> {
                if (!isPermitted(state, branch)) {
                    throw AuthorizationService.forbidden();
                }

                if (customerService.summaries(Set.of(id)).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                }

                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                record(state, actor, id, branch, reason, now, requestId);

                return new CrossBranchSummary(Long.toString(id), passService.heldBy(id, now), findUsages(id, now), now);
            });
        } catch (AccessDeniedException refused) {
            crossBranchDenialTrail.record(actor, id, branch, requestId);
            throw refused;
        }
    }

    /**
     * OWNER passes both; a MANAGER needs only the role and anyone else the grant, each at the serving branch and valid
     * now.
     */
    private boolean isPermitted(AuthorizationState state, long branch) {
        return authorizationService.permits(state, branch, BranchAction.SERVE_CROSS_BRANCH_AS_MANAGER)
                || authorizationService.permits(state, branch, BranchAction.SERVE_CROSS_BRANCH);
    }

    private void record(
            AuthorizationState state,
            long actor,
            long id,
            long branch,
            String reason,
            Instant now,
            String requestId
    ) {
        if (state.memberships().containsKey(branch)) {
            customerService.recordCrossBranchSummary(id, branch, actor, reason, now);
        } else {
            // The table references branch_memberships, which an OWNER may not have at this branch.
            auditTrail.record(AuditEvent.change(
                    actor,
                    branch,
                    ENTITY,
                    id,
                    "CROSS_BRANCH_SUMMARY",
                    reason,
                    null,
                    null,
                    requestId
            ));
        }
    }

    /** Each kind brings at most the limit, so the merged top holds whatever the mix, latest first. */
    private List<CrossBranchUsage> findUsages(long id, Instant now) {
        List<CrossBranchUsage> merged = new ArrayList<>();
        seatSessionService.recentUsages(id, RECENT_USAGES)
                .forEach(usage -> merged.add(toUsage(SEAT, usage.branchId(), usage.startAt(), usage.endAt())));
        roomReservationService.recentUsages(id, RECENT_USAGES)
                .forEach(usage -> merged.add(toUsage(ROOM, usage.branchId(), usage.startAt(), usage.endAt())));
        lockerAssignmentService.recentUsages(id, RECENT_USAGES, now)
                .forEach(usage -> merged.add(toUsage(LOCKER, usage.branchId(), usage.startAt(), usage.endAt())));

        return merged.stream()
                .sorted(Comparator.comparing(CrossBranchUsage::startAt).reversed())
                .limit(RECENT_USAGES)
                .toList();
    }

    private static CrossBranchUsage toUsage(String kind, long branch, Instant start, Instant end) {
        return new CrossBranchUsage(kind, Long.toString(branch), start, end);
    }
}
