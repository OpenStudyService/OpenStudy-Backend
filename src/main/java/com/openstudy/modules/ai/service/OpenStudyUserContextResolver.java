package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.spi.PolicyBinding;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.branch.service.SessionBranchService;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves OpenStudy employee sessions and branch memberships into DomainLens {@link UserContext}
 * and enforces {@code ADR-M10-001} D06 & {@code ADR-M9-001} D04 branch scope authorization ({@code OS-DL-0501}).
 */
@Service
@Transactional(readOnly = true)
public class OpenStudyUserContextResolver {

    public record ResolvedActor(
            long employeeId,
            String email,
            boolean owner,
            Set<Long> aiBranches,
            Set<Long> salesBranches,
            Set<String> allGrants,
            AuthorizationState state
    ) {
        public UserContext toUserContext(String scopeKey, Set<Long> effectiveBranches) {
            Set<String> roles = owner ? Set.of("OWNER", "MANAGER") : Set.of("EMPLOYEE");
            Map<String, Object> scopeMap = new LinkedHashMap<>();
            scopeMap.put("scopeKey", scopeKey == null ? "all" : scopeKey);
            scopeMap.put("branchIds", List.copyOf(effectiveBranches));
            scopeMap.put("aiBranches", List.copyOf(aiBranches));
            scopeMap.put("salesBranches", List.copyOf(salesBranches));
            scopeMap.put("grants", Set.copyOf(allGrants));
            return new UserContext() {
                @Override public String subjectId() { return Long.toString(employeeId); }
                @Override public Set<String> roles() { return roles; }
                @Override public PolicyBinding policyBinding() {
                    return new PolicyBinding() {
                        @Override public String roleKey() { return owner ? "OWNER" : "EMPLOYEE"; }
                        @Override public Map<String, Object> scope() { return scopeMap; }
                    };
                }
            };
        }
    }

    public record ScopeDecision(
            boolean allowed,
            int httpStatus,
            String errorCode,
            String resultCategory,
            String behavior,
            String message,
            Set<Long> effectiveBranches
    ) {
        public static ScopeDecision ok(Set<Long> branches) {
            return new ScopeDecision(true, 200, null, "AUTHORIZED", "OK", null, Set.copyOf(branches));
        }

        public static ScopeDecision denied(int status, String code, String category, String behavior, String message) {
            return new ScopeDecision(false, status, code, category, behavior, message, Set.of());
        }
    }

    private final AuthorizationService authorizationService;
    private final EntityManager entityManager;

    private final SessionBranchService branches;

    public OpenStudyUserContextResolver(AuthorizationService authorizationService, EntityManager entityManager,
            SessionBranchService branches) {
        this.authorizationService = authorizationService;
        this.entityManager = entityManager;
        this.branches = branches;
    }

    /**
     * The signed-in employee only. A request body's actor never replaces the session, and a call without a signed-in
     * employee fails closed instead of falling back to an owner: /api/ai/** needs a session and CSRF like every other
     * API (SecurityConfiguration), so this guards direct service calls.
     */
    public ResolvedActor resolve(Authentication auth, String ignoredActorOverride) {
        if (auth != null && auth.getName() != null && auth.getName().matches("[1-9][0-9]*")) {
            return resolveByEmployeeId(Long.parseLong(auth.getName()));
        }
        throw new AuthenticationCredentialsNotFoundException("A signed-in employee is required for AI requests");
    }

    public ResolvedActor resolveByActorIdentifier(String actor) {
        String trimmed = actor.trim();
        if ("ACTIVE_OWNER".equalsIgnoreCase(trimmed) || "OWNER".equalsIgnoreCase(trimmed)) {
            return resolveByEmployeeId(1L);
        }
        if (trimmed.matches("[1-9][0-9]*")) {
            return resolveByEmployeeId(Long.parseLong(trimmed));
        }
        List<Long> ids = entityManager.createQuery(
                        "SELECT e.id FROM Employee e WHERE LOWER(e.email) = :email", Long.class)
                .setParameter("email", trimmed.toLowerCase(Locale.ROOT))
                .getResultList();
        if (!ids.isEmpty()) {
            return resolveByEmployeeId(ids.getFirst());
        }
        return resolveByEmployeeId(1L);
    }

    public ResolvedActor resolveByEmployeeId(long employeeId) {
        AuthorizationState state = authorizationService.current(employeeId);
        List<String> emails = entityManager.createQuery(
                        "SELECT e.email FROM Employee e WHERE e.id = :id", String.class)
                .setParameter("id", employeeId)
                .getResultList();
        String email = emails.isEmpty() ? ("emp-" + employeeId) : emails.getFirst();

        Set<Long> aiBranches = new LinkedHashSet<>();
        Set<Long> salesBranches = new LinkedHashSet<>();
        Set<String> grants = new LinkedHashSet<>();

        for (Long branchId : state.branches().keySet().stream().sorted().toList()) {
            if (authorizationService.permits(state, branchId, BranchAction.READ_AI)) {
                aiBranches.add(branchId);
                grants.add("ai:read");
            }
            if (authorizationService.permits(state, branchId, BranchAction.READ_SALES)) {
                salesBranches.add(branchId);
                grants.add("sales:read");
            }
            var m = state.memberships().get(branchId);
            if (m != null && "ACTIVE".equals(m.status())) {
                grants.addAll(m.grants());
            }
        }
        if (state.owner()) {
            grants.add("ai:read");
            grants.add("sales:read");
            grants.add("people:manage");
        }

        return new ResolvedActor(
                employeeId,
                email,
                state.owner(),
                Set.copyOf(aiBranches),
                Set.copyOf(salesBranches),
                Set.copyOf(grants),
                state
        );
    }

    /**
     * Evaluates branch scope and domain permissions for a natural-language question ({@code ADR-M10-001} D06, {@code NEG-001}..{@code NEG-004}, {@code NEG-015}, {@code NEG-016}).
     */
    public ScopeDecision authorizeQuestion(
            ResolvedActor actor,
            String requestedScope,
            String question,
            boolean isSalesDomain
    ) {
        String q = question == null ? "" : question.trim();

        // 1. Basic ai:read permission check
        if (actor.aiBranches().isEmpty()) {
            return ScopeDecision.denied(
                    403,
                    "AI_PERMISSION_DENIED",
                    "AUTHORIZATION_DENIED",
                    "403",
                    "AI 분석 조회(ai:read) 권한이 없습니다."
            );
        }

        // 2. Detect explicit branch mentions in question text
        Set<Long> mentionedBranches = mentionedBranches(q);
        boolean mentionsAllBranches = q.contains("모든 지점") || q.contains("전 지점") || q.contains("전체 지점") || q.contains("지점별");

        // 3. Explicit branch scope parameter if numeric
        Long explicitScopeBranch = null;
        if (requestedScope != null && !requestedScope.isBlank() && !"all".equalsIgnoreCase(requestedScope.trim())) {
            String s = requestedScope.trim();
            if (!s.matches("[1-9][0-9]*")) {
                return ScopeDecision.denied(
                        422,
                        "VALIDATION_FAILED",
                        "INVALID_SCOPE",
                        "422",
                        "올바른 지점 범위를 입력해 주세요."
                );
            }
            explicitScopeBranch = Long.parseLong(s);
            if (!actor.aiBranches().contains(explicitScopeBranch)) {
                return ScopeDecision.denied(
                        403,
                        "BRANCH_SCOPE_DENIED",
                        "AUTHORIZATION_DENIED",
                        "403_OR_SCOPE_REINPUT",
                        "선택한 지점에 대한 AI 조회 권한이 없습니다."
                );
            }
        }

        // 4. Intersection rule (M9-D04 / M10-D06): If the user explicitly names any branch they lack permission for,
        //    never silently drop the branch or return a partial sum — reject immediately! (NEG-001, NEG-002)
        for (Long mentionedBranch : mentionedBranches) {
            if (!actor.aiBranches().contains(mentionedBranch)) {
                return ScopeDecision.denied(
                        403,
                        "BRANCH_SCOPE_DENIED",
                        "AUTHORIZATION_DENIED",
                        "403_OR_SCOPE_REINPUT",
                        "요청하신 지점에 대한 조회 권한이 없습니다. 허용된 지점만 지정해 주세요."
                );
            }
            if (isSalesDomain && !actor.salesBranches().contains(mentionedBranch)) {
                return ScopeDecision.denied(
                        403,
                        "SALES_PERMISSION_DENIED",
                        "AUTHORIZATION_DENIED",
                        "403_OR_SCOPE_REINPUT",
                        "요청하신 지점의 매출 조회(sales:read) 권한이 없습니다."
                );
            }
        }

        // 5. If non-owner asks for "모든 지점" / "지점별" when they only have single-branch access (NEG-001, NEG-004)
        if (!actor.owner() && mentionsAllBranches && !actor.aiBranches().containsAll(branches.allNames().keySet())) {
            return ScopeDecision.denied(
                    403,
                    "BRANCH_SCOPE_DENIED",
                    "AUTHORIZATION_DENIED",
                    "403_OR_SCOPE_REINPUT",
                    "전 지점 비교/조회 권한이 없습니다. 소속 지점 범위로 다시 질문해 주세요."
            );
        }

        // 6. Determine target branches
        Set<Long> targetBranches = new LinkedHashSet<>();
        if (!mentionedBranches.isEmpty()) {
            targetBranches.addAll(mentionedBranches);
        } else if (explicitScopeBranch != null) {
            targetBranches.add(explicitScopeBranch);
        } else if (actor.owner()) {
            // An owner's "all" is every branch they may read (M9-D04 union).
            targetBranches.addAll(actor.aiBranches());
        } else {
            // Non-owner with scope="all" and no explicit branch mentioned in the question (NEG-015, NEG-016)
            return ScopeDecision.denied(
                    403,
                    "AUTHORIZATION_SCOPE_CHECK",
                    "AUTHORIZATION_SCOPE_CHECK",
                    "DENY_OR_SCOPE_REINPUT",
                    "지점 소속 범위 확인이 필요합니다. 소속 지점을 선택하거나 질문에 지점명을 명시해 주세요."
            );
        }

        // 7. Sales domain requires sales:read on ALL target branches (ADR-M10-001 D06)
        if (isSalesDomain) {
            for (Long bId : targetBranches) {
                if (!actor.salesBranches().contains(bId)) {
                    return ScopeDecision.denied(
                            403,
                            "SALES_PERMISSION_DENIED",
                            "AUTHORIZATION_DENIED",
                            "403",
                            "매출 질문은 대상 지점의 매출 조회(sales:read) 권한이 필요합니다."
                    );
                }
            }
        }

        return ScopeDecision.ok(targetBranches);
    }

    /**
     * Branches named in the question, matched against the real branch names: the full name ("강남점") or, for a name
     * ending in "점", its stem ("강남"). The result is only used to check access and pick the target; a branch the
     * requester cannot read is refused without repeating its name (#94).
     */
    public Set<Long> mentionedBranches(String question) {
        if (question == null || question.isBlank()) return Set.of();
        Set<Long> found = new LinkedHashSet<>();
        branches.allNames().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(branch -> {
            String name = branch.getValue() == null ? "" : branch.getValue().trim();
            if (name.isEmpty()) return;
            String stem = name.endsWith("점") && name.length() >= 3 ? name.substring(0, name.length() - 1) : null;
            if (question.contains(name) || (stem != null && question.contains(stem))) found.add(branch.getKey());
        });
        return Set.copyOf(found);
    }

    /** The branch's real name, for result titles and choices the requester may already see. */
    public String branchName(long branchId) {
        return branches.names(List.of(branchId)).getOrDefault(branchId, "지점 " + branchId);
    }

    public static String toCsv(Collection<Long> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) return "";
        return branchIds.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }
}
