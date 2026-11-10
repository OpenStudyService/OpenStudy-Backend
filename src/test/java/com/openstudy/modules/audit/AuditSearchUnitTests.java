package com.openstudy.modules.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.audit.dto.request.AuditSearchCriteria;
import com.openstudy.modules.audit.repository.AuditEventSearchRepository;
import com.openstudy.modules.audit.service.AuditEventSearchService;
import com.openstudy.modules.authorization.service.AuthorizationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #122 without a database: the query rules of GET /audit-events (M14-D12), the OWNER check (M14-D04) and the masking of
 * snapshots on read (account-collab-api.md "민감 값 처리"). SQL itself is left to the milestone integration run.
 */
class AuditSearchUnitTests {
    /** 2026-11-10 09:00 in Seoul. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);

    static Map<String, String[]> query(String... pairs) {
        var map = new LinkedHashMap<String, String[]>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], new String[] {pairs[i + 1]});
        return map;
    }

    static AuditSearchCriteria parse(String... pairs) { return AuditSearchCriteria.parse(query(pairs), CLOCK); }

    static String code(Executable call) {
        var error = assertThrows(ApiException.class, call);
        return error.error("test").code();
    }

    static String field(Executable call) {
        var error = assertThrows(ApiException.class, call);
        return error.error("test").fieldErrors().keySet().iterator().next();
    }

    // ---- period (M14-D12) --------------------------------------------------------------------------------------------

    @Test void withoutAPeriodTheLastSevenDaysWithTodayAreSearched() {
        var criteria = parse();
        assertThat(criteria.from()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(criteria.to()).isEqualTo(LocalDate.parse("2026-11-10"));
        // Seoul days in UTC: 2026-11-04 00:00 KST is 2026-11-03 15:00Z, the end is 2026-11-11 00:00 KST.
        assertThat(criteria.start()).isEqualTo(Instant.parse("2026-11-03T15:00:00Z"));
        assertThat(criteria.end()).isEqualTo(Instant.parse("2026-11-10T15:00:00Z"));
    }

    @Test void oneEndFillsTheOtherToSevenDays() {
        assertThat(parse("from", "2026-10-01").to()).isEqualTo(LocalDate.parse("2026-10-07"));
        assertThat(parse("to", "2026-10-07").from()).isEqualTo(LocalDate.parse("2026-10-01"));
    }

    @Test void ninetyTwoDaysAreAllowedAndOneMoreIsTooLong() {
        var longest = parse("from", "2026-08-01", "to", "2026-10-31");
        assertThat(longest.to().toEpochDay() - longest.from().toEpochDay() + 1).isEqualTo(92);
        assertThat(code(() -> parse("from", "2026-08-01", "to", "2026-11-01"))).isEqualTo("PERIOD_TOO_LONG");
    }

    @Test void aReversedOrMalformedPeriodIsAValidationError() {
        assertThat(code(() -> parse("from", "2026-10-10", "to", "2026-10-09"))).isEqualTo("VALIDATION_FAILED");
        assertThat(field(() -> parse("from", "2026-10-1"))).isEqualTo("from");
        assertThat(field(() -> parse("to", "2026-10-01T00:00:00Z"))).isEqualTo("to");
        assertThat(field(() -> parse("from", "2026-02-30"))).isEqualTo("from");
    }

    // ---- allowed conditions ------------------------------------------------------------------------------------------

    @Test void everyContractConditionIsAccepted() {
        var criteria = parse("entityType", "employee", "entityId", "7", "action", "LOGIN_FAILED", "actorEmployeeId", "3",
                "branchId", "2", "result", "FAILED", "requestId", "req-1", "page", "2", "pageSize", "50");
        assertThat(criteria.entityType()).isEqualTo("employee");
        assertThat(criteria.entityId()).isEqualTo(7L);
        assertThat(criteria.action()).isEqualTo("LOGIN_FAILED");
        assertThat(criteria.actorEmployeeId()).isEqualTo(3L);
        assertThat(criteria.branchId()).isEqualTo(2L);
        assertThat(criteria.result()).isEqualTo("FAILED");
        assertThat(criteria.requestId()).isEqualTo("req-1");
        assertThat(criteria.offset()).isEqualTo(50);
    }

    @Test void unknownRepeatedOrOutOfRangeConditionsAreRejected() {
        assertThat(field(() -> parse("q", "x"))).isEqualTo("q");
        assertThat(field(() -> parse("entityId", "7"))).as("entityId needs entityType").isEqualTo("entityId");
        assertThat(field(() -> parse("result", "OK"))).isEqualTo("result");
        assertThat(field(() -> parse("actorEmployeeId", "0"))).isEqualTo("actorEmployeeId");
        assertThat(field(() -> parse("branchId", "99999999999999999999"))).isEqualTo("branchId");
        assertThat(field(() -> parse("action", ""))).isEqualTo("action");
        assertThat(field(() -> parse("entityType", "x".repeat(81)))).isEqualTo("entityType");
        assertThat(field(() -> parse("requestId", "r".repeat(129)))).isEqualTo("requestId");
        assertThat(field(() -> parse("pageSize", "101"))).isEqualTo("pageSize");
        assertThat(field(() -> parse("page", "0"))).isEqualTo("page");
        var twice = new LinkedHashMap<String, String[]>();
        twice.put("action", new String[] {"A", "B"});
        assertThat(field(() -> AuditSearchCriteria.parse(twice, CLOCK))).isEqualTo("action");
    }

    // ---- OWNER only (M14-D04) and masking ----------------------------------------------------------------------------

    final AuditEventSearchRepository repository = mock(AuditEventSearchRepository.class);
    final AuthorizationService policy = mock(AuthorizationService.class);
    final AuditEventSearchService service = new AuditEventSearchService(repository, policy, JsonMapper.builder().build(), CLOCK);

    void notOwner() {
        doThrow(AuthorizationService.forbidden()).when(policy).requireOwner(any());
    }

    @Test void othersAreForbiddenBeforeTheirQueryIsLookedAt() {
        notOwner();
        // An invalid query still answers 403, so a non-owner learns nothing from validation.
        assertThrows(AccessDeniedException.class, () -> service.search(5, query("q", "x")));
        assertThrows(AccessDeniedException.class, () -> service.get(5, 1));
        verify(repository, never()).search(any());
        verify(repository, never()).find(any(Long.class));
    }

    @Test void aMissingEventIsNotFound() {
        when(repository.find(9)).thenReturn(Optional.empty());
        assertThat(assertThrows(ApiException.class, () -> service.get(1, 9)).status()).isEqualTo(404);
    }

    static AuditEventSearchRepository.Row row(String before, String after) {
        return new AuditEventSearchRepository.Row(4, Instant.parse("2026-11-09T01:02:03.456789Z"), "UPDATED", "SUCCESS",
                "customer", 12L, "사유", "req-9", 3L, "관리자", null, null, before != null || after != null, before, after);
    }

    @Test void theDetailMasksSecretsAndContactsAtAnyDepth() {
        when(repository.find(4)).thenReturn(Optional.of(row(
                "{\"name\":\"김오픈\",\"phone\":\"010-1234-5678\",\"passwordHash\":\"$2a$x\",\"nested\":{\"resetToken\":\"abc\","
                        + "\"contacts\":[\"x\"]},\"items\":[{\"CSRF\":\"t\",\"memo\":\"그대로\"}]}",
                "{\"emergencyContact\":\"02-555-0199\",\"Secret\":1,\"credentials\":{\"a\":1}}")));
        var detail = service.get(1, 4);
        assertThat(detail.id()).isEqualTo("4");
        assertThat(detail.entityId()).isEqualTo("12");
        assertThat(detail.actor().name()).isEqualTo("관리자");
        assertThat(detail.branch()).isNull();
        var before = detail.before();
        assertThat(before.get("name").asString()).isEqualTo("김오픈");
        assertThat(before.get("phone").asString()).isEqualTo("***-****-5678");
        assertThat(before.get("passwordHash").asString()).isEqualTo("***");
        assertThat(before.at("/nested/resetToken").asString()).isEqualTo("***");
        // A contact key holding no string is walked, not replaced.
        assertThat(before.at("/nested/contacts/0").asString()).isEqualTo("x");
        assertThat(before.at("/items/0/CSRF").asString()).isEqualTo("***");
        assertThat(before.at("/items/0/memo").asString()).isEqualTo("그대로");
        var after = detail.after();
        assertThat(after.get("emergencyContact").asString()).isEqualTo("**-***-0199");
        assertThat(after.get("Secret").asString()).isEqualTo("***");
        assertThat(after.get("credentials").asString()).isEqualTo("***");
        assertThat(detail.toString()).doesNotContain("1234", "$2a$x", "abc", "555");
    }

    @Test void aDetailWithoutSnapshotsHasNullValues() {
        when(repository.find(4)).thenReturn(Optional.of(row(null, null)));
        var detail = service.get(1, 4);
        assertThat(detail.before()).isNull();
        assertThat(detail.after()).isNull();
        assertThat(detail.hasChanges()).isFalse();
    }

    @Test void searchReportsTheAppliedPeriodAndPagingWithoutSnapshots() {
        when(repository.search(any())).thenReturn(new AuditEventSearchRepository.Page(List.of(row("{\"a\":1}", null)), 41));
        var page = service.search(1, query("pageSize", "20", "page", "3"));
        assertThat(page.period().from()).isEqualTo(LocalDate.parse("2026-11-04"));
        assertThat(page.period().to()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(page.meta().total()).isEqualTo(41);
        assertThat(page.meta().page()).isEqualTo(3);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().hasChanges()).isTrue();
        assertThat(page.items().getFirst().actor().id()).isEqualTo("3");
    }
}
