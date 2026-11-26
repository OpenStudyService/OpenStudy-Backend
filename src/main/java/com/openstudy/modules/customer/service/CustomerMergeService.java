package com.openstudy.modules.customer.service;

import java.sql.Timestamp;
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

    private static final String AUDIT_ENTITY_TYPE = "customers";
    private static final int MAX_REASON_LENGTH = 500;

    private final CustomerRepository customerRepository;
    private final CustomerMergeRepository customerMergeRepository;
    private final CustomerService customerService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Groups of customers sharing a phone, most customers first, then by phone (M13-D13). */
    @Transactional(readOnly = true)
    public PageResponse<CustomerDuplicateGroup> findDuplicates(long actor, ListQuery query) {
        authorizationService.requireOwner(authorizationService.current(actor));

        List<String> phones = new ArrayList<>();
        customerRepository.findDuplicatePhones(query.pageSize(), query.offset())
                .forEach(row -> phones.add((String) row[0]));
        long total = customerRepository.countDuplicatePhones();
        PageResponse.Meta meta = new PageResponse.Meta(query.page(), query.pageSize(), total);

        if (phones.isEmpty()) {
            return new PageResponse<>(List.of(), meta);
        }

        List<Customer> rows = customerRepository.findNotMergedByPhones(phones);
        List<Long> ids = rows.stream()
                .map(Customer::getId)
                .toList();

        Map<Long, Long> paymentCounts = new HashMap<>();
        customerRepository.countPaymentsByCustomer(ids)
                .forEach(row -> paymentCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));

        Map<Long, Instant> lastActivities = new HashMap<>();
        customerRepository.findLastActivities(ids)
                .forEach(row -> lastActivities.put(((Number) row[0]).longValue(), toInstant(row[1])));

        Map<String, List<CustomerDuplicateGroup.Member>> byPhone = new LinkedHashMap<>();
        phones.forEach(phone -> byPhone.put(phone, new ArrayList<>()));

        for (Customer customer : rows) {
            byPhone.get(customer.getPhone()).add(toMember(customer, paymentCounts, lastActivities));
        }

        List<CustomerDuplicateGroup> groups = byPhone.entrySet()
                .stream()
                .map(entry -> new CustomerDuplicateGroup(entry.getKey(), entry.getValue()))
                .toList();

        return new PageResponse<>(groups, meta);
    }

    /**
     * Absorbs {@code absorbedCustomerId} into the customer of the path (M13-D03). Checks in the contract's order: the
     * same customer twice (422), a missing customer (404), an already merged one (409), then both versions (409).
     */
    public CustomerDetail merge(long actor, long target, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "absorbedCustomerId", "absorbedVersion", "reason");
        long version = input.version("version");
        long absorbedId = input.id("absorbedCustomerId");
        long absorbedVersion = input.version("absorbedVersion");
        String reason = input.text("reason", MAX_REASON_LENGTH);

        return authorizedCommands.ownerCommand(actor, () -> {
            if (absorbedId == target) {
                throw ApiException.unprocessable("CUSTOMER_MERGE_SELF", "같은 고객끼리는 병합할 수 없습니다.");
            }

            customerRepository.lock(Math.min(target, absorbedId));
            customerRepository.lock(Math.max(target, absorbedId));
            Customer kept = customerRepository.findById(target).orElseThrow(CustomerMergeService::notFound);
            Customer absorbed = customerRepository.findById(absorbedId).orElseThrow(CustomerMergeService::notFound);
            requireNotMerged(kept);
            requireNotMerged(absorbed);
            ApiVersion.requireCurrent(version, kept.getVersion(), "/api/customers/" + target);
            ApiVersion.requireCurrent(absorbedVersion, absorbed.getVersion(), "/api/customers/" + absorbedId);

            // Customers merged into the absorbed one move to the new target, so a target is never itself merged.
            List<Customer> moved = customerRepository.findByMergedIntoCustomerIdOrderByIdAsc(absorbedId);
            moved.forEach(customer -> customerRepository.lock(customer.getId()));

            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            absorbed.mergeInto(target);
            customerMergeRepository.save(new CustomerMerge(target, absorbedId, null, actor, now, reason));

            for (Customer customer : moved) {
                customer.mergeInto(target);
                CustomerMerge movedMerge = new CustomerMerge(target, customer.getId(), absorbedId, actor, now, reason);
                customerMergeRepository.save(movedMerge);
            }

            customerRepository.flush();
            customerRepository.incrementVersion(target);
            recordMerge(actor, kept, target, absorbedId, moved, reason, requestId);

            return customerService.get(actor, target, "all");
        });
    }

    /** Merges the customer took part in, newest first; anyone who sees the customer may read them. */
    @Transactional(readOnly = true)
    public List<CustomerMergeRecord> findHistory(long actor, long id, String scope) {
        customerService.requireVisible(authorizationService.current(actor), id, scope);

        List<CustomerMerge> rows = customerMergeRepository.findInvolving(id);
        TreeSet<Long> related = new TreeSet<>();
        TreeSet<Long> actors = new TreeSet<>();

        for (CustomerMerge row : rows) {
            related.add(row.getTargetCustomerId());
            related.add(row.getAbsorbedCustomerId());

            if (row.getViaCustomerId() != null) {
                related.add(row.getViaCustomerId());
            }

            actors.add(row.getMergedBy());
        }

        Map<Long, CustomerSummary> summaries = related.isEmpty() ? Map.of() : customerService.summaries(related);
        Map<Long, String> names = new HashMap<>();

        if (!actors.isEmpty()) {
            customerMergeRepository.findEmployeeNames(actors)
                    .forEach(row -> names.put(((Number) row[0]).longValue(), (String) row[1]));
        }

        return rows.stream()
                .map(row -> toRecord(row, summaries, names))
                .toList();
    }

    private void recordMerge(
            long actor,
            Customer kept,
            long target,
            long absorbedId,
            List<Customer> moved,
            String reason,
            String requestId
    ) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("absorbedCustomerId", Long.toString(absorbedId));
        after.put("movedCustomerIds", moved.stream().map(customer -> customer.getId().toString()).toList());

        AuditEvent event = AuditEvent.change(
                actor,
                kept.getRegisteredBranchId(),
                AUDIT_ENTITY_TYPE,
                target,
                "MERGED",
                reason,
                null,
                objectMapper.writeValueAsString(after),
                requestId
        );

        auditTrail.record(event);
    }

    private static void requireNotMerged(Customer customer) {
        if (customer.getStatus() == CustomerStatus.MERGED) {
            throw OperationsConflict.of(
                    "CUSTOMER_ALREADY_MERGED",
                    "이미 병합된 고객입니다.",
                    "mergedIntoCustomerId",
                    customer.getMergedIntoCustomerId().toString()
            );
        }
    }

    private static CustomerDuplicateGroup.Member toMember(
            Customer customer,
            Map<Long, Long> paymentCounts,
            Map<Long, Instant> lastActivities
    ) {
        Long registeredBranchId = customer.getRegisteredBranchId();

        return new CustomerDuplicateGroup.Member(
                customer.getId().toString(),
                customer.getMemberNo(),
                customer.getName(),
                CustomerInputs.toLabel(customer.getStatus()),
                registeredBranchId == null ? null : registeredBranchId.toString(),
                customer.getRegisteredAt(),
                paymentCounts.getOrDefault(customer.getId(), 0L),
                lastActivities.get(customer.getId())
        );
    }

    private static CustomerMergeRecord toRecord(
            CustomerMerge row,
            Map<Long, CustomerSummary> summaries,
            Map<Long, String> names
    ) {
        CustomerSummary via = row.getViaCustomerId() == null ? null : summaries.get(row.getViaCustomerId());
        CustomerMergeRecord.ActorSummary mergedBy = new CustomerMergeRecord.ActorSummary(
                Long.toString(row.getMergedBy()),
                names.get(row.getMergedBy())
        );

        return new CustomerMergeRecord(
                row.getId().toString(),
                summaries.get(row.getTargetCustomerId()),
                summaries.get(row.getAbsorbedCustomerId()),
                via,
                mergedBy,
                row.getMergedAt(),
                row.getReason()
        );
    }

    /** DATETIME read natively: the session time zone is UTC (connectionTimeZone=UTC). */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }

        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC);
        }

        if (value instanceof Instant instant) {
            return instant;
        }

        throw new IllegalStateException("Unexpected DATETIME type " + value.getClass());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
