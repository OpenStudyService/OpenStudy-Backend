package com.openstudy.modules.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.exception.ApiExceptionHandler;
import com.openstudy.config.SecurityConfiguration;
import com.openstudy.modules.auth.controller.AuthController;
import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.auth.service.LoginAttemptLimiter;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.branch.dto.SessionBranch;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeMembership;
import com.openstudy.modules.employee.service.EmployeeAccountService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, CurrentUserService.class, LoginAttemptLimiter.class, ApiExceptionHandler.class})
class AuthHttpTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean EmployeeAccountService employees;
    @MockitoBean SessionBranchService branches;
    @MockitoBean AuthorizationService authorization;

    private String email;
    private static final String PASSWORD = "test-password-123";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    @BeforeEach
    void accounts() {
        email = UUID.randomUUID() + "@example.com";
        when(employees.byEmail(anyString())).thenThrow(new UsernameNotFoundException("missing"));
        doReturn(account("ACTIVE")).when(employees).byEmail(email);
        when(employees.byId(42)).thenReturn(account("ACTIVE"));
        when(employees.memberships(42)).thenReturn(List.of(new EmployeeMembership("7", "admin", List.of("operations:write"))));
        when(branches.accessible(42, false)).thenReturn(List.of(new SessionBranch("7", "7", 1,
                Instant.EPOCH.toString(), Instant.EPOCH.toString(), "테스트 지점", "서울", "", "", "운영 중")));
    }

    @Test
    void anonymousRequestsReturnJsonWithoutBasicAuthChallengeOrLoginPage() throws Exception {
        var result = mvc.perform(get("/api/me").header("X-Request-ID", "untrusted-client-id")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(header().doesNotExist("Location")).andReturn();
        String id = result.getResponse().getHeader("X-Request-ID");
        assertThat(id).isNotBlank().isNotEqualTo("untrusted-client-id");
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("requestId").asString()).isEqualTo(id);
    }

    @Test
    void loginRotatesSessionAndPersistsAuthenticationForMe() throws Exception {
        var csrf = csrf(null);
        String oldId = csrf.session().getId();
        var loggedIn = login(csrf, email, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("42"))
                .andExpect(jsonPath("$.user.status").value("활성"))
                .andExpect(jsonPath("$.user.memberships[0].role").value("admin"))
                .andExpect(jsonPath("$.user.memberships[0].permissions[0]").value("operations:write"))
                .andExpect(jsonPath("$.branches[0].id").value("7"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var session = (MockHttpSession) loggedIn.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(loggedIn.getResponse().getContentAsString()).doesNotContain("password", HASH, PASSWORD);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email));
    }

    @Test
    void rejectsLoginWithoutCsrfAndWithInvalidCsrf() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(email, PASSWORD)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var csrf = csrf(null);
        login(new Csrf(csrf.session(), "invalid"), email, PASSWORD)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void unknownEmailWrongPasswordAndInactiveAccountHaveSameFailure() throws Exception {
        var csrf = csrf(null);
        login(csrf, "missing@example.com", PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        login(csrf, email, "incorrect-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        doReturn(account("SUSPENDED")).when(employees).byEmail(email);
        login(csrf, email, PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void logoutRequiresFreshCsrfAndInvalidatesSession() throws Exception {
        var before = csrf(null);
        login(before, email, PASSWORD).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(before.session()).header("X-CSRF-Token", before.token()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        var after = csrf(before.session());
        mvc.perform(post("/api/auth/logout").session(after.session()).header("X-CSRF-Token", after.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(cookie().maxAge("SESSION", 0));
        assertThat(after.session().isInvalid()).isTrue();
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void pendingUserCanReadMeButCannotAccessBusinessEndpoints() throws Exception {
        doReturn(account("PENDING")).when(employees).byEmail(email);
        when(employees.byId(42)).thenReturn(account("PENDING"));
        var csrf = csrf(null);
        login(csrf, email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.status").value("승인 대기"));
        mvc.perform(get("/api/me").session(csrf.session())).andExpect(status().isOk());
        mvc.perform(get("/api/protected").session(csrf.session()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void invalidatesSessionWhenAccountIsSuspendedOrDeleted() throws Exception {
        var csrf = csrf(null);
        login(csrf, email, PASSWORD).andExpect(status().isOk());
        when(employees.byId(42)).thenReturn(account("LEFT"));
        mvc.perform(get("/api/me").session(csrf.session())).andExpect(status().isUnauthorized());
        assertThat(csrf.session().isInvalid()).isTrue();
        when(employees.byId(42)).thenReturn(account("ACTIVE"));
        csrf = csrf(null);
        login(csrf, email, PASSWORD).andExpect(status().isOk());
        when(employees.byId(42)).thenThrow(new UsernameNotFoundException("deleted"));
        mvc.perform(get("/api/me").session(csrf.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void expiresSessionTwelveHoursAfterLoginEvenIfItIsActive() throws Exception {
        var csrf = csrf(null);
        login(csrf, email, PASSWORD).andExpect(status().isOk());
        csrf.session().setAttribute("authenticatedAt", Instant.now().minusSeconds(12 * 3600).getEpochSecond());
        mvc.perform(get("/api/me").session(csrf.session())).andExpect(status().isUnauthorized());
        assertThat(csrf.session().isInvalid()).isTrue();
    }

    @Test
    void validatesInputAndLimitsRepeatedLoginAttempts() throws Exception {
        var csrf = csrf(null);
        login(csrf, "invalid", "short").andExpect(status().isUnprocessableContent());
        for (int i = 0; i < 10; i++) {
            login(csrf, email, "incorrect-password").andExpect(status().isUnauthorized());
        }
        login(csrf, email, PASSWORD).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void logoutWithoutAnExistingSessionStillClearsCookie() throws Exception {
        mvc.perform(post("/api/auth/logout")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true)).andExpect(cookie().maxAge("SESSION", 0));
    }

    @Test
    void rejectsUnknownFieldsAndMalformedJsonWithoutEchoingCredentials() throws Exception {
        var csrf = csrf(null);
        for (String payload : List.of("{\"email\":\"" + email + "\",\"password\":\"secret-password\",\"owner\":true}",
                "{\"password\":\"secret-password\",", "{\"email\":null,\"password\":null}")) {
            var result = mvc.perform(post("/api/auth/login").session(csrf.session()).header("X-CSRF-Token", csrf.token())
                    .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors").isMap()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-password", "UnrecognizedProperty", "owner");
        }
    }

    @Test
    void unsupportedBodyMediaTypeReturns415WithoutInternalError() throws Exception {
        var csrf = csrf(null);
        mvc.perform(post("/api/auth/login").session(csrf.session()).header("X-CSRF-Token", csrf.token())
                        .contentType(MediaType.TEXT_PLAIN).content("secret-password"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.requestId").isString());
    }

    @Test
    void trimsEmailButPreservesPasswordAndAllowsLegacyShortPasswordAuthentication() throws Exception {
        var csrf = csrf(null);
        login(csrf, "  " + email + "  ", PASSWORD).andExpect(status().isOk());
        csrf = csrf(csrf.session());
        login(csrf, email, PASSWORD + " ").andExpect(status().isUnauthorized());
        login(csrf, email, "short").andExpect(status().isUnauthorized());
        login(csrf, email, "가".repeat(25)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    private EmployeeAccount account(String status) {
        return new EmployeeAccount(42, email, HASH, "직원", "닉네임", null, "EMPLOYEE", status, 1, Instant.EPOCH, Instant.EPOCH);
    }

    private record Csrf(MockHttpSession session, String token) {}

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        MvcResult result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return new Csrf((MockHttpSession) result.getRequest().getSession(false),
                json.readTree(result.getResponse().getContentAsString()).get("token").asString());
    }

    private org.springframework.test.web.servlet.ResultActions login(Csrf csrf, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").session(csrf.session()).header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(body(email, password)));
    }

    private String body(String email, String password) {
        return json.writeValueAsString(java.util.Map.of("email", email, "password", password));
    }
}
