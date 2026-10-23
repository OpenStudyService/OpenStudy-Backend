package com.openstudy.common.web;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import com.openstudy.common.exception.ApiExceptionHandler;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class ApiContractHttpTests {
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ContractController()).setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new ApiRequestFilter(json)).build();
    }

    @RestController
    static class ContractController {
        @GetMapping("/api/contract/version")
        Object version(@RequestParam long version) {
            ApiVersion.requireCurrent(version, 2, "/api/branches/7");
            return java.util.Map.of("version", ApiVersion.fromDatabase(2));
        }
        @GetMapping("/api/contract/failure")
        Object failure() { throw new IllegalStateException("SELECT password_hash secret-password token-private"); }
        @GetMapping("/api/contract/status")
        Object status(@RequestParam int status) {
            if (status == 403) throw new AccessDeniedException("private scope details");
            throw new ResponseStatusException(HttpStatus.valueOf(status), "SELECT secret-password");
        }
        @GetMapping("/api/contract/validated")
        Object validated(@RequestParam @Min(1) int count) { return count; }
        @GetMapping("/api/contract/list")
        PageResponse<Row> list(@RequestParam(defaultValue="1") long page,
                @RequestParam(defaultValue="20") int pageSize, @RequestParam(required=false) String sort,
                @RequestParam(required=false) String direction, @RequestParam(defaultValue="") String q) {
            var query = ListQuery.parse(page, pageSize, q, sort, direction, Set.of("createdAt"), "createdAt");
            return PageResponse.fromAuthorized(List.of(new Row(2, "same"), new Row(10, "same"), new Row(1, "same")),
                    query, Comparator.comparing(Row::createdAt), Comparator.comparingLong(Row::id));
        }
    }
    record Row(long id, String createdAt) {}

    @Test
    void conflictContainsSafeLatestVersionAndReloadLocationWithMatchingRequestId() throws Exception {
        var response = mvc.perform(get("/api/contract/version?version=2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.latestVersion").value(3))
                .andExpect(jsonPath("$.reloadUrl").value("/api/branches/7"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString()).get("requestId").asString()).isEqualTo(response.getHeader("X-Request-ID"));
        mvc.perform(get("/api/contract/version?version=3")).andExpect(status().isOk());
    }

    @Test
    void invalidVersionsAndMalformedParametersAre422() throws Exception {
        for (String version : List.of("0", "-1", "9007199254740992", "bad")) {
            mvc.perform(get("/api/contract/version").param("version", version)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.fieldErrors.version").exists());
        }
        mvc.perform(get("/api/contract/version")).andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/contract/validated?count=0")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void safeErrorsDoNotLeakExceptionTextAndLogsLinkRequestWithoutQueryOrBody(CapturedOutput output) throws Exception {
        var response = mvc.perform(get("/api/contract/failure?token=token-private")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("SELECT", "secret-password", "token-private");
        assertThat(output.getAll()).contains(response.getHeader("X-Request-ID")).doesNotContain("SELECT", "secret-password", "token-private");
        mvc.perform(get("/api/contract/status?status=403")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/contract/status?status=404")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/contract/status?status=500")).andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))));
    }

    @Test
    void filterFailureAlsoUsesSafeJsonAndRestoresMdc() throws Exception {
        var broken = MockMvcBuilders.standaloneSetup(new ContractController())
                .addFilters(new ApiRequestFilter(json), (request, response, chain) -> { throw new IllegalStateException("secret-password"); }).build();
        broken.perform(get("/api/contract/list")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.requestId").isString()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(org.slf4j.MDC.get("requestId")).isNull();
    }

    @Test
    void listUsesStableNumericIdTieBreakAndNestedMetaEvenPastLastPage() throws Exception {
        mvc.perform(get("/api/contract/list?pageSize=2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(10)).andExpect(jsonPath("$.items[1].id").value(2))
                .andExpect(jsonPath("$.meta.total").value(3)).andExpect(jsonPath("$.meta.page").value(1));
        mvc.perform(get("/api/contract/list?pageSize=2&page=2")).andExpect(jsonPath("$.items[0].id").value(1));
        mvc.perform(get("/api/contract/list?direction=asc")).andExpect(jsonPath("$.items[0].id").value(1));
        mvc.perform(get("/api/contract/list?page=9007199254740991&pageSize=100"))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.meta.total").value(3));
    }

    @Test
    void invalidPageSizeSortDirectionAndSearchAreRejected() throws Exception {
        for (String query : List.of("page=0", "pageSize=0", "pageSize=101", "sort=password_hash", "direction=DESC", "q=" + "a".repeat(101))) {
            mvc.perform(get("/api/contract/list?" + query)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        var query = ListQuery.parse(1, 20, "  이름  ", null, null, Set.of("createdAt"), "createdAt");
        assertThat(query.q()).isEqualTo("이름");
        assertThat(PageResponse.fromAuthorized(List.<Row>of(), query, Comparator.comparing(Row::createdAt), Comparator.comparingLong(Row::id)).meta().total()).isZero();
    }

    @Test
    void databaseVersionConversionIsBoundedAndReversible() {
        for (long db : List.of(0L, 12L, ApiVersion.MAX - 1)) assertThat(ApiVersion.toDatabase(ApiVersion.fromDatabase(db))).isEqualTo(db);
        assertThatThrownBy(() -> ApiVersion.fromDatabase(-1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ApiVersion.fromDatabase(Long.MAX_VALUE)).isInstanceOf(IllegalStateException.class);
    }
}
