package com.openstudy.modules.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditResult;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.account.dto.AccountResponses;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Account;
import com.openstudy.modules.auth.service.LoginAttemptLimiter;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;

/**
 * Password change, administrator reset, reset-link use and login unlock (#119, account-collab-api.md, ADR-M14-001
 * M14-D01·D02·D05·D06·D11). Every write runs under the common lock order (guard, employees, branches). Audit rows name
 * the employee as entity and never hold a password, hash or token.
 */
@Service
@RequiredArgsConstructor
public class AccountCredentialService {
    static final Duration LINK_LIFETIME = Duration.ofHours(1);
    private static final String ENTITY = "employee";

    private final AccountCredentialRepository repository;
    private final AuthorizedCommands commands;
    private final AuthorizationService policy;
    private final LoginAttemptLimiter limiter;
    private final PasswordEncoder encoder;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;
    /** Present only when reset mail is enabled (PasswordResetMailConfiguration). */
    private final Optional<PasswordResetWorker> resetMail;

    private enum Change { CHANGED, RATE_LIMITED, MISMATCH }

    /**
     * POST /me/password. The current session stays; every other session of the employee ends (M14-D02). A wrong current
     * password counts against the account login limit and is audited, so its transaction commits before the 422.
     */
    public void changePassword(long actor, Map<String, Object> body, String keepSessionId, String requestId) {
        var outcome = commands.selfAccountChange(actor, () -> {
            var account = repository.account(actor).orElseThrow(AuthorizationService::forbidden);
            if (limiter.accountExhausted(account.email())) return Change.RATE_LIMITED;
            var input = Passwords.fields(body, Set.of("currentPassword", "newPassword"));
            String current = input.get("currentPassword");
            String next = input.get("newPassword");
            Passwords.bounded("currentPassword", current);
            Passwords.acceptable("newPassword", next);
            if (!encoder.matches(current, account.passwordHash())) {
                limiter.accountFailure(account.email());
                audit.record(AuditEvent.attempt(actor, ENTITY, actor, "PASSWORD_CHANGED", "CURRENT_PASSWORD_MISMATCH", requestId,
                        AuditResult.FAILED));
                return Change.MISMATCH;
            }
            if (encoder.matches(next, account.passwordHash()))
                throw ApiException.invalid("PASSWORD_SAME_AS_CURRENT", "새 비밀번호가 기존 비밀번호와 같습니다.", "newPassword",
                        "기존과 다른 비밀번호를 입력해 주세요.");
            repository.updatePassword(actor, encoder.encode(next));
            repository.deleteSessions(actor, keepSessionId);
            audit.record(AuditEvent.attempt(actor, ENTITY, actor, "PASSWORD_CHANGED", null, requestId, AuditResult.SUCCESS));
            return Change.CHANGED;
        });
        if (outcome == Change.RATE_LIMITED) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        if (outcome == Change.MISMATCH)
            throw ApiException.invalid("CURRENT_PASSWORD_MISMATCH", "기존 비밀번호가 맞지 않습니다.", "currentPassword",
                    "기존 비밀번호를 확인해 주세요.");
    }

    /**
     * POST /employees/{id}/password-reset. The old password stops working now (M14-D11), every session of the employee
     * ends (M14-D02), earlier links are revoked and a delivery is queued; the worker makes the token when it claims it.
     */
    public AccountResponses.PasswordResetRequested requestReset(long actor, long target, String requestId) {
        return commands.activeEmployee(actor, () -> {}, state -> {
            var account = manageable(state, target, false);
            if (!"ACTIVE".equals(account.status()))
                throw ApiException.conflict("INVALID_STATE", "활성 계정만 비밀번호를 초기화할 수 있습니다.");
            // The reset kills the old password, so without a mail to carry the link it would lock the employee out.
            if (resetMail.isEmpty())
                throw ApiException.unavailable("PASSWORD_RESET_MAIL_DISABLED", "재설정 메일을 보낼 수 없어 비밀번호를 초기화하지 않았습니다. 관리자에게 문의해 주세요.");
            Instant now = now();
            repository.updatePassword(target, encoder.encode(Passwords.unknowable()));
            repository.deleteSessions(target, null);
            repository.revokeLive(target);
            repository.insertPending(target, actor, now);
            audit.record(AuditEvent.change(actor, null, ENTITY, target, "PASSWORD_RESET_REQUESTED", null, null, requestId));
            return new AccountResponses.PasswordResetRequested(Long.toString(target), now, "PENDING");
        });
    }

    /** POST /employees/{id}/login-unlock (M14-D06): this instance's account limit only; the source keeps counting. */
    public AccountResponses.LoginUnlockResult unlock(long actor, long target, String requestId) {
        return commands.activeEmployee(actor, () -> {}, state -> {
            var account = manageable(state, target, true);
            boolean wasLocked = limiter.clearAccount(account.email());
            audit.record(AuditEvent.change(actor, null, ENTITY, target, "LOGIN_UNLOCKED", null,
                    json.writeValueAsString(Map.of("wasLocked", wasLocked)), requestId));
            return new AccountResponses.LoginUnlockResult(Long.toString(target), wasLocked);
        });
    }

    /** POST /password-resets/preview. Wrong, expired, used and replaced links are the same 422. */
    @Transactional(readOnly = true)
    public AccountResponses.PasswordResetPreview preview(Map<String, Object> body, String source) {
        limiter.checkSource(source);
        String token = Passwords.fields(body, Set.of("token")).get("token");
        if (!Passwords.wellFormedToken(token)) throw linkInvalid();
        var row = repository.findByHash(Passwords.hash(token)).filter(this::usable).orElseThrow(AccountCredentialService::linkInvalid);
        var account = repository.account(row.employeeId()).filter(found -> "ACTIVE".equals(found.status()))
                .orElseThrow(AccountCredentialService::linkInvalid);
        return new AccountResponses.PasswordResetPreview(row.expiresAt(), Passwords.emailHint(account.email()));
    }

    /**
     * POST /password-resets/complete. The link is checked again under its row lock, the password replaced and the link
     * used up; the account login limit is cleared once the transaction has committed. No session is issued.
     */
    public void complete(Map<String, Object> body, String source, String requestId) {
        limiter.checkSource(source);
        var input = Passwords.fields(body, Set.of("token", "newPassword"));
        String next = input.get("newPassword");
        Passwords.acceptable("newPassword", next);
        String token = input.get("token");
        if (!Passwords.wellFormedToken(token)) throw linkInvalid();
        String email = commands.publicCommand(() -> {}, () -> {
            var row = repository.lockByHash(Passwords.hash(token)).filter(this::usable).orElseThrow(AccountCredentialService::linkInvalid);
            var account = repository.account(row.employeeId()).filter(found -> "ACTIVE".equals(found.status()))
                    .orElseThrow(AccountCredentialService::linkInvalid);
            repository.updatePassword(account.id(), encoder.encode(next));
            repository.used(row.id(), now());
            audit.record(AuditEvent.change(null, null, ENTITY, account.id(), "PASSWORD_RESET_COMPLETED", null, null, requestId));
            return account.email();
        });
        limiter.clearAccount(email);
    }

    private Account manageable(AuthorizationState state, long target, boolean selfAllowed) {
        var account = repository.account(target).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AccountTargets.requireManageable(state.employeeId(), state.owner(), account, repository.memberships(target),
                branch -> policy.permits(state, branch, BranchAction.MANAGE_PEOPLE), selfAllowed);
        return account;
    }

    /** A link works while its mail is being sent or has been sent, until it expires. */
    private boolean usable(AccountCredentialRepository.ResetRow row) {
        return ("SENDING".equals(row.status()) || "SENT".equals(row.status())) && row.expiresAt() != null
                && now().isBefore(row.expiresAt());
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    static ApiException linkInvalid() {
        return ApiException.unprocessable("PASSWORD_RESET_LINK_INVALID", "링크가 만료되었거나 더 이상 쓸 수 없습니다. 관리자에게 다시 요청해 주세요.");
    }
}
