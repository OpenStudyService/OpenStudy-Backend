package com.openstudy.modules.facility.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
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
    private final FacilityTypeRepository repository;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final IdempotencyStore requests;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true)
    public FacilityTypeList list(long actor) {
        policy.current(actor);
        return new FacilityTypeList(repository.findAllByOrderByNameAscIdAsc().stream().map(FacilityTypeService::view).toList());
    }

    public FacilityTypeView create(long actor, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("name", "defaultInspectionDays");
        String name = input.text("name", 80);
        if (!body.containsKey("defaultInspectionDays")) throw ApiException.validation("defaultInspectionDays", "점검 주기를 입력해 주세요.");
        int days = FacilityInputs.days(body.get("defaultInspectionDays"), "defaultInspectionDays");
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(name, days)));
        return commands.ownerCommand(actor, () -> {
            var prior = requests.lock(actor, CREATE, "all", key);
            var now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), FacilityTypeView.class);
                }
                requests.discard(prior.get());
            }
            // ownerCommand serializes every owner write under the guard lock, so this check cannot race with another
            // create; it replaces catching the JDBC duplicate-key error, which JPA would surface as a different type.
            if (repository.existsByName(name)) throw ApiException.conflict("DUPLICATE_FACILITY_TYPE", "이미 등록된 시설 유형입니다.");
            var type = repository.saveAndFlush(new FacilityType(name, days));
            var result = view(type);
            String snapshot = json.writeValueAsString(result);
            audit.record(AuditEvent.change(actor, null, "facility_types", type.getId(), "CREATED", null, snapshot, requestId));
            requests.complete(actor, CREATE, "all", key, fingerprint, 201, snapshot, now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    static FacilityTypeView view(FacilityType type) {
        return new FacilityTypeView(type.getId().toString(), type.getName(), type.getDefaultInspectionDays());
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
