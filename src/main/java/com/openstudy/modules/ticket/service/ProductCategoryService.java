package com.openstudy.modules.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.repository.ProductCategoryRepository;

/**
 * Statistics categories shared by all branches (FR-PROD-07). Only OWNER manages them (Q6). The code and the kind
 * never change, and past product revisions copy the category code and name, so renaming never rewrites history.
 */
@Service
public class ProductCategoryService {
    private static final Set<String> KINDS = Set.of(ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE);
    private static final Set<String> STATUSES = Set.of("활성", "비활성");
    private final ProductCategoryRepository repository;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;

    public ProductCategoryService(ProductCategoryRepository repository, AuthorizationService policy,
            AuthorizedCommands commands, ObjectMapper json) {
        this.repository = repository;
        this.policy = policy;
        this.commands = commands;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductCategory> list(long actor, ListQuery query, String kind, String status) {
        if (kind != null && !KINDS.contains(kind)) throw ApiException.validation("kind", "올바른 상품 유형을 선택해 주세요.");
        if (status != null && !STATUSES.contains(status)) throw ApiException.validation("status", "올바른 분류 상태를 선택해 주세요.");
        policy.current(actor);
        String needle = query.q().toLowerCase(Locale.ROOT);
        var rows = repository.all().stream()
                .filter(category -> kind == null || kind.equals(category.kind()))
                .filter(category -> status == null || status.equals(category.status()))
                .filter(category -> (category.name() + " " + category.code()).toLowerCase(Locale.ROOT).contains(needle)).toList();
        Comparator<ProductCategory> field = switch (query.sort()) {
            case "name" -> Comparator.comparing(ProductCategory::name);
            case "code" -> Comparator.comparing(ProductCategory::code);
            default -> Comparator.comparing(ProductCategory::createdAt);
        };
        return PageResponse.fromAuthorized(rows, query, field, Comparator.comparingLong(category -> Long.parseLong(category.id())));
    }

    public ProductCategory create(long actor, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("code", "name", "kind");
        String code = input.text("code", 40);
        if (!code.matches("[A-Z][A-Z0-9_]{0,39}")) throw ApiException.validation("code", "대문자로 시작하는 영문 대문자·숫자·밑줄 코드를 입력해 주세요.");
        String name = input.text("name", 100);
        String kind = input.choice("kind", ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE);
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(code, name, kind)));
        return commands.ownerCommand(actor, () -> {
            var prior = repository.request(actor, key);
            if (!prior.isEmpty()) {
                if (!MessageDigest.isEqual(fingerprint, prior.getFirst().hash()))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.getFirst().response(), ProductCategory.class);
            }
            long id;
            try { id = repository.create(actor, code, name, kind); }
            catch (DuplicateKeyException exception) {
                throw ApiException.conflict("CATEGORY_CODE_EXISTS", "이미 사용 중인 분류 코드입니다.");
            }
            var result = repository.find(id).orElseThrow();
            String snapshot = json.writeValueAsString(result);
            repository.audit(actor, id, "CREATED", null, snapshot, requestId);
            repository.saveRequest(actor, key, fingerprint, snapshot);
            return result;
        });
    }

    public ProductCategory update(long actor, long id, Map<String, Object> body, String requestId) {
        // code and kind are not accepted fields: they identify past statistics and are immutable.
        var input = new StrictInput(body).only("version", "name", "status");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String name = input.has("name") ? input.text("name", 100) : null;
        String status = input.has("status") ? input.choice("status", "활성", "비활성") : null;
        return commands.ownerCommand(actor, () -> {
            var before = repository.findForUpdate(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (version != before.version())
                throw ApiException.versionConflict(before.version(), "/api/product-categories");
            String newName = name == null ? before.name() : name;
            String newStatus = status == null ? before.status() : status;
            if (newName.equals(before.name()) && newStatus.equals(before.status())) return before;
            repository.update(actor, id, newName, newStatus);
            var after = repository.find(id).orElseThrow();
            repository.audit(actor, id, "UPDATED", json.writeValueAsString(before), json.writeValueAsString(after), requestId);
            return after;
        });
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
