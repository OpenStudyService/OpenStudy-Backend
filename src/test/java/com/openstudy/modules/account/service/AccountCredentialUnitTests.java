package com.openstudy.modules.account.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditResult;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Account;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Membership;
import com.openstudy.modules.account.repository.AccountCredentialRepository.ResetRow;
import com.openstudy.modules.auth.service.LoginAttemptLimiter;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #119 without a database: password rules, who may reset or unlock whom (M14-D05), the order of the password-change
 * checks, what a reset request does (M14-D02·D11), reset-link checks and the login limiter. The SQL and HTTP paths are left
 * to the milestone integration run.
 */
class AccountCredentialUnitTests {
    static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    static final String OLD = "old-password-1";
    static final String OLD_HASH = ENCODER.encode(OLD);

    final AccountCredentialRepository repository = mock(AccountCredentialRepository.class);
    final AuthorizedCommands commands = mock(AuthorizedCommands.class);
    final AuthorizationService policy = mock(AuthorizationService.class);
    final AuditTrail audit = mock(AuditTrail.class);
    LoginAttemptLimiter limiter;
    AccountCredentialService service;

    static String code(Executable call) {
        return assertThrows(ApiException.class, call).error("t").code();
    }

    static String field(Executable call) {
        return assertThrows(ApiException.class, call).error("t").fieldErrors().keySet().iterator().next();
    }

    static Map<String, Object> body(Object... pairs) {
        var map = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    static Account account(long id, String status, boolean owner) {
        return new Account(id, "user" + id + "@example.com", "직원" + id, status, owner, OLD_HASH);
    }

    static final Optional<PasswordResetWorker> MAIL_ON = Optional.of(new PasswordResetWorker(null, null, null));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        limiter = new LoginAttemptLimiter(CLOCK, 3, 15, 30, 15, 100);
        service = new AccountCredentialService(repository, commands, policy, limiter, ENCODER, audit, JsonMapper.builder().build(), CLOCK, MAIL_ON);
        // The command wrappers only take locks; here they run the callback directly.
        when(commands.selfAccountChange(anyLong(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        when(commands.publicCommand(any(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        when(commands.activeEmployee(anyLong(), any(), any())).thenAnswer(call ->
                ((Function<AuthorizationState, Object>) call.getArgument(2)).apply(state(call.getArgument(0), false)));
    }

    static AuthorizationState state(long id, boolean owner) {
        return new AuthorizationState(id, owner, "ACTIVE", Map.of(), Map.of());
    }

    // ---- password rules ----------------------------------------------------------------------------------------------

    @Test void newPasswordsNeedEightCharactersAndAtMost72Bytes() {
        Passwords.acceptable("p", "12345678");
        Passwords.acceptable("p", "비밀번호여덟글자");          // 8 code points, 24 bytes
        Passwords.acceptable("p", " spaces ");                  // spaces are kept and count
        assertThat(field(() -> Passwords.acceptable("p", "1234567"))).isEqualTo("p");
        assertThat(field(() -> Passwords.acceptable("p", "가".repeat(25)))).as("75 bytes").isEqualTo("p");
        Passwords.acceptable("p", "a".repeat(72));
        assertThat(field(() -> Passwords.acceptable("p", "a".repeat(73)))).isEqualTo("p");
    }

    @Test void bodiesTakeOnlyTheirStringFields() {
        assertThat(Passwords.fields(body("token", "x"), java.util.Set.of("token"))).containsEntry("token", "x");
        assertThat(field(() -> Passwords.fields(body("token", "x", "extra", "y"), java.util.Set.of("token")))).isEqualTo("extra");
        assertThat(field(() -> Passwords.fields(body("token", 1), java.util.Set.of("token")))).isEqualTo("token");
        assertThat(field(() -> Passwords.fields(body("token", ""), java.util.Set.of("token")))).isEqualTo("token");
        assertThat(field(() -> Passwords.fields(null, java.util.Set.of("token")))).isEqualTo("body");
    }

    @Test void tokensAreRandomUrlSafeAndStoredOnlyAsTheirHash() {
        String one = Passwords.newToken();
        String two = Passwords.newToken();
        assertThat(one).isNotEqualTo(two);
        assertThat(Passwords.wellFormedToken(one)).isTrue();
        assertThat(Passwords.wellFormedToken(one + "=")).isFalse();
        assertThat(Passwords.wellFormedToken(null)).isFalse();
        assertThat(Passwords.hash(one)).hasSize(32).isEqualTo(Passwords.hash(one));
        assertThat(ENCODER.matches(OLD, ENCODER.encode(Passwords.unknowable()))).isFalse();
    }

    @Test void theEmailHintShowsTwoCharactersAndTheDomain() {
        assertThat(Passwords.emailHint("abcdef@example.com")).isEqualTo("ab***@example.com");
        assertThat(Passwords.emailHint("ab@example.com")).isEqualTo("a***@example.com");
        assertThat(Passwords.emailHint("broken")).isEqualTo("***");
    }

    // ---- who may reset or unlock whom (M14-D05) ----------------------------------------------------------------------

    static void decide(long actor, boolean owner, Account target, List<Membership> memberships, java.util.Set<Long> managed, boolean self) {
        AccountTargets.requireManageable(actor, owner, target, memberships, managed::contains, self);
    }

    @Test void theTargetRulesFollowTheContractOrder() {
        var staff = account(5, "ACTIVE", false);
        var active = List.of(new Membership(1, "ACTIVE"));
        // A manager of branch 1 manages an active member of branch 1.
        decide(9, false, staff, active, java.util.Set.of(1L), false);
        // Not visible: no membership in a managed branch → 404.
        assertThrows(ResponseStatusException.class, () -> decide(9, false, staff, active, java.util.Set.of(2L), false));
        // Visible through a revoked membership only → 403.
        assertThrows(AccessDeniedException.class,
                () -> decide(9, false, staff, List.of(new Membership(1, "REVOKED")), java.util.Set.of(1L), false));
        // Self: the reset says SELF_RESET_FORBIDDEN, the unlock allows it.
        assertThat(code(() -> decide(5, false, staff, active, java.util.Set.of(1L), false))).isEqualTo("SELF_RESET_FORBIDDEN");
        decide(5, false, staff, active, java.util.Set.of(1L), true);
        // An OWNER target: only another owner.
        var owner = account(2, "ACTIVE", true);
        assertThrows(AccessDeniedException.class, () -> decide(9, false, owner, active, java.util.Set.of(1L), false));
        decide(1, true, owner, List.of(), java.util.Set.of(), false);
        assertThat(code(() -> decide(2, true, owner, List.of(), java.util.Set.of(), false))).isEqualTo("SELF_RESET_FORBIDDEN");
    }

    // ---- password change (M14-D02) -----------------------------------------------------------------------------------

    @Test void aChangeReplacesTheHashEndsOtherSessionsAndAudits() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        service.changePassword(5, body("currentPassword", OLD, "newPassword", "new-password-2"), "session-a", "req-1");
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).updatePassword(eq(5L), hash.capture());
        assertThat(ENCODER.matches("new-password-2", hash.getValue())).isTrue();
        verify(repository).deleteSessions(5, "session-a");
        var event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("PASSWORD_CHANGED");
        assertThat(event.getValue().getResult()).isEqualTo(AuditResult.SUCCESS);
    }

    @Test void aWrongCurrentPasswordIsAuditedCountedAndRefused() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        assertThat(code(() -> service.changePassword(5, body("currentPassword", "wrong-one", "newPassword", "new-password-2"), "s", "r")))
                .isEqualTo("CURRENT_PASSWORD_MISMATCH");
        verify(repository, never()).updatePassword(anyLong(), any());
        var event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getResult()).isEqualTo(AuditResult.FAILED);
        assertThat(event.getValue().getReason()).isEqualTo("CURRENT_PASSWORD_MISMATCH");
        // The limit is 3 here: two more wrong guesses exhaust it, and then even a right one is 429 before any check.
        for (int i = 0; i < 2; i++)
            code(() -> service.changePassword(5, body("currentPassword", "wrong-one", "newPassword", "new-password-2"), "s", "r"));
        var limited = assertThrows(ResponseStatusException.class,
                () -> service.changePassword(5, body("currentPassword", OLD, "newPassword", "bad"), "s", "r"));
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
    }

    @Test void theSamePasswordAndMalformedInputAreRefused() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        assertThat(code(() -> service.changePassword(5, body("currentPassword", OLD, "newPassword", OLD), "s", "r")))
                .isEqualTo("PASSWORD_SAME_AS_CURRENT");
        assertThat(field(() -> service.changePassword(5, body("currentPassword", OLD, "newPassword", "short"), "s", "r")))
                .isEqualTo("newPassword");
        assertThat(field(() -> service.changePassword(5, body("currentPassword", OLD), "s", "r"))).isEqualTo("newPassword");
        verify(repository, never()).updatePassword(anyLong(), any());
    }

    // ---- administrator reset (M14-D01·D02·D05·D11) and unlock (M14-D06) ----------------------------------------------

    void managerOfBranchOne() {
        when(repository.memberships(5)).thenReturn(List.of(new Membership(1, "ACTIVE")));
        when(policy.permits(any(), eq(1L), any())).thenReturn(true);
    }

    @Test void aResetKillsTheOldPasswordAndEverySessionAndQueuesALink() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        managerOfBranchOne();
        var result = service.requestReset(9, 5, "req-2");
        assertThat(result.employeeId()).isEqualTo("5");
        assertThat(result.requestedAt()).isEqualTo(NOW);
        assertThat(result.delivery()).isEqualTo("PENDING");
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).updatePassword(eq(5L), hash.capture());
        assertThat(ENCODER.matches(OLD, hash.getValue())).as("the old password stops working").isFalse();
        verify(repository).deleteSessions(5, null);
        verify(repository).revokeLive(5);
        verify(repository).insertPending(5, 9, NOW);
        var event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("PASSWORD_RESET_REQUESTED");
        assertThat(event.getValue().getActorEmployeeId()).isEqualTo(9L);
    }

    @Test void onlyActiveAccountsAreReset() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "SUSPENDED", false)));
        managerOfBranchOne();
        assertThat(code(() -> service.requestReset(9, 5, "r"))).isEqualTo("INVALID_STATE");
        verify(repository, never()).updatePassword(anyLong(), any());
    }

    @Test void withResetMailOffAResetIsRefusedAndChangesNothing() {
        var mailOff = new AccountCredentialService(repository, commands, policy, limiter, ENCODER, audit, JsonMapper.builder().build(),
                CLOCK, Optional.empty());
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        managerOfBranchOne();
        var refused = assertThrows(ApiException.class, () -> mailOff.requestReset(9, 5, "r"));
        assertThat(refused.error("t").code()).isEqualTo("PASSWORD_RESET_MAIL_DISABLED");
        assertThat(refused.status()).isEqualTo(503);
        verify(repository, never()).updatePassword(anyLong(), any());
        verify(repository, never()).deleteSessions(anyLong(), any());
        verify(repository, never()).insertPending(anyLong(), anyLong(), any());
        verify(audit, never()).record(any());
    }

    @Test void anUnlockClearsTheAccountLimitAndSaysWhetherItWasLocked() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "SUSPENDED", false)));
        managerOfBranchOne();
        assertThat(service.unlock(9, 5, "r").wasLocked()).isFalse();
        for (int i = 0; i < 3; i++) limiter.accountFailure("USER5@example.com ");
        assertThat(limiter.accountExhausted("user5@example.com")).isTrue();
        var result = service.unlock(9, 5, "r");
        assertThat(result.wasLocked()).isTrue();
        assertThat(limiter.accountExhausted("user5@example.com")).isFalse();
    }

    // ---- reset link ----------------------------------------------------------------------------------------------------

    static ResetRow row(String status, Instant expiresAt) {
        return new ResetRow(7, 5, status, new byte[32], expiresAt, 1, null, null);
    }

    @Test void aLinkIsPreviewedOnlyWhileSentAndUnexpired() {
        String token = Passwords.newToken();
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        when(repository.findByHash(any())).thenReturn(Optional.of(row("SENT", NOW.plusSeconds(60))));
        var preview = service.preview(body("token", token), "10.0.0.1");
        assertThat(preview.emailHint()).isEqualTo("us***@example.com");
        assertThat(preview.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        when(repository.findByHash(any())).thenReturn(Optional.of(row("SENT", NOW)));
        assertThat(code(() -> service.preview(body("token", token), "10.0.0.1"))).isEqualTo("PASSWORD_RESET_LINK_INVALID");
        when(repository.findByHash(any())).thenReturn(Optional.of(row("USED", null)));
        assertThat(code(() -> service.preview(body("token", token), "10.0.0.1"))).isEqualTo("PASSWORD_RESET_LINK_INVALID");
        assertThat(code(() -> service.preview(body("token", "short"), "10.0.0.1"))).isEqualTo("PASSWORD_RESET_LINK_INVALID");
    }

    @Test void completingALinkSetsThePasswordUsesTheLinkAndClearsTheLimit() {
        String token = Passwords.newToken();
        when(repository.account(5)).thenReturn(Optional.of(account(5, "ACTIVE", false)));
        when(repository.lockByHash(any())).thenReturn(Optional.of(row("SENDING", NOW.plusSeconds(60))));
        for (int i = 0; i < 3; i++) limiter.accountFailure("user5@example.com");
        service.complete(body("token", token, "newPassword", "brand-new-pass"), "10.0.0.2", "req-3");
        var hash = ArgumentCaptor.forClass(String.class);
        verify(repository).updatePassword(eq(5L), hash.capture());
        assertThat(ENCODER.matches("brand-new-pass", hash.getValue())).isTrue();
        verify(repository).used(7, NOW);
        assertThat(limiter.accountExhausted("user5@example.com")).isFalse();
    }

    @Test void aLinkOfAnAccountThatIsNoLongerActiveIsInvalid() {
        when(repository.account(5)).thenReturn(Optional.of(account(5, "LEFT", false)));
        when(repository.lockByHash(any())).thenReturn(Optional.of(row("SENT", NOW.plusSeconds(60))));
        assertThat(code(() -> service.complete(body("token", Passwords.newToken(), "newPassword", "brand-new-pass"), "s", "r")))
                .isEqualTo("PASSWORD_RESET_LINK_INVALID");
        verify(repository, never()).updatePassword(anyLong(), any());
    }

    @Test void theLinkEndpointsShareTheLoginSourceLimit() {
        var tight = new LoginAttemptLimiter(CLOCK, 10, 15, 2, 15, 100);
        var limited = new AccountCredentialService(repository, commands, policy, tight, ENCODER, audit, JsonMapper.builder().build(), CLOCK, MAIL_ON);
        for (int i = 0; i < 2; i++) code(() -> limited.preview(body("token", "short"), "10.0.0.9"));
        assertThat(assertThrows(ResponseStatusException.class, () -> limited.preview(body("token", "short"), "10.0.0.9"))
                .getStatusCode().value()).isEqualTo(429);
    }
}
