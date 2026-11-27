package com.openstudy.modules.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.exception.OperationsConflict;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.common.web.TimeRange;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.ticket.dto.Product;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductRevision;
import com.openstudy.modules.ticket.dto.ProductSnapshot;
import com.openstudy.modules.ticket.dto.ProductTerms;
import com.openstudy.modules.ticket.dto.SaleTerms;
import com.openstudy.modules.ticket.entity.RevisionEvent;
import com.openstudy.modules.ticket.repository.ProductCategoryRepository;
import com.openstudy.modules.ticket.repository.ProductRepository;
import com.openstudy.modules.ticket.repository.ProductRevisionRepository;

/**
 * Pass product catalogue. Products are shared across their sale branches (Q7), so every write is authorized
 * against the persisted branch set read under the product lock, never against a client-supplied flag. SRS v1.1
 * provides no physical delete: a product is stopped and resumed, and its ID, revisions and purchase terms stay.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final String ON_SALE = "판매 중";
    private static final String STOPPED = "판매 중지";
    private static final String CREATE = "products:create";
    /** saleAvailability labels (M13-D06), in the order a sale is judged. */
    static final String AVAILABLE = "판매 가능";
    static final String NOT_YET = "판매 전";
    static final String ENDED = "판매 종료";
    static final String SOLD_OUT = "한도 소진";
    private static final Set<String> AVAILABILITIES = Set.of(AVAILABLE, STOPPED, NOT_YET, ENDED, SOLD_OUT);
    private static final Set<String> SALE_STATUSES = Set.of(ON_SALE, STOPPED);
    private static final Set<String> KINDS = Set.of(ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE);
    private static final long LIMIT_MAX = 1_000_000;
    private static final String ALL_SCOPE = "all";
    private static final String AUDIT_TABLE = "products";
    private static final String ACTIVE_CATEGORY = "활성";
    private static final String CODE_PREFIX = "PD-";
    private static final int CODE_RANDOM_LENGTH = 29;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final ProductRevisionRepository productRevisionRepository;
    private final EntityManager entityManager;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    private record Assignable(com.openstudy.modules.ticket.entity.ProductCategory entity, ProductCategory view) {
    }

    /**
     * The sale fields of a create or PATCH body: an absent field keeps the current value, null clears it (M13-D06).
     * Times carry an offset and whole seconds; the period must end after it starts.
     */
    record SalesInput(
            boolean startsGiven,
            Instant startsAt,
            boolean endsGiven,
            Instant endsAt,
            boolean limitGiven,
            Integer limit
    ) {

        static SalesInput parse(Map<String, Object> body) {
            boolean startsGiven = body.containsKey("salesStartsAt");
            Instant startsAt = parseTime(body, "salesStartsAt");
            boolean endsGiven = body.containsKey("salesEndsAt");
            Instant endsAt = parseTime(body, "salesEndsAt");
            boolean limitGiven = body.containsKey("salesLimit");
            Integer limit = body.get("salesLimit") == null
                    ? null
                    : (int) ProductInputs.parseInteger(body.get("salesLimit"), "salesLimit", 1, LIMIT_MAX);

            return new SalesInput(startsGiven, startsAt, endsGiven, endsAt, limitGiven, limit);
        }

        ProductRecord.Sales over(ProductRecord.Sales current) {
            ProductRecord.Sales merged = new ProductRecord.Sales(
                    startsGiven ? startsAt : current.startsAt(),
                    endsGiven ? endsAt : current.endsAt(),
                    limitGiven ? limit : current.limit()
            );
            boolean isEmptyPeriod = merged.startsAt() != null
                    && merged.endsAt() != null
                    && !merged.startsAt().isBefore(merged.endsAt());

            if (isEmptyPeriod) {
                throw ApiException.validation("salesEndsAt", "판매 종료는 판매 시작보다 뒤여야 합니다.");
            }

            return merged;
        }

        private static Instant parseTime(Map<String, Object> body, String field) {
            Object value = body.get(field);

            if (value == null) {
                return null;
            }

            if (!(value instanceof String text)) {
                throw ApiException.validation(field, "ISO 8601 시각을 입력해 주세요.");
            }

            Instant parsed = TimeRange.instant(text, field);

            if (parsed.getNano() != 0) {
                throw ApiException.validation(field, "초 단위까지만 입력해 주세요.");
            }

            return parsed;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Product> list(
            long actor,
            String scope,
            ListQuery query,
            String status,
            String kind,
            String categoryId,
            String availability
    ) {
        if (status != null && !SALE_STATUSES.contains(status)) {
            throw ApiException.validation("status", "올바른 판매 상태를 선택해 주세요.");
        }

        if (availability != null && !AVAILABILITIES.contains(availability)) {
            throw ApiException.validation("availability", "올바른 판매 가능 여부를 선택해 주세요.");
        }

        if (kind != null && !KINDS.contains(kind)) {
            throw ApiException.validation("kind", "올바른 상품 유형을 선택해 주세요.");
        }

        Long category = categoryId == null ? null : ProductInputs.parseId(categoryId, "categoryId");
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        Set<Long> visible = authorizationService.scope(state, ALL_SCOPE, BranchAction.READ);
        String needle = query.q().toLowerCase(Locale.ROOT);
        List<ProductRecord> matching = productRepository.findAllWithDetailsByOrderByIdAsc()
                .stream()
                .map(ProductViews::toRecord)
                .filter(product -> !Collections.disjoint(product.branchIds(), selected))
                .filter(product -> status == null || status.equals(toStatusLabel(product)))
                .filter(product -> kind == null || kind.equals(product.terms().kind()))
                .filter(product -> category == null || category == product.categoryId())
                .filter(product -> matches(product, needle))
                .toList();
        Map<Long, Long> sold = new HashMap<>();

        if (!matching.isEmpty()) {
            List<Long> productIds = matching.stream()
                    .map(ProductRecord::id)
                    .toList();
            productRepository.countSoldByProduct(productIds)
                    .forEach(row -> sold.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        }

        Instant now = clock.instant();
        List<Product> rows = matching.stream()
                .map(product -> toProduct(product, state, visible, sold.getOrDefault(product.id(), 0L), now))
                .filter(product -> availability == null || availability.equals(product.saleAvailability()))
                .toList();
        Comparator<Product> field = switch (query.sort()) {
            case "name" -> Comparator.comparing(Product::name);
            case "priceKrw" -> Comparator.comparingLong(Product::priceKrw);
            default -> Comparator.comparing(Product::createdAt);
        };
        Comparator<Product> tieBreaker = Comparator.comparingLong(product -> Long.parseLong(product.id()));

        return PageResponse.fromAuthorized(rows, query, field, tieBreaker);
    }

    @Transactional(readOnly = true)
    public Product get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        ProductRecord product = findVisibleProduct(state, id, scope);

        return toProduct(product, state, findReadableBranches(state));
    }

    public Product create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only(
                "name",
                "priceKrw",
                "terms",
                "status",
                "categoryId",
                "branchIds",
                "salesStartsAt",
                "salesEndsAt",
                "salesLimit"
        );
        String name = input.text("name", MAX_NAME_LENGTH);
        long price = ProductInputs.parsePrice(body.get("priceKrw"));
        ProductTerms terms = ProductInputs.parseTerms(body.get("terms"));
        boolean onSale = ON_SALE.equals(input.choice("status", ON_SALE, STOPPED));
        long categoryId = input.id("categoryId");
        Set<Long> branches = ProductInputs.parseBranchIds(body.get("branchIds"));
        ProductRecord.Sales sales = SalesInput.parse(body).over(ProductRecord.Sales.NONE);
        requireValidKey(key);
        // scope=all never means "sell everywhere"; it is only checked for syntax and for a readable branch.
        authorizationService.scope(actor, scope, BranchAction.READ);
        List<Object> fingerprintInput = Arrays.asList(
                name,
                price,
                terms,
                onSale,
                categoryId,
                branches,
                String.valueOf(sales.startsAt()),
                String.valueOf(sales.endsAt()),
                sales.limit()
        );
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, branches, BranchAction.EDIT_PRODUCT, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<Product> storedResult = findStoredResult(actor, key, fingerprint);

            if (storedResult.isPresent()) {
                return storedResult.get();
            }

            Assignable category = findAssignableCategory(categoryId, terms.kind());
            String code = createCode();
            com.openstudy.modules.ticket.entity.Product entity = new com.openstudy.modules.ticket.entity.Product(
                    code,
                    name,
                    ProductViews.toKind(terms.kind()),
                    price,
                    ProductViews.toColumns(terms),
                    onSale,
                    category.entity(),
                    branches,
                    actor
            );
            entity.changeSales(sales.startsAt(), sales.endsAt(), sales.limit());
            com.openstudy.modules.ticket.entity.Product product = productRepository.saveAndFlush(entity);
            ProductRecord created = ProductViews.toRecord(product);
            long id = created.id();
            productRevisionRepository.save(new com.openstudy.modules.ticket.entity.ProductRevision(
                    id,
                    created.dbVersion(),
                    RevisionEvent.CREATED,
                    actor,
                    requestId,
                    toSnapshotJson(created)
            ));
            auditTrail.record(AuditEvent.change(actor, null, AUDIT_TABLE, id, "CREATED", null, toSnapshotJson(created),
                    requestId));
            Product result = toProduct(created, state, findReadableBranches(state));
            String responseJson = objectMapper.writeValueAsString(result);
            Instant expiresAt = clock.instant().plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, ALL_SCOPE, key, fingerprint, CREATED, responseJson, expiresAt);

            return result;
        });
    }

    public Product update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only(
                "version",
                "name",
                "priceKrw",
                "terms",
                "categoryId",
                "salesStartsAt",
                "salesEndsAt",
                "salesLimit"
        );

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String name = input.has("name") ? input.text("name", MAX_NAME_LENGTH) : null;
        Long price = input.has("priceKrw") ? ProductInputs.parsePrice(body.get("priceKrw")) : null;
        ProductTerms terms = input.has("terms") ? ProductInputs.parseTerms(body.get("terms")) : null;
        Long categoryId = input.has("categoryId") ? input.id("categoryId") : null;
        SalesInput salesInput = SalesInput.parse(body);
        ProductRecord found = findVisibleProduct(authorizationService.current(actor), id, scope);
        Set<Long> branches = Set.copyOf(found.branchIds());

        return authorizedCommands.inBranches(
                actor,
                branches,
                BranchAction.EDIT_PRODUCT,
                () -> productRepository.lock(id),
                state -> {
                    com.openstudy.modules.ticket.entity.Product product = findLockedProduct(id);
                    ProductRecord before = ProductViews.toRecord(product);
                    Set<Long> persisted = Set.copyOf(before.branchIds());
                    authorizationService.requireSharedProduct(state, persisted, persisted, false);
                    ApiVersion.requireCurrent(version, before.dbVersion(), toReloadPath(id));

                    if (terms != null && !terms.kind().equals(before.terms().kind())) {
                        throw ApiException.validation("terms.kind", "상품 유형은 변경할 수 없습니다. 새 상품으로 등록해 주세요.");
                    }

                    ProductCategory category = categoryId == null || categoryId == before.categoryId()
                            ? before.category()
                            : findAssignableCategory(categoryId, before.terms().kind()).view();
                    String newName = name == null ? before.name() : name;
                    long newPrice = price == null ? before.priceKrw() : price;
                    ProductTerms newTerms = terms == null ? before.terms() : terms;
                    ProductRecord.Sales newSales = salesInput.over(before.sales());
                    boolean isUnchanged = newName.equals(before.name())
                            && newPrice == before.priceKrw()
                            && newTerms.equals(before.terms())
                            && category.id().equals(before.category().id())
                            && newSales.equals(before.sales());

                    // An identical request is not a change: no version bump, revision, audit row or event.
                    if (isUnchanged) {
                        return toProduct(before, state, findReadableBranches(state));
                    }

                    return write(actor, state, product, before, newName, newPrice, newTerms,
                            Long.parseLong(category.id()), before.onSale(), newSales, "UPDATED", requestId);
                }
        );
    }

    public Product changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        boolean onSale = ON_SALE.equals(input.choice("status", ON_SALE, STOPPED));
        ProductRecord found = findVisibleProduct(authorizationService.current(actor), id, scope);
        Set<Long> branches = Set.copyOf(found.branchIds());

        return authorizedCommands.inBranches(
                actor,
                branches,
                BranchAction.MANAGE_PRODUCT,
                () -> productRepository.lock(id),
                state -> {
                    com.openstudy.modules.ticket.entity.Product product = findLockedProduct(id);
                    ProductRecord before = ProductViews.toRecord(product);
                    Set<Long> persisted = Set.copyOf(before.branchIds());
                    authorizationService.requireSharedProduct(state, persisted, persisted, true);
                    ApiVersion.requireCurrent(version, before.dbVersion(), toReloadPath(id));

                    if (before.onSale() == onSale) {
                        return toProduct(before, state, findReadableBranches(state));
                    }

                    return write(actor, state, product, before, before.name(), before.priceKrw(), before.terms(),
                            before.categoryId(), onSale, before.sales(), "STATUS_CHANGED", requestId);
                }
        );
    }

    public Product replaceBranches(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "branchIds");
        long version = input.version("version");
        Set<Long> requested = ProductInputs.parseBranchIds(body.get("branchIds"));
        ProductRecord found = findVisibleProduct(authorizationService.current(actor), id, scope);
        Set<Long> union = new TreeSet<>(found.branchIds());
        union.addAll(requested);

        return authorizedCommands.inBranches(
                actor,
                union,
                BranchAction.MANAGE_PRODUCT,
                () -> productRepository.lock(id),
                state -> {
                    com.openstudy.modules.ticket.entity.Product product = findLockedProduct(id);
                    ProductRecord before = ProductViews.toRecord(product);
                    Set<Long> persisted = Set.copyOf(before.branchIds());
                    // Both the stored and the new scope must be manageable, so a hidden branch cannot be dropped or added.
                    authorizationService.requireSharedProduct(state, persisted, requested, true);
                    ApiVersion.requireCurrent(version, before.dbVersion(), toReloadPath(id));

                    if (persisted.equals(requested)) {
                        return toProduct(before, state, findReadableBranches(state));
                    }

                    Set<Long> added = new TreeSet<>(requested);
                    Set<Long> removed = new TreeSet<>(persisted);
                    added.removeAll(persisted);
                    removed.removeAll(requested);
                    product.replaceBranches(removed, added);

                    return write(actor, state, product, before, before.name(), before.priceKrw(), before.terms(),
                            before.categoryId(), before.onSale(), before.sales(), "BRANCHES_CHANGED", requestId);
                }
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductRevision> history(long actor, long id, String scope, ListQuery query) {
        AuthorizationState state = authorizationService.current(actor);
        ProductRecord product = findVisibleProduct(state, id, scope);
        List<com.openstudy.modules.ticket.entity.ProductRevision> stored =
                productRevisionRepository.findByOriginalProductIdOrderByProductVersionAscIdAsc(id);
        List<JsonNode> snapshots = new ArrayList<>();
        Set<Long> related = new TreeSet<>(product.branchIds());

        for (com.openstudy.modules.ticket.entity.ProductRevision revision : stored) {
            JsonNode snapshot = objectMapper.readTree(revision.getSnapshotJson());
            snapshots.add(snapshot);

            if (snapshot.has("branchIds")) {
                snapshot.get("branchIds").forEach(branch -> related.add(Long.parseLong(branch.asString())));
            }
        }

        // History names every branch that ever sold the product, so all of them must be manageable by the reader.
        authorizationService.require(state, related, BranchAction.MANAGE_PRODUCT);
        String needle = query.q().toLowerCase(Locale.ROOT);
        List<ProductRevision> rows = new ArrayList<>();

        for (int index = 0; index < stored.size(); index++) {
            com.openstudy.modules.ticket.entity.ProductRevision revision = stored.get(index);
            JsonNode snapshot = snapshots.get(index);
            JsonNode before = index == 0 ? null : snapshots.get(index - 1);
            ProductRevision row = toRevision(revision, before, snapshot);

            if (matches(snapshot, needle)) {
                rows.add(row);
            }
        }

        // Versions only grow, so they order revisions even when two share a timestamp.
        return PageResponse.fromAuthorized(
                rows,
                query,
                Comparator.comparingLong(ProductRevision::version),
                Comparator.comparingLong(revision -> Long.parseLong(revision.id()))
        );
    }

    /**
     * Entry point for the purchase flow: call inside the purchase transaction. A stopped product, or one not sold at
     * the branch, cannot be bought; the shared row lock keeps a concurrent stop from interleaving with the sale.
     * The returned snapshot is the purchase-time condition record, independent of any later product edit.
     */
    @Transactional
    public ProductSnapshot requireSellable(long productId, long branchId) {
        return sellable(productId, branchId).snapshot();
    }

    /** {@link #requireSellable} plus the revision of the locked version, which the issued pass references (FR-PASS-03). */
    @Transactional
    public SaleTerms sellable(long productId, long branchId) {
        // M13-D02: a limited product is locked for update, so two sales of its last unit run one after the other; an
        // unlimited one keeps the shared lock. Every command already holds the common guard lock, so the limit cannot
        // change between this read and the lock.
        boolean limited = productRepository.findSalesLimit(productId).isPresent();

        if (limited) {
            productRepository.lock(productId);
        } else {
            productRepository.lockShared(productId);
        }

        ProductRecord product = productRepository.findWithDetailsById(productId)
                .map(ProductViews::toRecord)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // M13-D06: sale status, sale branch, sale period, then the remaining limit, each with its own code.
        if (!product.onSale()) {
            throw ApiException.conflict("PRODUCT_NOT_ON_SALE", "판매가 중지된 상품입니다.");
        }

        if (!product.branchIds().contains(branchId)) {
            throw ApiException.conflict("PRODUCT_NOT_AVAILABLE_AT_BRANCH", "이 지점에서 판매하지 않는 상품입니다.");
        }

        ProductRecord.Sales sales = product.sales();
        Instant now = clock.instant();
        boolean isBeforeSalePeriod = sales.startsAt() != null && now.isBefore(sales.startsAt());
        boolean isAfterSalePeriod = sales.endsAt() != null && !now.isBefore(sales.endsAt());

        if (isBeforeSalePeriod || isAfterSalePeriod) {
            throw OperationsConflict.of("PRODUCT_NOT_IN_SALE_PERIOD", "판매 기간이 아닌 상품입니다.",
                    "salesStartsAt", toText(sales.startsAt()), "salesEndsAt", toText(sales.endsAt()));
        }

        if (sales.limit() != null) {
            long sold = productRepository.countSold(productId);

            if (sold >= sales.limit()) {
                throw OperationsConflict.of("PRODUCT_SALE_LIMIT_REACHED", "판매 한도를 모두 판매한 상품입니다.",
                        "salesLimit", sales.limit(), "soldQuantity", sold);
            }
        }

        long revision = productRevisionRepository
                .findFirstByOriginalProductIdAndProductVersionOrderByIdDesc(productId, product.dbVersion())
                .orElseThrow(() -> new IllegalStateException("Product version has no revision"))
                .getId();

        return new SaleTerms(productId, revision, toSnapshot(product));
    }

    /**
     * Every real change advances the version by exactly one, as the former UPDATE ... version=version+1 did, also when
     * only the sale branches changed. A stale version is still a 409 (the former WHERE version=? matched 0 rows).
     */
    private Product write(
            long actor,
            AuthorizationState state,
            com.openstudy.modules.ticket.entity.Product product,
            ProductRecord before,
            String name,
            long price,
            ProductTerms terms,
            long categoryId,
            boolean onSale,
            ProductRecord.Sales sales,
            String action,
            String requestId
    ) {
        com.openstudy.modules.ticket.entity.ProductCategory category = categoryId == before.categoryId()
                ? product.getCategory()
                : productCategoryRepository.getReferenceById(categoryId);
        product.change(name, price, ProductViews.toColumns(terms), category, onSale, actor);
        product.changeSales(sales.startsAt(), sales.endsAt(), sales.limit());

        try {
            productRepository.flush();
        } catch (OptimisticLockingFailureException exception) {
            long expectedVersion = ApiVersion.fromDatabase(before.dbVersion() + 1);
            throw ApiException.versionConflict(expectedVersion, toReloadPath(before.id()));
        }

        if (product.getVersion() == before.dbVersion()) {
            productRepository.incrementVersion(before.id());
        }

        entityManager.refresh(product);
        ProductRecord after = ProductViews.toRecord(product);
        // The revision event stays UPDATED for status and branch changes too; the audit row carries the action.
        productRevisionRepository.save(new com.openstudy.modules.ticket.entity.ProductRevision(
                after.id(),
                after.dbVersion(),
                RevisionEvent.UPDATED,
                actor,
                requestId,
                toSnapshotJson(after)
        ));
        auditTrail.record(AuditEvent.change(actor, null, AUDIT_TABLE, after.id(), action, toSnapshotJson(before),
                toSnapshotJson(after), requestId));

        return toProduct(after, state, findReadableBranches(state));
    }

    /** A stored result of the same request, replayed; the same key with a different input is a conflict. */
    private Optional<Product> findStoredResult(long actor, String key, byte[] fingerprint) {
        Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, ALL_SCOPE, key);

        if (prior.isEmpty()) {
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.get().getResponseJson(), Product.class));
    }

    private com.openstudy.modules.ticket.entity.Product findLockedProduct(long id) {
        return productRepository.findWithDetailsById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 404 hides products the reader cannot see in any readable branch, so IDs are not an existence oracle. */
    private ProductRecord findVisibleProduct(AuthorizationState state, long id, String scope) {
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        ProductRecord product = productRepository.findWithDetailsById(id)
                .map(ProductViews::toRecord)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (Collections.disjoint(product.branchIds(), selected)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        return product;
    }

    private Set<Long> findReadableBranches(AuthorizationState state) {
        return authorizationService.scope(state, ALL_SCOPE, BranchAction.READ);
    }

    private Assignable findAssignableCategory(long id, String kind) {
        com.openstudy.modules.ticket.entity.ProductCategory locked = productCategoryRepository.findForUpdateById(id)
                .orElseThrow(() -> ApiException.validation("categoryId", "존재하지 않는 분류입니다."));
        ProductCategory category = ProductViews.toCategory(locked);

        if (!category.kind().equals(kind)) {
            throw ApiException.validation("categoryId", "상품 유형과 분류 유형이 일치하지 않습니다.");
        }

        if (!ACTIVE_CATEGORY.equals(category.status())) {
            throw ApiException.validation("categoryId", "비활성 분류에는 상품을 지정할 수 없습니다.");
        }

        return new Assignable(locked, category);
    }

    private Product toProduct(ProductRecord product, AuthorizationState state, Set<Long> readable) {
        return toProduct(product, state, readable, productRepository.countSold(product.id()), clock.instant());
    }

    private Product toProduct(
            ProductRecord product,
            AuthorizationState state,
            Set<Long> readable,
            long sold,
            Instant now
    ) {
        Set<Long> all = Set.copyOf(product.branchIds());
        boolean editable = all.stream()
                .allMatch(branch -> authorizationService.permits(state, branch, BranchAction.EDIT_PRODUCT));
        List<String> visible = product.branchIds()
                .stream()
                .filter(readable::contains)
                .map(String::valueOf)
                .toList();
        Integer limit = product.sales().limit();
        Long remaining = limit == null ? null : Math.max(limit - sold, 0);

        return new Product(
                Long.toString(product.id()),
                ApiVersion.fromDatabase(product.dbVersion()),
                product.createdAt().toString(),
                product.updatedAt().toString(),
                product.code(),
                product.name(),
                product.priceKrw(),
                product.terms(),
                toStatusLabel(product),
                product.category(),
                visible,
                editable,
                toText(product.sales().startsAt()),
                toText(product.sales().endsAt()),
                limit,
                sold,
                remaining,
                toAvailability(product, sold, now)
        );
    }

    private static ProductRevision toRevision(
            com.openstudy.modules.ticket.entity.ProductRevision revision,
            JsonNode before,
            JsonNode snapshot
    ) {
        boolean deleted = revision.getEventType() == RevisionEvent.DELETED;
        String changedBy = revision.getChangedBy() == null ? null : Long.toString(revision.getChangedBy());

        return new ProductRevision(
                Long.toString(revision.getId()),
                Long.toString(revision.getOriginalProductId()),
                ApiVersion.fromDatabase(revision.getProductVersion()),
                revision.getChangedAt().toString(),
                changedBy,
                revision.getEventType().name(),
                before,
                deleted ? null : snapshot
        );
    }

    /** The first reason a new sale would be refused, in the order of the sale check (M13-D06), or 판매 가능. */
    private static String toAvailability(ProductRecord product, long sold, Instant now) {
        ProductRecord.Sales sales = product.sales();

        if (!product.onSale()) {
            return STOPPED;
        }

        if (sales.startsAt() != null && now.isBefore(sales.startsAt())) {
            return NOT_YET;
        }

        if (sales.endsAt() != null && !now.isBefore(sales.endsAt())) {
            return ENDED;
        }

        if (sales.limit() != null && sold >= sales.limit()) {
            return SOLD_OUT;
        }

        return AVAILABLE;
    }

    private static boolean matches(ProductRecord product, String needle) {
        String text = product.name() + " " + product.code() + " " + product.category().name();

        return text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean matches(JsonNode snapshot, String needle) {
        String text = snapshot.path("name").asString("") + " " + snapshot.path("categoryName").asString("");

        return text.toLowerCase(Locale.ROOT).contains(needle);
    }

    private ProductSnapshot toSnapshot(ProductRecord product) {
        List<String> branchIds = product.branchIds()
                .stream()
                .map(String::valueOf)
                .toList();

        return new ProductSnapshot(
                ProductSnapshot.SCHEMA_VERSION,
                product.code(),
                product.name(),
                product.priceKrw(),
                product.terms(),
                toStatusLabel(product),
                product.category().code(),
                product.category().name(),
                branchIds,
                toText(product.sales().startsAt()),
                toText(product.sales().endsAt()),
                product.sales().limit()
        );
    }

    private String toSnapshotJson(ProductRecord product) {
        return objectMapper.writeValueAsString(toSnapshot(product));
    }

    private static String toText(Instant at) {
        return at == null ? null : at.toString();
    }

    private static String toStatusLabel(ProductRecord product) {
        return product.onSale() ? ON_SALE : STOPPED;
    }

    private static String toReloadPath(long id) {
        return "/api/products/" + id;
    }

    private static String createCode() {
        String random = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, CODE_RANDOM_LENGTH);

        return CODE_PREFIX + random;
    }

    private static void requireValidKey(String key) {
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
