package com.openstudy.modules.invitation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.AcceptanceResult;
import com.openstudy.modules.invitation.dto.InvitationPreview;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository.Link;

/**
 * Public link commands. The raw token and password exist only in request memory: lookups use the SHA-256
 * hash, and neither value reaches the database, audit rows, logs or responses.
 */
@Service
public class InvitationAcceptanceService {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,512}");
    private final InvitationManagementRepository repository;
    private final AuthorizedCommands commands;
    private final EmployeeAccountService employees;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public InvitationAcceptanceService(InvitationManagementRepository repository, AuthorizedCommands commands,
            EmployeeAccountService employees, PasswordEncoder encoder, Clock clock) {
        this.repository = repository;
        this.commands = commands;
        this.employees = employees;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvitationPreview preview(Map<String, Object> body) {
        var input = new StrictInput(body).only("token");
        var link = usable(repository.link(hash(token(input))), clock.instant());
        return new InvitationPreview(link.name(), link.email(),
                new InvitationPreview.Branch(Long.toString(link.branch()), link.branchName()),
                "MANAGER".equals(link.role()) ? "admin" : "staff", link.expiresAt(),
                employees.exists(link.email()) ? "EXISTING_ACCOUNT" : "NEW_ACCOUNT");
    }

    /**
     * @param sessionEmployee authenticated employee of the current session, or null when anonymous
     * @param sessionMode WORKSPACE/APPROVAL_ONLY of that session, or null
     */
    public AcceptanceResult accept(Map<String, Object> body, Long sessionEmployee, String sessionMode, String requestId) {
        var input = new StrictInput(body);
        boolean create = "NEW_ACCOUNT".equals(input.choice("mode", "NEW_ACCOUNT", "EXISTING_ACCOUNT"));
        if (create) input.only("mode", "token", "email", "password"); else input.only("mode", "token", "email");
        byte[] hash = hash(token(input));
        String email = input.text("email", 254).toLowerCase(Locale.ROOT);
        String password = create ? password(input) : null;
        // A new account never attaches to someone's existing session; an existing account proves itself
        // with its own business session instead of a password sent to this endpoint.
        if (create && sessionEmployee != null) throw AuthorizationService.forbidden();
        if (!create && sessionEmployee == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!create && !SessionBoundaryPolicy.WORKSPACE.equals(sessionMode)) throw AuthorizationService.forbidden();
        // Reject unknown links before spending BCrypt work, then verify again under the invitation lock.
        var candidate = usable(repository.link(hash), clock.instant());
        if (!candidate.email().equals(email)) throw linkInvalid();
        String passwordHash = create ? encoder.encode(password) : null;
        return commands.publicCommand(() -> repository.lock(candidate.id()), () -> {
            Instant now = clock.instant();
            var link = usable(repository.link(candidate.id(), true)
                    .filter(current -> current.tokenHash() != null && MessageDigest.isEqual(hash, current.tokenHash())), now);
            var target = employees.invitationTarget(link.email(), link.branch());
            long employee;
            if (create) {
                if (target.isPresent()) throw invalidState("이미 가입된 이메일입니다. 로그인한 뒤 초대를 수락해 주세요.");
                try {
                    employee = employees.createInvitedAccount(link.email(), passwordHash, link.name());
                } catch (DuplicateKeyException exception) {
                    throw invalidState("이미 가입된 이메일입니다. 로그인한 뒤 초대를 수락해 주세요.");
                }
            } else {
                var account = target.filter(current -> current.employeeId() == sessionEmployee)
                        .orElseThrow(AuthorizationService::forbidden);
                if (account.owner() || !"ACTIVE".equals(account.accountStatus())) throw AuthorizationService.forbidden();
                if ("ACTIVE".equals(account.membershipStatus())) {
                    throw ApiException.conflict("DUPLICATE_MEMBERSHIP", "이미 활성 소속이 있는 지점입니다.");
                }
                if ("PENDING".equals(account.membershipStatus())) throw invalidState("이미 승인 대기 중인 지점입니다.");
                employee = account.employeeId();
            }
            var before = repository.find(link.id(), true, now).orElseThrow().view();
            employees.pendInvitationMembership(employee, link.branch(), link.role(), requestId);
            repository.accept(employee, link.id());
            var after = repository.find(link.id(), true, now).orElseThrow().view();
            repository.changed(employee, link.branch(), link.id(), "ACCEPTED", before, after, requestId);
            return new AcceptanceResult(Long.toString(link.id()), "승인 대기",
                    create ? "LOGIN_FOR_APPROVAL_STATUS" : "REFRESH_APPROVAL_STATUS");
        });
    }

    private static Link usable(Optional<Link> link, Instant now) {
        // Wrong, expired, consumed and cancelled links share one response so the link holder learns nothing more.
        return link.filter(current -> "PENDING".equals(current.state()) && now.isBefore(current.expiresAt()))
                .orElseThrow(InvitationAcceptanceService::linkInvalid);
    }

    private static String token(StrictInput input) {
        if (!(input.values().get("token") instanceof String token) || !TOKEN.matcher(token).matches()) throw linkInvalid();
        return token;
    }

    private static String password(StrictInput input) {
        // Passwords are never trimmed. 72 bytes is the BCrypt input limit.
        if (!(input.values().get("password") instanceof String password)
                || password.codePointCount(0, password.length()) < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.validation("password", "비밀번호는 8자 이상, UTF-8 72바이트 이하여야 합니다.");
        }
        return password;
    }

    private static byte[] hash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static ApiException linkInvalid() {
        return ApiException.unprocessable("INVITATION_LINK_INVALID", "초대 링크가 유효하지 않습니다. 관리자에게 새 초대를 요청해 주세요.");
    }

    private static ApiException invalidState(String message) { return ApiException.conflict("INVALID_STATE", message); }
}
