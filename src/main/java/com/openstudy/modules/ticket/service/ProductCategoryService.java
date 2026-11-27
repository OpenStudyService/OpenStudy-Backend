package com.openstudy.modules.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.entity.CategoryStatus;
import com.openstudy.modules.ticket.repository.ProductCategoryRepository;

/**
 * Statistics categories shared by all branches (FR-PROD-07). Only OWNER manages them (Q6). The code and the kind
 * never change, and past product revisions copy the category code and name, so renaming never rewrites history.
 */
@Service
@RequiredArgsConstructor
public class ProductCategoryService {

    private static final Set<String> KINDS = Set.of(ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE);
    private static final String ACTIVE_LABEL = "활성";
    private static final String INACTIVE_LABEL = "비활성";
    private static final Set<String> STATUSES = Set.of(ACTIVE_LABEL, INACTIVE_LABEL);
    private static final String CREATE = "product-categories:create";
    private static final String ALL_SCOPE = "all";
    private static final String AUDIT_TABLE = "product_categories";
    private static final String CODE_FORMAT = "[A-Z][A-Z0-9_]{0,39}";
    private static final int MAX_CODE_LENGTH = 40;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final ProductCategoryRepository productCategoryRepository;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<ProductCategory> list(long actor, ListQuery query, String kind, String status) {
        if (kind != null && !KINDS.contains(kind)) {
            throw ApiException.validation("kind", "올바른 상품 유형을 선택해 주세요.");
        }

        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation("status", "올바른 분류 상태를 선택해 주세요.");
        }

        authorizationService.current(actor);
        String needle = query.q().toLowerCase(Locale.ROOT);
        List<ProductCategory> rows = productCategoryRepository.findAllByOrderByIdAsc()
                .stream()
                .map(ProductViews::toCategory)
                .filter(category -> kind == null || kind.equals(category.kind()))
                .filter(category -> status == null || status.equals(category.status()))
                .filter(category -> matches(category, needle))
                .toList();
        Comparator<ProductCategory> field = switch (query.sort()) {
            case "name" -> Comparator.comparing(ProductCategory::name);
            case "code" -> Comparator.comparing(ProductCategory::code);
            default -> Comparator.comparing(ProductCategory::createdAt);
        };
        Comparator<ProductCategory> tieBreaker = Comparator.comparingLong(category -> Long.parseLong(category.id()));

        return PageResponse.fromAuthorized(rows, query, field, tieBreaker);
    }

    public ProductCategory create(long actor, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("code", "name", "kind");
        String code = input.text("code", MAX_CODE_LENGTH);

        if (!code.matches(CODE_FORMAT)) {
            throw ApiException.validation("code", "대문자로 시작하는 영문 대문자·숫자·밑줄 코드를 입력해 주세요.");
        }

        String name = input.text("name", MAX_NAME_LENGTH);
        String kind = input.choice("kind", ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE);
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }

        byte[] fingerprint = digest(objectMapper.writeValueAsString(Arrays.asList(code, name, kind)));

        return authorizedCommands.ownerCommand(actor, () -> {
            Optional<ProductCategory> storedResult = findStoredResult(actor, key, fingerprint);

            if (storedResult.isPresent()) {
                return storedResult.get();
            }

            // ownerCommand serializes every category create under the guard lock, so this check cannot race;
            // it replaces catching the JDBC DuplicateKeyException, which JPA would surface as a different type.
            if (productCategoryRepository.existsByCode(code)) {
                throw ApiException.conflict("CATEGORY_CODE_EXISTS", "이미 사용 중인 분류 코드입니다.");
            }

            com.openstudy.modules.ticket.entity.ProductCategory newCategory =
                    new com.openstudy.modules.ticket.entity.ProductCategory(
                            code,
                            name,
                            ProductViews.toKind(kind),
                            actor
                    );
            com.openstudy.modules.ticket.entity.ProductCategory category =
                    productCategoryRepository.saveAndFlush(newCategory);
            ProductCategory result = ProductViews.toCategory(category);
            String snapshot = objectMapper.writeValueAsString(result);
            recordChange(actor, category.getId(), "CREATED", null, snapshot, requestId);
            Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, ALL_SCOPE, key, fingerprint, CREATED, snapshot, expiresAt);

            return result;
        });
    }

    public ProductCategory update(long actor, long id, Map<String, Object> body, String requestId) {
        // code and kind are not accepted fields: they identify past statistics and are immutable.
        StrictInput input = new StrictInput(body).only("version", "name", "status");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String name = input.has("name") ? input.text("name", MAX_NAME_LENGTH) : null;
        String status = input.has("status") ? input.choice("status", ACTIVE_LABEL, INACTIVE_LABEL) : null;

        return authorizedCommands.ownerCommand(actor, () -> {
            com.openstudy.modules.ticket.entity.ProductCategory category = productCategoryRepository
                    .findForUpdateById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            ProductCategory before = ProductViews.toCategory(category);

            if (version != before.version()) {
                throw ApiException.versionConflict(before.version(), "/api/product-categories");
            }

            String newName = name == null ? before.name() : name;
            String newStatus = status == null ? before.status() : status;
            boolean isUnchanged = newName.equals(before.name()) && newStatus.equals(before.status());

            if (isUnchanged) {
                return before;
            }

            // Name or status differs, so the row is dirty and @Version advances by exactly one (as version+1 did).
            CategoryStatus categoryStatus = ACTIVE_LABEL.equals(newStatus)
                    ? CategoryStatus.ACTIVE
                    : CategoryStatus.INACTIVE;
            category.change(newName, categoryStatus, actor);
            // @Generated re-reads the DB-maintained updated_at after this UPDATE
            productCategoryRepository.flush();
            ProductCategory after = ProductViews.toCategory(category);
            String beforeJson = objectMapper.writeValueAsString(before);
            String afterJson = objectMapper.writeValueAsString(after);
            recordChange(actor, id, "UPDATED", beforeJson, afterJson, requestId);

            return after;
        });
    }

    private static boolean matches(ProductCategory category, String needle) {
        String text = (category.name() + " " + category.code()).toLowerCase(Locale.ROOT);

        return text.contains(needle);
    }

    /** A stored result of the same request, replayed; the same key with a different input is a conflict. */
    private Optional<ProductCategory> findStoredResult(long actor, String key, byte[] fingerprint) {
        Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, ALL_SCOPE, key);

        if (prior.isEmpty()) {
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), ProductCategory.class));
    }

    private void recordChange(long actor, long id, String action, String before, String after, String requestId) {
        auditTrail.record(AuditEvent.change(actor, null, AUDIT_TABLE, id, action, before, after, requestId));
        // Categories are shared by every branch, so every branch stream is told to reload product data.
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
