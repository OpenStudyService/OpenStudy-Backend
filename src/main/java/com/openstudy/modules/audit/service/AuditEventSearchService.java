package com.openstudy.modules.audit.service;

import java.time.Clock;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.audit.dto.request.AuditSearchCriteria;
import com.openstudy.modules.audit.dto.response.AuditEventViews;
import com.openstudy.modules.audit.repository.AuditEventSearchRepository;
import com.openstudy.modules.authorization.service.AuthorizationService;

/**
 * Audit search and detail for OWNER only (#122, M14-D04). The OWNER check comes before any query validation, so other
 * employees learn nothing from 422s. Snapshots are masked again when read; search results never carry them.
 */
@Service
@RequiredArgsConstructor
public class AuditEventSearchService {
    private final AuditEventSearchRepository repository;
    private final AuthorizationService policy;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AuditEventViews.Page search(long actor, Map<String, String[]> query) {
        requireOwner(actor);
        var criteria = AuditSearchCriteria.parse(query, clock);
        var page = repository.search(criteria);
        var items = page.rows().stream().map(AuditEventSearchService::item).toList();
        return new AuditEventViews.Page(items, new PageResponse.Meta(criteria.page(), criteria.pageSize(), page.total()),
                new AuditEventViews.Period(criteria.from(), criteria.to()));
    }

    @Transactional(readOnly = true)
    public AuditEventViews.Detail get(long actor, long id) {
        requireOwner(actor);
        var row = repository.find(id).orElseThrow(() -> ApiException.notFound("감사 기록을 찾을 수 없습니다."));
        return new AuditEventViews.Detail(Long.toString(row.id()), row.occurredAt(), row.action(), row.result(), row.entityType(),
                id(row.entityId()), row.reason(), row.requestId(), ref(row.actorId(), row.actorName()),
                ref(row.branchId(), row.branchName()), row.hasChanges(), snapshot(row.beforeJson()), snapshot(row.afterJson()));
    }

    private void requireOwner(long actor) {
        policy.requireOwner(policy.current(actor));
    }

    private JsonNode snapshot(String stored) {
        return stored == null ? null : AuditSnapshotMasking.mask(json.readTree(stored));
    }

    private static AuditEventViews.Item item(AuditEventSearchRepository.Row row) {
        return new AuditEventViews.Item(Long.toString(row.id()), row.occurredAt(), row.action(), row.result(), row.entityType(),
                id(row.entityId()), row.reason(), row.requestId(), ref(row.actorId(), row.actorName()),
                ref(row.branchId(), row.branchName()), row.hasChanges());
    }

    /** A removed actor or branch keeps its ID (ON DELETE SET NULL clears it); an unknown name shows as empty. */
    private static AuditEventViews.Ref ref(Long id, String name) {
        return id == null ? null : new AuditEventViews.Ref(Long.toString(id), name == null ? "" : name);
    }

    private static String id(Long value) { return value == null ? null : Long.toString(value); }
}
