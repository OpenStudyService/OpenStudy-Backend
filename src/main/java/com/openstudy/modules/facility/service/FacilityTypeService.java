package com.openstudy.modules.facility.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.facility.dto.response.FacilityTypeList;
import com.openstudy.modules.facility.dto.response.FacilityTypeView;
import com.openstudy.modules.facility.entity.FacilityType;
import com.openstudy.modules.facility.repository.FacilityTypeRepository;

/**
 * The global facility type code table (FR-FAC-02, collaboration-api.md). Any active workspace session reads it; only
 * OWNER adds a type, like a product category. There is no update or delete.
 */
@Service
@RequiredArgsConstructor
public class FacilityTypeService {

    private static final String CREATE = "facility-types:create";
    private static final String ALL_SCOPE = "all";
    private static final String AUDIT_TABLE = "facility_types";
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final FacilityTypeRepository facilityTypeRepository;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final IdempotencyStore idempotencyStore;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public FacilityTypeList list(long actor) {
        authorizationService.current(actor);
        List<FacilityTypeView> types = facilityTypeRepository.findAllByOrderByNameAscIdAsc()
                .stream()
                .map(FacilityTypeService::toView)
                .toList();

        return new FacilityTypeList(types);
    }

    public FacilityTypeView create(long actor, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("name", "defaultInspectionDays");
        String name = input.text("name", MAX_NAME_LENGTH);

        if (!body.containsKey("defaultInspectionDays")) {
            throw ApiException.validation("defaultInspectionDays", "점검 주기를 입력해 주세요.");
        }

        int days = FacilityInputs.parseDays(body.get("defaultInspectionDays"), "defaultInspectionDays");
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }

        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(name, days)));

        return authorizedCommands.ownerCommand(actor, () -> {
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, ALL_SCOPE, key);
            Instant now = clock.instant();

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    }

                    return objectMapper.readValue(prior.get().getResponseJson(), FacilityTypeView.class);
                }

                idempotencyStore.discard(prior.get());
            }

            // ownerCommand serializes every owner write under the guard lock, so this check cannot race with another
            // create; it replaces catching the JDBC duplicate-key error, which JPA would surface as a different type.
            if (facilityTypeRepository.existsByName(name)) {
                throw ApiException.conflict("DUPLICATE_FACILITY_TYPE", "이미 등록된 시설 유형입니다.");
            }

            FacilityType type = facilityTypeRepository.saveAndFlush(new FacilityType(name, days));
            FacilityTypeView result = toView(type);
            String snapshot = objectMapper.writeValueAsString(result);
            auditTrail.record(AuditEvent.change(actor, null, AUDIT_TABLE, type.getId(), "CREATED", null, snapshot,
                    requestId));
            Instant expiresAt = now.plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, ALL_SCOPE, key, fingerprint, CREATED, snapshot, expiresAt);

            return result;
        });
    }

    static FacilityTypeView toView(FacilityType type) {
        return new FacilityTypeView(type.getId().toString(), type.getName(), type.getDefaultInspectionDays());
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
