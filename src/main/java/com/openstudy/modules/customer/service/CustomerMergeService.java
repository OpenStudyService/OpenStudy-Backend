package com.openstudy.modules.customer.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.exception.OperationsConflict;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.customer.dto.response.CustomerDetail;
import com.openstudy.modules.customer.dto.response.CustomerDuplicateGroup;
import com.openstudy.modules.customer.dto.response.CustomerMergeRecord;
import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.customer.entity.Customer;
import com.openstudy.modules.customer.entity.CustomerMerge;
import com.openstudy.modules.customer.entity.CustomerStatus;
import com.openstudy.modules.customer.repository.CustomerMergeRepository;
import com.openstudy.modules.customer.repository.CustomerRepository;

/**
 * Duplicate customers and their merge (operations-extension-api.md "중복 고객 확인·병합", M13-D03·D07·D13). Only the OWNER
 * looks for duplicates and merges, and a merge is never undone. The absorbed customer stays as a 병합됨 record that points at
 * its target; payments, passes, stays and reservations keep their customer, and the target's reads include them. A merge
 * runs in the OWNER lock order (guard first), so two merges never interleave.
 */
@Service
@RequiredArgsConstructor
public class CustomerMergeService {
    private final CustomerRepository repository;
    private final CustomerMergeRepository merges;
    private final CustomerService customers;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final AuditTrail audit;
    private final ObjectMapper json;
    private final Clock clock;

    /** Groups of customers sharing a phone, most customers first, then by phone (M13-D13). */
    @Transactional(readOnly = true)
    public PageResponse<CustomerDuplicateGroup> duplicates(long actor, ListQuery query) {
        policy.requireOwner(policy.current(actor));
        var phones = new ArrayList<String>();
        repository.duplicatePhones(query.pageSize(), query.offset()).forEach(row -> phones.add((String) row[0]));
        long total = repository.countDuplicatePhones();
        if (phones.isEmpty()) return new PageResponse<>(List.of(), new PageResponse.Meta(query.page(), query.pageSize(), total));
        var rows = repository.notMergedWithPhones(phones);
        var ids = rows.stream().map(Customer::getId).toList();
        var payments = new HashMap<Long, Long>();
        repository.paymentCounts(ids).forEach(row -> payments.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        var activity = new HashMap<Long, Instant>();
        repository.lastActivities(ids).forEach(row -> activity.put(((Number) row[0]).longValue(), instant(row[1])));
        var byPhone = new LinkedHashMap<String, List<CustomerDuplicateGroup.Member>>();
        phones.forEach(phone -> byPhone.put(phone, new ArrayList<>()));
        for (var customer : rows) {
            byPhone.get(customer.getPhone()).add(new CustomerDuplicateGroup.Member(customer.getId().toString(), customer.getMemberNo(),
                    customer.getName(), CustomerInputs.label(customer.getStatus()),
                    customer.getRegisteredBranchId() == null ? null : customer.getRegisteredBranchId().toString(),
                    customer.getRegisteredAt(), payments.getOrDefault(customer.getId(), 0L), activity.get(customer.getId())));
        }
        var groups = byPhone.entrySet().stream().map(entry -> new CustomerDuplicateGroup(entry.getKey(), entry.getValue())).toList();
        return new PageResponse<>(groups, new PageResponse.Meta(query.page(), query.pageSize(), total));
    }

    /**
     * Absorbs {@code absorbedCustomerId} into the customer of the path (M13-D03). Checks in the contract's order: the
     * same customer twice (422), a missing customer (404), an already merged one (409), then both versions (409).
     */
    public CustomerDetail merge(long actor, long target, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "absorbedCustomerId", "absorbedVersion", "reason");
        long version = input.version("version");
        long absorbedId = input.id("absorbedCustomerId");
        long absorbedVersion = input.version("absorbedVersion");
        String reason = input.text("reason", 500);
        return commands.ownerCommand(actor, () -> {
            if (absorbedId == target) throw ApiException.unprocessable("CUSTOMER_MERGE_SELF", "같은 고객끼리는 병합할 수 없습니다.");
            repository.lock(Math.min(target, absorbedId));
            repository.lock(Math.max(target, absorbedId));
            var kept = repository.findById(target).orElseThrow(CustomerMergeService::notFound);
            var absorbed = repository.findById(absorbedId).orElseThrow(CustomerMergeService::notFound);
            for (var customer : List.of(kept, absorbed))
                if (customer.getStatus() == CustomerStatus.MERGED)
                    throw OperationsConflict.of("CUSTOMER_ALREADY_MERGED", "이미 병합된 고객입니다.", "mergedIntoCustomerId",
                            customer.getMergedIntoCustomerId().toString());
            ApiVersion.requireCurrent(version, kept.getVersion(), "/api/customers/" + target);
            ApiVersion.requireCurrent(absorbedVersion, absorbed.getVersion(), "/api/customers/" + absorbedId);
            // Customers merged into the absorbed one move to the new target, so a target is never itself merged.
            var moved = repository.findByMergedIntoCustomerIdOrderByIdAsc(absorbedId);
            moved.forEach(customer -> repository.lock(customer.getId()));
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            absorbed.mergeInto(target);
            merges.save(new CustomerMerge(target, absorbedId, null, actor, now, reason));
            for (var customer : moved) {
                customer.mergeInto(target);
                merges.save(new CustomerMerge(target, customer.getId(), absorbedId, actor, now, reason));
            }
            repository.flush();
            repository.incrementVersion(target);
            var after = new LinkedHashMap<String, Object>();
            after.put("absorbedCustomerId", Long.toString(absorbedId));
            after.put("movedCustomerIds", moved.stream().map(customer -> customer.getId().toString()).toList());
            audit.record(AuditEvent.change(actor, kept.getRegisteredBranchId(), "customers", target, "MERGED", reason, null,
                    json.writeValueAsString(after), requestId));
            return customers.get(actor, target, "all");
        });
    }

    /** Merges the customer took part in, newest first; anyone who sees the customer may read them. */
    @Transactional(readOnly = true)
    public List<CustomerMergeRecord> history(long actor, long id, String scope) {
        customers.requireVisible(policy.current(actor), id, scope);
        var rows = merges.involving(id);
        var related = new TreeSet<Long>();
        var actors = new TreeSet<Long>();
        for (var row : rows) {
            related.add(row.getTargetCustomerId());
            related.add(row.getAbsorbedCustomerId());
            if (row.getViaCustomerId() != null) related.add(row.getViaCustomerId());
            actors.add(row.getMergedBy());
        }
        Map<Long, CustomerSummary> summaries = related.isEmpty() ? Map.of() : customers.summaries(related);
        var names = new HashMap<Long, String>();
        if (!actors.isEmpty()) merges.employeeNames(actors).forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));
        return rows.stream().map(row -> new CustomerMergeRecord(row.getId().toString(), summaries.get(row.getTargetCustomerId()),
                summaries.get(row.getAbsorbedCustomerId()), row.getViaCustomerId() == null ? null : summaries.get(row.getViaCustomerId()),
                new CustomerMergeRecord.ActorSummary(Long.toString(row.getMergedBy()), names.get(row.getMergedBy())), row.getMergedAt(),
                row.getReason())).toList();
    }

    /** DATETIME read natively: the session time zone is UTC (connectionTimeZone=UTC). */
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Instant instant) return instant;
        throw new IllegalStateException("Unexpected DATETIME type " + value.getClass());
    }

    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
}
