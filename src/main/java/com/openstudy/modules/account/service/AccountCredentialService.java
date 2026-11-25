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
import com.openstudy.common.web.PasswordPolicy;
import com.openstudy.modules.account.dto.AccountResponses;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Account;
import com.openstudy.modules.account.repository.AccountCredentialRepository.ResetRow;
import com.openstudy.modules.auth.service.LoginAttemptLimiter;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;

/**
 * Password change, administrator reset, reset-link use and login unlock
 * (#119, account-collab-api.md, ADR-M14-001 M14-D01·D02·D05·D06·D11).
 *
 * <p>Every write runs under the common lock order (guard, employees, branches).
 * Audit rows name the employee as entity and never hold a password, hash or token.
 */
@Service
@RequiredArgsConstructor
public class AccountCredentialService {

    static final Duration LINK_LIFETIME = Duration.ofHours(1);

    private static final String ENTITY = "employee";
    private static final String ACTIVE = "ACTIVE";

    private static final String CURRENT_PASSWORD = "currentPassword";
    private static final String NEW_PASSWORD = "newPassword";
    private static final String TOKEN = "token";

    private final AccountCredentialRepository accountCredentialRepository;
    private final AuthorizedCommands authorizedCommands;
    private final AuthorizationService authorizationService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PasswordEncoder passwordEncoder;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Present only when reset mail is enabled (PasswordResetMailConfiguration). */
    private final Optional<PasswordResetWorker> passwordResetWorker;

    private enum Change { CHANGED, RATE_LIMITED, MISMATCH }

    /**
     * POST /me/password. The current session stays; every other session of the employee ends (M14-D02).
     * A wrong current password counts against the account login limit and is audited,
     * so its transaction commits before the 422.
     */
    public void changePassword(long actor, Map<String, Object> body, String keepSessionId, String requestId) {
        Change outcome = authorizedCommands.selfAccountChange(actor, () -> {
            Account account = accountCredentialRepository.findAccount(actor)
                    .orElseThrow(AuthorizationService::forbidden);

            if (loginAttemptLimiter.accountExhausted(account.email())) {
                return Change.RATE_LIMITED;
            }

            Map<String, String> input = Passwords.requireStringFields(body, Set.of(CURRENT_PASSWORD, NEW_PASSWORD));
            String currentPassword = input.get(CURRENT_PASSWORD);
            String newPassword = input.get(NEW_PASSWORD);

            PasswordPolicy.validateByteLength(CURRENT_PASSWORD, currentPassword);
            PasswordPolicy.validateNew(NEW_PASSWORD, newPassword);

            if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
                loginAttemptLimiter.accountFailure(account.email());
                auditTrail.record(AuditEvent.attempt(
                        actor,
                        ENTITY,
                        actor,
                        "PASSWORD_CHANGED",
                        "CURRENT_PASSWORD_MISMATCH",
                        requestId,
                        AuditResult.FAILED
                ));
                return Change.MISMATCH;
            }

            if (passwordEncoder.matches(newPassword, account.passwordHash())) {
                throw ApiException.invalid(
                        "PASSWORD_SAME_AS_CURRENT",
                        "새 비밀번호가 기존 비밀번호와 같습니다.",
                        NEW_PASSWORD,
                        "기존과 다른 비밀번호를 입력해 주세요."
                );
            }

            accountCredentialRepository.updatePassword(actor, passwordEncoder.encode(newPassword));
            accountCredentialRepository.deleteSessions(actor, keepSessionId);
            auditTrail.record(AuditEvent.attempt(
                    actor,
                    ENTITY,
                    actor,
                    "PASSWORD_CHANGED",
                    null,
                    requestId,
                    AuditResult.SUCCESS
            ));

            return Change.CHANGED;
        });

        if (outcome == Change.RATE_LIMITED) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        }

        if (outcome == Change.MISMATCH) {
            throw ApiException.invalid(
                    "CURRENT_PASSWORD_MISMATCH",
                    "기존 비밀번호가 맞지 않습니다.",
                    CURRENT_PASSWORD,
                    "기존 비밀번호를 확인해 주세요."
            );
        }
    }

    /**
     * POST /employees/{id}/password-reset. The old password stops working now (M14-D11),
     * every session of the employee ends (M14-D02), earlier links are revoked and a delivery is queued;
     * the worker makes the token when it claims it.
     */
    public AccountResponses.PasswordResetRequested requestReset(long actor, long target, String requestId) {
        return authorizedCommands.activeEmployee(actor, () -> {}, state -> {
            Account account = findManageableAccount(state, target, false);

            if (!ACTIVE.equals(account.status())) {
                throw ApiException.conflict("INVALID_STATE", "활성 계정만 비밀번호를 초기화할 수 있습니다.");
            }

            // The reset kills the old password, so without a mail to carry the link it would lock the employee out.
            if (passwordResetWorker.isEmpty()) {
                throw ApiException.unavailable(
                        "PASSWORD_RESET_MAIL_DISABLED",
                        "재설정 메일을 보낼 수 없어 비밀번호를 초기화하지 않았습니다. 관리자에게 문의해 주세요."
                );
            }

            Instant now = now();
            String unknowableHash = passwordEncoder.encode(Passwords.createUnknowableSecret());

            accountCredentialRepository.updatePassword(target, unknowableHash);
            accountCredentialRepository.deleteSessions(target, null);
            accountCredentialRepository.revokeLive(target);
            accountCredentialRepository.insertPending(target, actor, now);

            auditTrail.record(AuditEvent.change(
                    actor,
                    null,
                    ENTITY,
                    target,
                    "PASSWORD_RESET_REQUESTED",
                    null,
                    null,
                    requestId
            ));

            return new AccountResponses.PasswordResetRequested(Long.toString(target), now, "PENDING");
        });
    }

    /** POST /employees/{id}/login-unlock (M14-D06): this instance's account limit only; the source keeps counting. */
    public AccountResponses.LoginUnlockResult unlockLogin(long actor, long target, String requestId) {
        return authorizedCommands.activeEmployee(actor, () -> {}, state -> {
            Account account = findManageableAccount(state, target, true);
            boolean wasLocked = loginAttemptLimiter.clearAccount(account.email());

            auditTrail.record(AuditEvent.change(
                    actor,
                    null,
                    ENTITY,
                    target,
                    "LOGIN_UNLOCKED",
                    null,
                    objectMapper.writeValueAsString(Map.of("wasLocked", wasLocked)),
                    requestId
            ));

            return new AccountResponses.LoginUnlockResult(Long.toString(target), wasLocked);
        });
    }

    /** POST /password-resets/preview. Wrong, expired, used and replaced links are the same 422. */
    @Transactional(readOnly = true)
    public AccountResponses.PasswordResetPreview previewReset(Map<String, Object> body, String source) {
        loginAttemptLimiter.checkSource(source);

        String token = Passwords.requireStringFields(body, Set.of(TOKEN)).get(TOKEN);
        if (!Passwords.isWellFormedToken(token)) {
            throw linkInvalid();
        }

        ResetRow row = accountCredentialRepository.findByHash(Passwords.hashToken(token))
                .filter(this::isUsable)
                .orElseThrow(AccountCredentialService::linkInvalid);

        Account account = findActiveAccount(row.employeeId());

        return new AccountResponses.PasswordResetPreview(row.expiresAt(), Passwords.toEmailHint(account.email()));
    }

    /**
     * POST /password-resets/complete. The link is checked again under its row lock,
     * the password replaced and the link used up;
     * the account login limit is cleared once the transaction has committed. No session is issued.
     */
    public void completeReset(Map<String, Object> body, String source, String requestId) {
        loginAttemptLimiter.checkSource(source);

        Map<String, String> input = Passwords.requireStringFields(body, Set.of(TOKEN, NEW_PASSWORD));
        String newPassword = input.get(NEW_PASSWORD);
        PasswordPolicy.validateNew(NEW_PASSWORD, newPassword);

        String token = input.get(TOKEN);
        if (!Passwords.isWellFormedToken(token)) {
            throw linkInvalid();
        }

        String email = authorizedCommands.publicCommand(() -> {}, () -> {
            ResetRow row = accountCredentialRepository.lockByHash(Passwords.hashToken(token))
                    .filter(this::isUsable)
                    .orElseThrow(AccountCredentialService::linkInvalid);

            Account account = findActiveAccount(row.employeeId());

            accountCredentialRepository.updatePassword(account.id(), passwordEncoder.encode(newPassword));
            accountCredentialRepository.markUsed(row.id(), now());

            auditTrail.record(AuditEvent.change(
                    null,
                    null,
                    ENTITY,
                    account.id(),
                    "PASSWORD_RESET_COMPLETED",
                    null,
                    null,
                    requestId
            ));

            return account.email();
        });

        loginAttemptLimiter.clearAccount(email);
    }

    private Account findManageableAccount(AuthorizationState state, long target, boolean selfAllowed) {
        Account account = accountCredentialRepository.findAccount(target)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        AccountTargets.requireManageable(
                state.employeeId(),
                state.owner(),
                account,
                accountCredentialRepository.findMemberships(target),
                branch -> authorizationService.permits(state, branch, BranchAction.MANAGE_PEOPLE),
                selfAllowed
        );

        return account;
    }

    /** The reset link's account, which must still be active; otherwise the link is invalid. */
    private Account findActiveAccount(long employeeId) {
        return accountCredentialRepository.findAccount(employeeId)
                .filter(found -> ACTIVE.equals(found.status()))
                .orElseThrow(AccountCredentialService::linkInvalid);
    }

    /** A link works while its mail is being sent or has been sent, until it expires. */
    private boolean isUsable(ResetRow row) {
        boolean delivering = "SENDING".equals(row.status()) || "SENT".equals(row.status());
        boolean notExpired = row.expiresAt() != null && now().isBefore(row.expiresAt());

        return delivering && notExpired;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static ApiException linkInvalid() {
        return ApiException.unprocessable(
                "PASSWORD_RESET_LINK_INVALID",
                "링크가 만료되었거나 더 이상 쓸 수 없습니다. 관리자에게 다시 요청해 주세요."
        );
    }
}
