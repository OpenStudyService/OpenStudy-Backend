package com.openstudy.modules.invitation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.PasswordPolicy;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.employee.dto.InvitationTarget;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.AcceptanceResult;
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.dto.InvitationPreview;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository.Link;

/**
 * Public link commands. The raw token and password exist only in request memory: lookups use the SHA-256
 * hash, and neither value reaches the database, audit rows, logs or responses.
 */
@Service
@RequiredArgsConstructor
public class InvitationAcceptanceService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,512}");
    private static final String NEW_ACCOUNT = "NEW_ACCOUNT";
    private static final String EXISTING_ACCOUNT = "EXISTING_ACCOUNT";
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final String ALREADY_REGISTERED = "이미 가입된 이메일입니다. 로그인한 뒤 초대를 수락해 주세요.";

    private final InvitationManagementRepository invitationManagementRepository;
    private final AuthorizedCommands authorizedCommands;
    private final EmployeeAccountService employeeAccountService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional(readOnly = true)
    public InvitationPreview preview(Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("token");
        Link link = requireUsable(invitationManagementRepository.link(hash(readToken(input))), clock.instant());
        String acceptanceMode = employeeAccountService.exists(link.email()) ? EXISTING_ACCOUNT : NEW_ACCOUNT;

        return new InvitationPreview(
                link.name(),
                link.email(),
                new InvitationPreview.Branch(Long.toString(link.branch()), link.branchName()),
                "MANAGER".equals(link.role()) ? "admin" : "staff",
                link.expiresAt(),
                acceptanceMode
        );
    }

    /**
     * @param sessionEmployee authenticated employee of the current session, or null when anonymous
     * @param sessionMode WORKSPACE/APPROVAL_ONLY of that session, or null
     */
    public AcceptanceResult accept(
            Map<String, Object> body,
            Long sessionEmployee,
            String sessionMode,
            String requestId
    ) {
        StrictInput input = new StrictInput(body);
        boolean create = NEW_ACCOUNT.equals(input.choice("mode", NEW_ACCOUNT, EXISTING_ACCOUNT));

        if (create) {
            input.only("mode", "token", "email", "password");
        } else {
            input.only("mode", "token", "email");
        }

        byte[] hash = hash(readToken(input));
        String email = input.text("email", MAX_EMAIL_LENGTH).toLowerCase(Locale.ROOT);
        String password = create ? PasswordPolicy.validateNew("password", input.values().get("password")) : null;
        requireSessionFor(create, sessionEmployee, sessionMode);

        // Reject unknown links before spending BCrypt work, then verify again under the invitation lock.
        Link candidate = requireUsable(invitationManagementRepository.link(hash), clock.instant());

        if (!candidate.email().equals(email)) {
            throw linkInvalid();
        }

        String passwordHash = create ? passwordEncoder.encode(password) : null;

        return authorizedCommands.publicCommand(
                () -> invitationManagementRepository.lock(candidate.id()),
                () -> acceptLocked(candidate.id(), hash, create, passwordHash, sessionEmployee, requestId)
        );
    }

    /**
     * A new account never attaches to someone's existing session; an existing account proves itself
     * with its own business session instead of a password sent to this endpoint.
     */
    private static void requireSessionFor(boolean create, Long sessionEmployee, String sessionMode) {
        if (create && sessionEmployee != null) {
            throw AuthorizationService.forbidden();
        }

        if (!create && sessionEmployee == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        if (!create && !SessionBoundaryPolicy.WORKSPACE.equals(sessionMode)) {
            throw AuthorizationService.forbidden();
        }
    }

    private AcceptanceResult acceptLocked(
            long invitationId,
            byte[] hash,
            boolean create,
            String passwordHash,
            Long sessionEmployee,
            String requestId
    ) {
        Instant now = clock.instant();
        Optional<Link> current = invitationManagementRepository.link(invitationId, true)
                .filter(found -> found.tokenHash() != null && MessageDigest.isEqual(hash, found.tokenHash()));
        Link link = requireUsable(current, now);
        Optional<InvitationTarget> target = employeeAccountService.invitationTarget(link.email(), link.branch());

        long employee = create
                ? createAccount(link, target, passwordHash)
                : requireExistingAccount(target, sessionEmployee);

        Invitation before = invitationManagementRepository.find(link.id(), true, now).orElseThrow().view();
        employeeAccountService.pendInvitationMembership(employee, link.branch(), link.role(), requestId);
        invitationManagementRepository.accept(employee, link.id());
        Invitation after = invitationManagementRepository.find(link.id(), true, now).orElseThrow().view();
        invitationManagementRepository.changed(
                employee,
                link.branch(),
                link.id(),
                "ACCEPTED",
                before,
                after,
                requestId
        );

        String nextAction = create ? "LOGIN_FOR_APPROVAL_STATUS" : "REFRESH_APPROVAL_STATUS";
        return new AcceptanceResult(Long.toString(link.id()), "승인 대기", nextAction);
    }

    private long createAccount(Link link, Optional<InvitationTarget> target, String passwordHash) {
        if (target.isPresent()) {
            throw invalidState(ALREADY_REGISTERED);
        }

        try {
            return employeeAccountService.createInvitedAccount(link.email(), passwordHash, link.name());
        } catch (DuplicateKeyException exception) {
            throw invalidState(ALREADY_REGISTERED);
        }
    }

    private static long requireExistingAccount(Optional<InvitationTarget> target, Long sessionEmployee) {
        InvitationTarget account = target
                .filter(found -> found.employeeId() == sessionEmployee)
                .orElseThrow(AuthorizationService::forbidden);

        if (account.owner() || !"ACTIVE".equals(account.accountStatus())) {
            throw AuthorizationService.forbidden();
        }

        if ("ACTIVE".equals(account.membershipStatus())) {
            throw ApiException.conflict("DUPLICATE_MEMBERSHIP", "이미 활성 소속이 있는 지점입니다.");
        }

        if ("PENDING".equals(account.membershipStatus())) {
            throw invalidState("이미 승인 대기 중인 지점입니다.");
        }

        return account.employeeId();
    }

    private static Link requireUsable(Optional<Link> link, Instant now) {
        // Wrong, expired, consumed and cancelled links share one response so the link holder learns nothing more.
        return link
                .filter(current -> "PENDING".equals(current.state()) && now.isBefore(current.expiresAt()))
                .orElseThrow(InvitationAcceptanceService::linkInvalid);
    }

    private static String readToken(StrictInput input) {
        Object value = input.values().get("token");

        if (!(value instanceof String token) || !TOKEN.matcher(token).matches()) {
            throw linkInvalid();
        }

        return token;
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiException linkInvalid() {
        return ApiException.unprocessable(
                "INVITATION_LINK_INVALID",
                "초대 링크가 유효하지 않습니다. 관리자에게 새 초대를 요청해 주세요."
        );
    }

    private static ApiException invalidState(String message) {
        return ApiException.conflict("INVALID_STATE", message);
    }
}
