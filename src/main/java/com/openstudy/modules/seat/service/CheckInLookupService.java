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
import org.springframework.stereotype.Service;
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
    private final CustomerService customers;
    private final PassService passes;
    private final SeatSessionRepository sessions;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    public CheckInLookupResult lookup(long actor, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("branchId", "memberNo", "phone");
        long branch = input.id("branchId");
        if (input.has("memberNo") == input.has("phone"))
            throw ApiException.validation("memberNo", "회원번호와 연락처 중 하나만 입력해 주세요.");
        boolean byMemberNo = input.has("memberNo");
        String memberNo = byMemberNo ? input.text("memberNo", 40) : null;
        String phone = byMemberNo ? null : customers.normalizedPhone(body);
        return commands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> {}, state -> {
            var matches = byMemberNo ? customers.matchMemberNo(memberNo) : customers.matchPhone(phone);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            record(state, actor, branch, byMemberNo ? BY_MEMBER_NO : BY_PHONE, matches, now, requestId);
            return new CheckInLookupResult(candidates(matches, now));
        });
    }

    private void record(AuthorizationState state, long actor, long branch, String method, List<ExactCustomerMatch> matches,
            Instant now, String requestId) {
        if (matches.isEmpty()) {
            // There is no customer row to point at, and the searched value is not stored: it may be a stranger's phone.
            audit.record(AuditEvent.change(actor, branch, ENTITY, null, "CHECK_IN_LOOKUP_NO_MATCH", null,
                    json.writeValueAsString(Map.of("method", method)), requestId));
        } else if (state.memberships().containsKey(branch)) {
            String reason = "입실 정확 일치 조회(" + method + ")";
            matches.forEach(match -> customers.recordLookup(match.id(), branch, actor, reason, now));
        } else {
            // The table references branch_memberships, which an OWNER may not have at this branch.
            var after = new LinkedHashMap<String, Object>();
            after.put("method", method);
            after.put("customerIds", matches.stream().map(match -> Long.toString(match.id())).toList());
            audit.record(AuditEvent.change(actor, branch, ENTITY, null, "CHECK_IN_LOOKUP", null, json.writeValueAsString(after), requestId));
        }
    }

    private List<CheckInCandidate> candidates(List<ExactCustomerMatch> matches, Instant now) {
        if (matches.isEmpty()) return List.of();
        var ids = matches.stream().map(ExactCustomerMatch::id).toList();
        var usable = passes.usableBy(ids, now);
        Map<Long, CheckInCandidate.ActiveSession> active = new HashMap<>();
        sessions.findByStatusAndCustomerIdIn(SeatSession.Status.ACTIVE, ids).forEach(session -> active.put(session.getCustomerId(),
                new CheckInCandidate.ActiveSession(session.getId().toString(), Long.toString(session.getBranchId()), session.getCheckedInAt())));
        return matches.stream().map(match -> new CheckInCandidate(match.customer(), match.status(), active.get(match.id()),
                usable.getOrDefault(match.id(), List.of()))).toList();
    }
}
