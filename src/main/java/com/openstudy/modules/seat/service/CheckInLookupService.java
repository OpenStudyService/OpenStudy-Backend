package com.openstudy.modules.seat.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.ExactCustomerMatch;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.seat.dto.response.CheckInCandidate;
import com.openstudy.modules.seat.dto.response.CheckInLookupResult;
import com.openstudy.modules.seat.entity.SeatSession;
import com.openstudy.modules.seat.repository.SeatSessionRepository;
import com.openstudy.modules.ticket.dto.UsablePass;
import com.openstudy.modules.ticket.service.PassService;

/**
 * The exact-match customer lookup for check-in (M5-D05·D07). This is its only entry point on purpose: the rate limit
 * deferred by M5-D08 attaches here. It returns the minimum needed to check in and never widens the general customer
 * scope (M4-D05). The lookup changes nothing but its own trail, so it has no Idempotency-Key.
 */
@Service
@RequiredArgsConstructor
public class CheckInLookupService {

    private static final String ENTITY = "customer-lookups";
    private static final String BY_MEMBER_NO = "회원번호";
    private static final String BY_PHONE = "연락처";
    private static final int MAX_MEMBER_NO_LENGTH = 40;

    private final CustomerService customerService;
    private final PassService passService;
    private final SeatSessionRepository seatSessionRepository;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CheckInLookupLimiter checkInLookupLimiter;

    public CheckInLookupResult lookup(long actor, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("branchId", "memberNo", "phone");
        long branch = input.id("branchId");

        if (input.has("memberNo") == input.has("phone")) {
            throw ApiException.validation("memberNo", "회원번호와 연락처 중 하나만 입력해 주세요.");
        }

        boolean byMemberNo = input.has("memberNo");
        String memberNo = byMemberNo ? input.text("memberNo", MAX_MEMBER_NO_LENGTH) : null;
        String phone = byMemberNo ? null : customerService.normalizedPhone(body);
        // #65: refused before anything is read, so a refusal tells nothing about customers. The first refusal of a
        // window is audited; branch_id stays empty because the branch has not been authorized yet.
        CheckInLookupLimiter.Refusal refusal = checkInLookupLimiter.check(actor, branch);

        if (refusal != null) {
            if (refusal.firstInWindow()) {
                String detail = objectMapper.writeValueAsString(Map.of("branchId", Long.toString(branch)));
                auditTrail.record(AuditEvent.denied(actor, null, ENTITY, null, "CHECK_IN_LOOKUP_RATE_LIMITED", detail,
                        requestId));
            }

            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> {}, state -> {
            List<ExactCustomerMatch> matches = byMemberNo
                    ? customerService.matchMemberNo(memberNo)
                    : customerService.matchPhone(phone);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            String method = byMemberNo ? BY_MEMBER_NO : BY_PHONE;
            recordLookup(state, actor, branch, method, matches, now, requestId);

            return new CheckInLookupResult(toCandidates(matches, now));
        });
    }

    private void recordLookup(
            AuthorizationState state,
            long actor,
            long branch,
            String method,
            List<ExactCustomerMatch> matches,
            Instant now,
            String requestId
    ) {
        if (matches.isEmpty()) {
            // There is no customer row to point at, and the searched value is not stored: it may be a stranger's phone.
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, null, "CHECK_IN_LOOKUP_NO_MATCH", null,
                    objectMapper.writeValueAsString(Map.of("method", method)), requestId));
        } else if (state.memberships().containsKey(branch)) {
            String reason = "입실 정확 일치 조회(" + method + ")";
            matches.forEach(match -> customerService.recordLookup(match.id(), branch, actor, reason, now));
        } else {
            // The table references branch_memberships, which an OWNER may not have at this branch.
            List<String> customerIds = matches.stream()
                    .map(match -> Long.toString(match.id()))
                    .toList();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("method", method);
            after.put("customerIds", customerIds);
            auditTrail.record(AuditEvent.change(actor, branch, ENTITY, null, "CHECK_IN_LOOKUP", null,
                    objectMapper.writeValueAsString(after), requestId));
        }
    }

    private List<CheckInCandidate> toCandidates(List<ExactCustomerMatch> matches, Instant now) {
        if (matches.isEmpty()) {
            return List.of();
        }

        List<Long> ids = matches.stream()
                .map(ExactCustomerMatch::id)
                .toList();
        Map<Long, List<UsablePass>> usable = passService.usableBy(ids, now);
        Map<Long, CheckInCandidate.ActiveSession> active = new HashMap<>();
        seatSessionRepository.findByStatusAndCustomerIdIn(SeatSession.Status.ACTIVE, ids)
                .forEach(session -> active.put(session.getCustomerId(), toActiveSession(session)));

        return matches.stream()
                .map(match -> new CheckInCandidate(
                        match.customer(),
                        match.status(),
                        active.get(match.id()),
                        usable.getOrDefault(match.id(), List.of())
                ))
                .toList();
    }

    private static CheckInCandidate.ActiveSession toActiveSession(SeatSession session) {
        return new CheckInCandidate.ActiveSession(
                session.getId().toString(),
                Long.toString(session.getBranchId()),
                session.getCheckedInAt()
        );
    }
}
