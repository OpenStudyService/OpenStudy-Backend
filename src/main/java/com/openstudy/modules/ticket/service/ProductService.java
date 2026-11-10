package com.openstudy.modules.ticket.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import jakarta.persistence.EntityManager;
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
public class ProductService {
    private static final String ON_SALE = "판매 중";
    private static final String STOPPED = "판매 중지";
    private static final String CREATE = "products:create";
    /** saleAvailability labels (M13-D06), in the order a sale is judged. */
    static final String AVAILABLE = "판매 가능", NOT_YET = "판매 전", ENDED = "판매 종료", SOLD_OUT = "한도 소진";
    private static final Set<String> AVAILABILITIES = Set.of(AVAILABLE, STOPPED, NOT_YET, ENDED, SOLD_OUT);
    private static final long LIMIT_MAX = 1_000_000;
    private final ProductRepository repository;
    private final ProductCategoryRepository categories;
    private final ProductRevisionRepository revisions;
    private final EntityManager entityManager;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final IdempotencyStore requests;
    private final Clock clock;

    public ProductService(ProductRepository repository, ProductCategoryRepository categories,
            ProductRevisionRepository revisions, EntityManager entityManager, AuthorizationService policy,
            AuthorizedCommands commands, ObjectMapper json, AuditTrail audit,
            IdempotencyStore requests, Clock clock) {
        this.repository = repository;
        this.categories = categories;
        this.revisions = revisions;
        this.entityManager = entityManager;
        this.policy = policy;
        this.commands = commands;
        this.json = json;
        this.audit = audit;
        this.requests = requests;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<Product> list(long actor, String scope, ListQuery query, String status, String kind, String categoryId,
            String availability) {
        if (status != null && !Set.of(ON_SALE, STOPPED).contains(status)) throw ApiException.validation("status", "올바른 판매 상태를 선택해 주세요.");
        if (availability != null && !AVAILABILITIES.contains(availability))
            throw ApiException.validation("availability", "올바른 판매 가능 여부를 선택해 주세요.");
        if (kind != null && !Set.of(ProductTerms.TIME, ProductTerms.PERIOD, ProductTerms.STORED_VALUE).contains(kind))
            throw ApiException.validation("kind", "올바른 상품 유형을 선택해 주세요.");
        Long category = categoryId == null ? null : ProductInputs.id(categoryId, "categoryId");
        var state = policy.current(actor);
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        Set<Long> visible = policy.scope(state, "all", BranchAction.READ);
        String needle = query.q().toLowerCase(Locale.ROOT);
        var matching = repository.findAllWithDetailsByOrderByIdAsc().stream().map(ProductViews::record)
                .filter(product -> !Collections.disjoint(product.branchIds(), selected))
                .filter(product -> status == null || status.equals(statusName(product)))
                .filter(product -> kind == null || kind.equals(product.terms().kind()))
                .filter(product -> category == null || category == product.categoryId())
                .filter(product -> (product.name() + " " + product.code() + " " + product.category().name())
                        .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        var sold = new HashMap<Long, Long>();
        if (!matching.isEmpty())
            repository.soldQuantities(matching.stream().map(ProductRecord::id).toList())
                    .forEach(row -> sold.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        Instant now = clock.instant();
        var rows = matching.stream().map(product -> view(product, state, visible, sold.getOrDefault(product.id(), 0L), now))
                .filter(product -> availability == null || availability.equals(product.saleAvailability())).toList();
        Comparator<Product> field = switch (query.sort()) {
            case "name" -> Comparator.comparing(Product::name);
            case "priceKrw" -> Comparator.comparingLong(Product::priceKrw);
            default -> Comparator.comparing(Product::createdAt);
        };
        return PageResponse.fromAuthorized(rows, query, field, Comparator.comparingLong(product -> Long.parseLong(product.id())));
    }

    @Transactional(readOnly = true)
    public Product get(long actor, long id, String scope) {
        var state = policy.current(actor);
        return view(visibleProduct(state, id, scope), state, policy.scope(state, "all", BranchAction.READ));
    }

    private Product view(ProductRecord product, AuthorizationState state, Set<Long> readable) {
        return view(product, state, readable, repository.soldQuantity(product.id()), clock.instant());
    }

    public Product create(long actor, String scope, String key, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("name", "priceKrw", "terms", "status", "categoryId", "branchIds", "salesStartsAt",
                "salesEndsAt", "salesLimit");
        String name = input.text("name", 120);
        long price = ProductInputs.price(body.get("priceKrw"));
        ProductTerms terms = ProductInputs.terms(body.get("terms"));
        boolean onSale = ON_SALE.equals(input.choice("status", ON_SALE, STOPPED));
        long categoryId = input.id("categoryId");
        Set<Long> branches = ProductInputs.branchIds(body.get("branchIds"));
        var sales = SalesInput.parse(body).over(ProductRecord.Sales.NONE);
        validKey(key);
        // scope=all never means "sell everywhere"; it is only checked for syntax and for a readable branch.
        policy.scope(actor, scope, BranchAction.READ);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(name, price, terms, onSale, categoryId, branches,
                String.valueOf(sales.startsAt()), String.valueOf(sales.endsAt()), sales.limit())));
        return commands.inBranches(actor, branches, BranchAction.EDIT_PRODUCT, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, "all", key);
            if (prior.isPresent()) {
                if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                    throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.get().getResponseJson(), Product.class);
            }
            var category = assignableCategory(categoryId, terms.kind());
            String code = "PD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 29);
            var entity = new com.openstudy.modules.ticket.entity.Product(code, name,
                    ProductViews.kind(terms.kind()), price, ProductViews.columns(terms), onSale, category.entity(), branches, actor);
            entity.changeSales(sales.startsAt(), sales.endsAt(), sales.limit());
            var product = repository.saveAndFlush(entity);
            var created = ProductViews.record(product);
            long id = created.id();
            revisions.save(new com.openstudy.modules.ticket.entity.ProductRevision(id, created.dbVersion(), RevisionEvent.CREATED,
                    actor, requestId, snapshotJson(created)));
            audit.record(AuditEvent.change(actor, null, "products", id, "CREATED", null, snapshotJson(created), requestId));
            var result = view(created, state, policy.scope(state, "all", BranchAction.READ));
            requests.complete(actor, CREATE, "all", key, fingerprint, 201, json.writeValueAsString(result),
                    clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }

    public Product update(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "name", "priceKrw", "terms", "categoryId", "salesStartsAt", "salesEndsAt",
                "salesLimit");
        if (body.size() < 2) throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        long version = input.version("version");
        String name = input.has("name") ? input.text("name", 120) : null;
        Long price = input.has("priceKrw") ? ProductInputs.price(body.get("priceKrw")) : null;
        ProductTerms terms = input.has("terms") ? ProductInputs.terms(body.get("terms")) : null;
        Long categoryId = input.has("categoryId") ? input.id("categoryId") : null;
        var salesInput = SalesInput.parse(body);
        var found = visibleProduct(policy.current(actor), id, scope);
        return commands.inBranches(actor, Set.copyOf(found.branchIds()), BranchAction.EDIT_PRODUCT, () -> repository.lock(id), state -> {
            var product = lockedProduct(id);
            var before = ProductViews.record(product);
            Set<Long> persisted = Set.copyOf(before.branchIds());
            policy.requireSharedProduct(state, persisted, persisted, false);
            ApiVersion.requireCurrent(version, before.dbVersion(), reload(id));
            if (terms != null && !terms.kind().equals(before.terms().kind()))
                throw ApiException.validation("terms.kind", "상품 유형은 변경할 수 없습니다. 새 상품으로 등록해 주세요.");
            var category = categoryId == null || categoryId == before.categoryId() ? before.category()
                    : assignableCategory(categoryId, before.terms().kind()).view();
            String newName = name == null ? before.name() : name;
            long newPrice = price == null ? before.priceKrw() : price;
            ProductTerms newTerms = terms == null ? before.terms() : terms;
            var newSales = salesInput.over(before.sales());
            // An identical request is not a change: no version bump, revision, audit row or event.
            if (newName.equals(before.name()) && newPrice == before.priceKrw() && newTerms.equals(before.terms())
                    && category.id().equals(before.category().id()) && newSales.equals(before.sales())) {
                return view(before, state, policy.scope(state, "all", BranchAction.READ));
            }
            return write(actor, state, product, before, newName, newPrice, newTerms, Long.parseLong(category.id()), before.onSale(),
                    newSales, "UPDATED", requestId);
        });
    }

    public Product changeStatus(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        boolean onSale = ON_SALE.equals(input.choice("status", ON_SALE, STOPPED));
        var found = visibleProduct(policy.current(actor), id, scope);
        return commands.inBranches(actor, Set.copyOf(found.branchIds()), BranchAction.MANAGE_PRODUCT, () -> repository.lock(id), state -> {
            var product = lockedProduct(id);
            var before = ProductViews.record(product);
            Set<Long> persisted = Set.copyOf(before.branchIds());
            policy.requireSharedProduct(state, persisted, persisted, true);
            ApiVersion.requireCurrent(version, before.dbVersion(), reload(id));
            if (before.onSale() == onSale) return view(before, state, policy.scope(state, "all", BranchAction.READ));
            return write(actor, state, product, before, before.name(), before.priceKrw(), before.terms(), before.categoryId(), onSale,
                    before.sales(), "STATUS_CHANGED", requestId);
        });
    }

    public Product replaceBranches(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "branchIds");
        long version = input.version("version");
        Set<Long> requested = ProductInputs.branchIds(body.get("branchIds"));
        var found = visibleProduct(policy.current(actor), id, scope);
        Set<Long> union = new TreeSet<>(found.branchIds());
        union.addAll(requested);
        return commands.inBranches(actor, union, BranchAction.MANAGE_PRODUCT, () -> repository.lock(id), state -> {
            var product = lockedProduct(id);
            var before = ProductViews.record(product);
            Set<Long> persisted = Set.copyOf(before.branchIds());
            // Both the stored and the new scope must be manageable, so a hidden branch cannot be dropped or added.
            policy.requireSharedProduct(state, persisted, requested, true);
            ApiVersion.requireCurrent(version, before.dbVersion(), reload(id));
            if (persisted.equals(requested)) return view(before, state, policy.scope(state, "all", BranchAction.READ));
            Set<Long> added = new TreeSet<>(requested), removed = new TreeSet<>(persisted);
            added.removeAll(persisted);
            removed.removeAll(requested);
            product.replaceBranches(removed, added);
            return write(actor, state, product, before, before.name(), before.priceKrw(), before.terms(), before.categoryId(),
                    before.onSale(), before.sales(), "BRANCHES_CHANGED", requestId);
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductRevision> history(long actor, long id, String scope, ListQuery query) {
        var state = policy.current(actor);
        var product = visibleProduct(state, id, scope);
        var stored = revisions.findByOriginalProductIdOrderByProductVersionAscIdAsc(id);
        List<JsonNode> snapshots = new ArrayList<>();
        Set<Long> related = new TreeSet<>(product.branchIds());
        for (var revision : stored) {
            JsonNode snapshot = json.readTree(revision.getSnapshotJson());
            snapshots.add(snapshot);
            if (snapshot.has("branchIds"))
                snapshot.get("branchIds").forEach(branch -> related.add(Long.parseLong(branch.asString())));
        }
        // History names every branch that ever sold the product, so all of them must be manageable by the reader.
        policy.require(state, related, BranchAction.MANAGE_PRODUCT);
        String needle = query.q().toLowerCase(Locale.ROOT);
        List<ProductRevision> rows = new ArrayList<>();
        for (int index = 0; index < stored.size(); index++) {
            var revision = stored.get(index);
            JsonNode snapshot = snapshots.get(index);
            boolean deleted = revision.getEventType() == RevisionEvent.DELETED;
            JsonNode before = index == 0 ? null : snapshots.get(index - 1);
            var row = new ProductRevision(Long.toString(revision.getId()), Long.toString(revision.getOriginalProductId()),
                    ApiVersion.fromDatabase(revision.getProductVersion()), revision.getChangedAt().toString(),
                    revision.getChangedBy() == null ? null : Long.toString(revision.getChangedBy()), revision.getEventType().name(),
                    before, deleted ? null : snapshot);
            if ((snapshot.path("name").asString("") + " " + snapshot.path("categoryName").asString("")).toLowerCase(Locale.ROOT).contains(needle))
                rows.add(row);
        }
        // Versions only grow, so they order revisions even when two share a timestamp.
        return PageResponse.fromAuthorized(rows, query, Comparator.comparingLong(ProductRevision::version),
                Comparator.comparingLong(revision -> Long.parseLong(revision.id())));
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
        boolean limited = repository.salesLimitOf(productId).isPresent();
        if (limited) repository.lock(productId); else repository.lockShared(productId);
        var product = repository.findWithDetailsById(productId).map(ProductViews::record)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // M13-D06: sale status, sale branch, sale period, then the remaining limit, each with its own code.
        if (!product.onSale()) throw ApiException.conflict("PRODUCT_NOT_ON_SALE", "판매가 중지된 상품입니다.");
        if (!product.branchIds().contains(branchId))
            throw ApiException.conflict("PRODUCT_NOT_AVAILABLE_AT_BRANCH", "이 지점에서 판매하지 않는 상품입니다.");
        var sales = product.sales();
        Instant now = clock.instant();
        if ((sales.startsAt() != null && now.isBefore(sales.startsAt())) || (sales.endsAt() != null && !now.isBefore(sales.endsAt())))
            throw OperationsConflict.of("PRODUCT_NOT_IN_SALE_PERIOD", "판매 기간이 아닌 상품입니다.", "salesStartsAt",
                    text(sales.startsAt()), "salesEndsAt", text(sales.endsAt()));
        if (sales.limit() != null) {
            long sold = repository.soldQuantity(productId);
            if (sold >= sales.limit())
                throw OperationsConflict.of("PRODUCT_SALE_LIMIT_REACHED", "판매 한도를 모두 판매한 상품입니다.", "salesLimit", sales.limit(),
                        "soldQuantity", sold);
        }
        long revision = revisions.findFirstByOriginalProductIdAndProductVersionOrderByIdDesc(productId, product.dbVersion())
                .orElseThrow(() -> new IllegalStateException("Product version has no revision")).getId();
        return new SaleTerms(productId, revision, snapshot(product));
    }

    /**
     * Every real change advances the version by exactly one, as the former UPDATE ... version=version+1 did, also when
     * only the sale branches changed. A stale version is still a 409 (the former WHERE version=? matched 0 rows).
     */
    private Product write(long actor, AuthorizationState state, com.openstudy.modules.ticket.entity.Product product,
            ProductRecord before, String name, long price, ProductTerms terms, long categoryId, boolean onSale,
            ProductRecord.Sales sales, String action, String requestId) {
        var category = categoryId == before.categoryId() ? product.getCategory() : categories.getReferenceById(categoryId);
        product.change(name, price, ProductViews.columns(terms), category, onSale, actor);
        product.changeSales(sales.startsAt(), sales.endsAt(), sales.limit());
        try {
            repository.flush();
        } catch (OptimisticLockingFailureException exception) {
            throw ApiException.versionConflict(ApiVersion.fromDatabase(before.dbVersion() + 1), reload(before.id()));
        }
        if (product.getVersion() == before.dbVersion()) repository.incrementVersion(before.id());
        entityManager.refresh(product);
        var after = ProductViews.record(product);
        // The revision event stays UPDATED for status and branch changes too; the audit row carries the action.
        revisions.save(new com.openstudy.modules.ticket.entity.ProductRevision(after.id(), after.dbVersion(),
                RevisionEvent.UPDATED, actor, requestId, snapshotJson(after)));
        audit.record(AuditEvent.change(actor, null, "products", after.id(), action, snapshotJson(before), snapshotJson(after), requestId));
        return view(after, state, policy.scope(state, "all", BranchAction.READ));
    }

    private com.openstudy.modules.ticket.entity.Product lockedProduct(long id) {
        return repository.findWithDetailsById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 404 hides products the reader cannot see in any readable branch, so IDs are not an existence oracle. */
    private ProductRecord visibleProduct(AuthorizationState state, long id, String scope) {
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        var product = repository.findWithDetailsById(id).map(ProductViews::record)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (Collections.disjoint(product.branchIds(), selected)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return product;
    }

    private record Assignable(com.openstudy.modules.ticket.entity.ProductCategory entity, ProductCategory view) {}

    private Assignable assignableCategory(long id, String kind) {
        var locked = categories.findForUpdateById(id)
                .orElseThrow(() -> ApiException.validation("categoryId", "존재하지 않는 분류입니다."));
        var category = ProductViews.category(locked);
        if (!category.kind().equals(kind)) throw ApiException.validation("categoryId", "상품 유형과 분류 유형이 일치하지 않습니다.");
        if (!"활성".equals(category.status())) throw ApiException.validation("categoryId", "비활성 분류에는 상품을 지정할 수 없습니다.");
        return new Assignable(locked, category);
    }

    private Product view(ProductRecord product, AuthorizationState state, Set<Long> readable, long sold, Instant now) {
        Set<Long> all = Set.copyOf(product.branchIds());
        boolean editable = all.stream().allMatch(branch -> policy.permits(state, branch, BranchAction.EDIT_PRODUCT));
        List<String> visible = product.branchIds().stream().filter(readable::contains).map(String::valueOf).toList();
        return new Product(Long.toString(product.id()), ApiVersion.fromDatabase(product.dbVersion()),
                product.createdAt().toString(), product.updatedAt().toString(), product.code(), product.name(),
                product.priceKrw(), product.terms(), statusName(product), product.category(), visible, editable,
                text(product.sales().startsAt()), text(product.sales().endsAt()), product.sales().limit(), sold,
                product.sales().limit() == null ? null : Math.max(product.sales().limit() - sold, 0), availability(product, sold, now));
    }

    /** The first reason a new sale would be refused, in the order of the sale check (M13-D06), or 판매 가능. */
    static String availability(ProductRecord product, long sold, Instant now) {
        var sales = product.sales();
        if (!product.onSale()) return STOPPED;
        if (sales.startsAt() != null && now.isBefore(sales.startsAt())) return NOT_YET;
        if (sales.endsAt() != null && !now.isBefore(sales.endsAt())) return ENDED;
        if (sales.limit() != null && sold >= sales.limit()) return SOLD_OUT;
        return AVAILABLE;
    }

    private static String text(Instant at) { return at == null ? null : at.toString(); }

    /**
     * The sale fields of a create or PATCH body: an absent field keeps the current value, null clears it (M13-D06).
     * Times carry an offset and whole seconds; the period must end after it starts.
     */
    record SalesInput(boolean startsGiven, Instant startsAt, boolean endsGiven, Instant endsAt, boolean limitGiven, Integer limit) {
        static SalesInput parse(Map<String, Object> body) {
            return new SalesInput(body.containsKey("salesStartsAt"), time(body, "salesStartsAt"), body.containsKey("salesEndsAt"),
                    time(body, "salesEndsAt"), body.containsKey("salesLimit"),
                    body.get("salesLimit") == null ? null : (int) ProductInputs.integer(body.get("salesLimit"), "salesLimit", 1, LIMIT_MAX));
        }

        ProductRecord.Sales over(ProductRecord.Sales current) {
            var merged = new ProductRecord.Sales(startsGiven ? startsAt : current.startsAt(), endsGiven ? endsAt : current.endsAt(),
                    limitGiven ? limit : current.limit());
            if (merged.startsAt() != null && merged.endsAt() != null && !merged.startsAt().isBefore(merged.endsAt()))
                throw ApiException.validation("salesEndsAt", "판매 종료는 판매 시작보다 뒤여야 합니다.");
            return merged;
        }

        private static Instant time(Map<String, Object> body, String field) {
            Object value = body.get(field);
            if (value == null) return null;
            if (!(value instanceof String text)) throw ApiException.validation(field, "ISO 8601 시각을 입력해 주세요.");
            Instant parsed = TimeRange.instant(text, field);
            if (parsed.getNano() != 0) throw ApiException.validation(field, "초 단위까지만 입력해 주세요.");
            return parsed;
        }
    }

    private ProductSnapshot snapshot(ProductRecord product) {
        return new ProductSnapshot(ProductSnapshot.SCHEMA_VERSION, product.code(), product.name(), product.priceKrw(),
                product.terms(), statusName(product), product.category().code(), product.category().name(),
                product.branchIds().stream().map(String::valueOf).toList(), text(product.sales().startsAt()),
                text(product.sales().endsAt()), product.sales().limit());
    }

    private String snapshotJson(ProductRecord product) { return json.writeValueAsString(snapshot(product)); }

    private static String statusName(ProductRecord product) { return product.onSale() ? ON_SALE : STOPPED; }
    private static String reload(long id) { return "/api/products/" + id; }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
