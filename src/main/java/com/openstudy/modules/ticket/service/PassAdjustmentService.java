package com.openstudy.modules.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.StrictInput;
import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.ticket.dto.IssuedPass;
import com.openstudy.modules.ticket.entity.Pass;
import com.openstudy.modules.ticket.entity.PassBalanceEvent;
import com.openstudy.modules.ticket.entity.PassStatus;
import com.openstudy.modules.ticket.entity.ProductKind;
import com.openstudy.modules.ticket.repository.PassBalanceEventRepository;
import com.openstudy.modules.ticket.repository.PassRepository;

/**
 * Manual balance and validity correction (M5-D04, FR-PASS-12). The purchase branch decides everything: outside its reach
 * the pass does not exist (404), inside it payments:write is required (403), wherever the pass was used. Requests carry the
 * values after the correction, and the deltas are computed under the pass lock. Check-out takes the same guard and the same
 * row lock, so a stay in progress settles against whichever balance committed last (seat-usage-api.md).
 */
@Service
@RequiredArgsConstructor
public class PassAdjustmentService {
    private static final String ADJUST = "passes:adjust";
    private static final long MAX_MINUTES = 4_294_967_295L;
    private final PassRepository repository;
    private final PassBalanceEventRepository ledger;
    private final PassService passes;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final IdempotencyStore requests;
    private final ObjectMapper json;
    private final Clock clock;

    /** The parsed request. A null amount was not sent; {@code setsExpiry} tells an absent expiry from an explicit null. */
    private record Change(long version, String reason, Long minutes, Long creditKrw, boolean setsExpiry, Instant expiresAt) {
        static Change parse(Map<String, Object> body) {
            var input = new StrictInput(body).only("version", "reason", "remainingMinutes", "remainingCreditKrw", "expiresAt");
            long version = input.version("version");
            String reason = input.text("reason", 500);
            Long minutes = amount(body, "remainingMinutes", MAX_MINUTES);
            Long credit = amount(body, "remainingCreditKrw", ProductInputs.JS_MAX);
            boolean setsExpiry = input.has("expiresAt");
            if (minutes == null && credit == null && !setsExpiry) throw ApiException.validation("body", "조정할 항목이 필요합니다.");
            return new Change(version, reason, minutes, credit, setsExpiry, setsExpiry ? expiry(body.get("expiresAt")) : null);
        }

        private static Long amount(Map<String, Object> body, String field, long max) {
            if (!body.containsKey(field)) return null;
            Object value = body.get(field);
            if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                    || ((Number) value).longValue() < 0 || ((Number) value).longValue() > max)
                throw ApiException.validation(field, "0 이상의 정수를 입력해 주세요.");
            return ((Number) value).longValue();
        }

        private static Instant expiry(Object value) {
            if (value == null) return null;
            if (!(value instanceof String text)) throw ApiException.validation("expiresAt", "ISO 8601 UTC 시각을 입력해 주세요.");
            return TimeRange.instant(text, "expiresAt").truncatedTo(ChronoUnit.MICROS);
        }

        /** Whether the expiry was sent at all is part of the input, so it is part of the request hash. */
        String canonical(long id, ObjectMapper json) {
            return json.writeValueAsString(Arrays.asList(id, version, reason, minutes, creditKrw, setsExpiry,
                    expiresAt == null ? null : expiresAt.toString()));
        }
    }

    public IssuedPass adjust(long actor, long id, String scope, String key, Map<String, Object> body, String requestId) {
        var change = Change.parse(body);
        validKey(key);
        String scopeKey = Long.toString(id);
        byte[] fingerprint = digest(change.canonical(id, json));
        return commands.inRecordBranch(actor, BranchAction.READ, () -> repository.purchaseBranchOf(id), (state, branch) -> {
            if (!policy.scope(state, scope, BranchAction.READ).contains(branch)) throw notFound();
            // Being able to see the pass is not enough: only the purchase branch's payments:write adjusts it (M5-D04).
            policy.require(state, Set.of(branch), BranchAction.WRITE_PAYMENT_HISTORY);
            var prior = requests.lock(actor, ADJUST, scopeKey, key);
            if (prior.isPresent()) {
                if (clock.instant().isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), IssuedPass.class);
                }
                requests.discard(prior.get());
            }
            var pass = repository.locked(id).orElseThrow(PassAdjustmentService::notFound);
            requireFitting(pass, change);
            if (pass.getStatus() == PassStatus.REFUNDED) throw ApiException.conflict("PASS_REFUNDED", "환불된 이용권은 조정할 수 없습니다.");
            // NOT_STARTED survives only in the V1 enum (ADR-M4-002), and ck_passes_period_activation forbids it an expiry.
            if (pass.getStatus() == PassStatus.NOT_STARTED) throw new ResponseStatusException(HttpStatus.CONFLICT);
            ApiVersion.requireCurrent(change.version(), pass.getVersion(), "/api/passes/" + id);
            // The processing instant: read after every lock, like check-in and check-out.
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Long minutes = change.minutes() == null ? pass.getRemainingMinutes() : change.minutes();
            Long credit = change.creditKrw() == null ? pass.getRemainingCreditKrw() : change.creditKrw();
            Instant expiry = change.setsExpiry() ? change.expiresAt() : pass.getExpiresAt();
            boolean changed = !Objects.equals(minutes, pass.getRemainingMinutes()) || !Objects.equals(credit, pass.getRemainingCreditKrw())
                    || !Objects.equals(expiry, pass.getExpiresAt());
            if (changed) apply(actor, pass, branch, change.reason(), minutes, credit, expiry, now, storedKey(actor, scopeKey, key), requestId);
            var result = passes.view(pass, state, now);
            requests.complete(actor, ADJUST, scopeKey, key, fingerprint, 200, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    /** Depends on the pass, so it runs after the 404 and 403 of the contract table: the kind stays hidden from others. */
    private static void requireFitting(Pass pass, Change change) {
        if (change.minutes() != null && pass.getKind() != ProductKind.TIME)
            throw ApiException.validation("remainingMinutes", "시간권만 남은 시간을 조정할 수 있습니다.");
        if (change.creditKrw() != null && pass.getKind() != ProductKind.STORED_VALUE)
            throw ApiException.validation("remainingCreditKrw", "정액권만 남은 금액을 조정할 수 있습니다.");
        if (!change.setsExpiry()) return;
        if (change.expiresAt() == null) {
            if (pass.getKind() == ProductKind.PERIOD) throw ApiException.validation("expiresAt", "기간권은 만료 시각이 필요합니다.");
        } else if (pass.getStartedAt() != null && !change.expiresAt().isAfter(pass.getStartedAt())) {
            throw ApiException.validation("expiresAt", "만료 시각은 시작 시각보다 뒤여야 합니다.");
        }
    }

    private void apply(long actor, Pass pass, long branch, String reason, Long minutes, Long credit, Instant expiry, Instant now,
            String eventKey, String requestId) {
        String before = snapshot(pass);
        long deltaMinutes = minutes == null ? 0 : minutes - pass.getRemainingMinutes();
        long deltaKrw = credit == null ? 0 : credit - pass.getRemainingCreditKrw();
        try {
            DuplicateKeys.translate(() -> ledger.saveAndFlush(PassBalanceEvent.adjust(pass.getId(), deltaMinutes, deltaKrw, actor,
                    reason, eventKey, now)));
        } catch (DuplicateKeyException exception) {
            // The stored key repeats only when a key is reused after its idempotency record expired.
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "이미 사용한 요청 키입니다.");
        }
        pass.adjust(minutes, credit, expiry, now);
        repository.saveAndFlush(pass);
        audit.record(AuditEvent.change(actor, branch, "passes", pass.getId(), "ADJUSTED", reason, before, snapshot(pass), requestId));
    }

    /** The ledger has no expiry column, so the audit row is where the values before and after stay (contract). */
    private String snapshot(Pass pass) {
        var values = new LinkedHashMap<String, Object>();
        values.put("remainingMinutes", pass.getRemainingMinutes());
        values.put("remainingCreditKrw", pass.getRemainingCreditKrw());
        values.put("expiresAt", pass.getExpiresAt() == null ? null : pass.getExpiresAt().toString());
        values.put("status", pass.getStatus().name());
        return json.writeValueAsString(values);
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

    /** event_key is globally unique and 120 characters, so it holds a hash (contract "멱등 키"). */
    private static String storedKey(long actor, String scopeKey, String key) {
        return "ADJ-" + HexFormat.of().formatHex(digest(actor + "\n" + ADJUST + "\n" + scopeKey + "\n" + key));
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
