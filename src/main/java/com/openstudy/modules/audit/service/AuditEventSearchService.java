package com.openstudy.modules.audit.service;

import java.time.Clock;
import java.util.List;
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

    private final AuditEventSearchRepository auditEventSearchRepository;
    private final AuthorizationService authorizationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AuditEventViews.Page search(long actor, Map<String, String[]> query) {
        requireOwner(actor);

        AuditSearchCriteria criteria = AuditSearchCriteria.parse(query, clock);
        AuditEventSearchRepository.Page page = auditEventSearchRepository.search(criteria);
        List<AuditEventViews.Item> items = page.rows()
                .stream()
                .map(AuditEventSearchService::toItem)
                .toList();
        PageResponse.Meta meta = new PageResponse.Meta(criteria.page(), criteria.pageSize(), page.total());

        return new AuditEventViews.Page(items, meta, new AuditEventViews.Period(criteria.from(), criteria.to()));
    }

    @Transactional(readOnly = true)
    public AuditEventViews.Detail get(long actor, long id) {
        requireOwner(actor);

        AuditEventSearchRepository.Row row = auditEventSearchRepository.find(id)
                .orElseThrow(() -> ApiException.notFound("감사 기록을 찾을 수 없습니다."));

        return new AuditEventViews.Detail(
                Long.toString(row.id()),
                row.occurredAt(),
                row.action(),
                row.result(),
                row.entityType(),
                toId(row.entityId()),
                row.reason(),
                row.requestId(),
                toRef(row.actorId(), row.actorName()),
                toRef(row.branchId(), row.branchName()),
                row.hasChanges(),
                toMaskedSnapshot(row.beforeJson()),
                toMaskedSnapshot(row.afterJson())
        );
    }

    private void requireOwner(long actor) {
        authorizationService.requireOwner(authorizationService.current(actor));
    }

    private JsonNode toMaskedSnapshot(String stored) {
        return stored == null ? null : AuditSnapshotMasking.mask(objectMapper.readTree(stored));
    }

    private static AuditEventViews.Item toItem(AuditEventSearchRepository.Row row) {
        return new AuditEventViews.Item(
                Long.toString(row.id()),
                row.occurredAt(),
                row.action(),
                row.result(),
                row.entityType(),
                toId(row.entityId()),
                row.reason(),
                row.requestId(),
                toRef(row.actorId(), row.actorName()),
                toRef(row.branchId(), row.branchName()),
                row.hasChanges()
        );
    }

    /** A removed actor or branch keeps its ID (ON DELETE SET NULL clears it); an unknown name shows as empty. */
    private static AuditEventViews.Ref toRef(Long id, String name) {
        return id == null ? null : new AuditEventViews.Ref(Long.toString(id), name == null ? "" : name);
    }

    private static String toId(Long value) {
        return value == null ? null : Long.toString(value);
    }
}
