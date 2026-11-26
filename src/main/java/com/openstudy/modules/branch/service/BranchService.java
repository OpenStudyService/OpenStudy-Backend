package com.openstudy.modules.branch.service;

import java.util.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import jakarta.persistence.EntityManager;
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
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.branch.dto.*;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.entity.BranchStatus;
import com.openstudy.modules.branch.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class BranchService {

    private static final String CREATE_OPERATION = "branches:create";
    private static final String CREATE_SCOPE = "all";
    private static final String AUDIT_ENTITY_TYPE = "branches";
    private static final String CODE_PREFIX = "BR-";
    private static final int CODE_RANDOM_LENGTH = 29;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED_STATUS = 201;
    private static final Duration KEY_RETENTION = Duration.ofHours(24);
    private static final int DAYS_PER_WEEK = 7;
    private static final int LAST_OPENING_MINUTE = 1439;
    private static final int LAST_CLOSING_MINUTE = 2880;

    private final BranchRepository branchRepository;
    private final EntityManager entityManager;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<Branch> list(long actor, String scope, ListQuery query, String status) {
        if (status != null) {
            toDbStatus(status);
        }

        Set<Long> allowed = authorizationService.scope(actor, scope, BranchAction.READ);
        String keyword = query.q().toLowerCase(Locale.ROOT);

        List<Branch> rows = branchRepository.findAllWithWeeklyHoursByOrderByIdAsc()
                .stream()
                .filter(branch -> allowed.contains(branch.getId()))
                .map(BranchViews::toDto)
                .filter(branch -> status == null || status.equals(branch.status()))
                .filter(branch -> matchesKeyword(branch, keyword))
                .toList();

        Comparator<Branch> order = "name".equals(query.sort())
                ? Comparator.comparing(Branch::name)
                : Comparator.comparing(Branch::createdAt);
        Comparator<Branch> tieBreaker = Comparator.comparingLong(branch -> Long.parseLong(branch.id()));

        return PageResponse.fromAuthorized(rows, query, order, tieBreaker);
    }

    @Transactional(readOnly = true)
    public List<Branch> lookup(long actor, String scope) {
        Set<Long> allowed = authorizationService.scope(actor, scope, BranchAction.READ);

        return branchRepository.findAllWithWeeklyHoursByOrderByIdAsc()
                .stream()
                .filter(branch -> allowed.contains(branch.getId()))
                .map(BranchViews::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Branch get(long actor, long id) {
        // Require access before reading the branch or its schedule; no existence oracle outside scope.
        authorizationService.require(authorizationService.current(actor), Set.of(id), BranchAction.READ);

        return BranchViews.toDto(findBranch(id));
    }

    public Branch create(long actor, String key, BranchRequests.Create body, String requestId) {
        requireValidKey(key);

        BranchRequests.Create normalized = new BranchRequests.Create(
                requireText(body.name(), "name"),
                requireText(body.address(), "address"),
                body.phone(),
                validateWeeklyHours(body.weeklyHours()),
                body.status()
        );
        String dbStatus = toDbStatus(body.status());

        return authorizedCommands.ownerCommand(actor, () -> {
            byte[] fingerprint = digest(objectMapper.writeValueAsString(normalized));
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE_OPERATION, CREATE_SCOPE, key);

            if (prior.isPresent()) {
                return replay(prior.get(), fingerprint);
            }

            // created_by/updated_by stay NULL as before this migration; the actor is recorded in audit_events.
            com.openstudy.modules.branch.entity.Branch branch = new com.openstudy.modules.branch.entity.Branch(
                    createCode(),
                    normalized.name(),
                    normalized.address(),
                    normalized.phone(),
                    BranchStatus.valueOf(dbStatus),
                    null
            );
            applyWeeklyHours(branch, normalized.weeklyHours());
            branchRepository.saveAndFlush(branch);
            entityManager.refresh(branch);

            Branch result = BranchViews.toDto(branch);
            String snapshot = objectMapper.writeValueAsString(result);
            recordChange(actor, branch.getId(), "CREATED", null, snapshot, requestId);
            idempotencyStore.complete(
                    actor,
                    CREATE_OPERATION,
                    CREATE_SCOPE,
                    key,
                    fingerprint,
                    CREATED_STATUS,
                    snapshot,
                    clock.instant().plus(KEY_RETENTION)
            );
            return result;
        });
    }

    public Branch update(long actor, long id, BranchRequests.Update body, String requestId) {
        ApiVersion.toDatabase(body.version());

        boolean nothingToChange = body.name() == null
                && body.address() == null
                && body.phone() == null
                && body.weeklyHours() == null;

        if (nothingToChange) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        List<WeeklyHours> schedule = body.weeklyHours() == null ? null : validateWeeklyHours(body.weeklyHours());

        return authorizedCommands.inBranches(actor, Set.of(id), BranchAction.MANAGE_BRANCH, () -> {
            com.openstudy.modules.branch.entity.Branch branch = findBranch(id);
            Branch before = BranchViews.toDto(branch);
            requireCurrentVersion(before, body.version());
            long version = branch.getVersion();

            String name = body.name() == null ? before.name() : requireText(body.name(), "name");
            String address = body.address() == null ? before.address() : requireText(body.address(), "address");
            String phone = body.phone() == null ? before.phone() : body.phone();
            branch.update(name, address, phone);
            applyWeeklyHours(branch, schedule == null ? before.weeklyHours() : schedule);

            Branch result = saveChange(branch, version);
            String beforeJson = objectMapper.writeValueAsString(before);
            recordChange(actor, id, "UPDATED", beforeJson, objectMapper.writeValueAsString(result), requestId);
            return result;
        });
    }

    public Branch changeStatus(long actor, long id, BranchRequests.Status body, String requestId) {
        ApiVersion.toDatabase(body.version());
        String dbStatus = toDbStatus(body.status());

        return authorizedCommands.inBranches(actor, Set.of(id), BranchAction.MANAGE_BRANCH, () -> {
            com.openstudy.modules.branch.entity.Branch branch = findBranch(id);
            Branch before = BranchViews.toDto(branch);
            requireCurrentVersion(before, body.version());
            long version = branch.getVersion();
            branch.changeStatus(BranchStatus.valueOf(dbStatus));

            Branch result = saveChange(branch, version);
            String beforeJson = objectMapper.writeValueAsString(before);
            recordChange(actor, id, "STATUS_CHANGED", beforeJson, objectMapper.writeValueAsString(result), requestId);
            return result;
        });
    }

    private com.openstudy.modules.branch.entity.Branch findBranch(long id) {
        return branchRepository.findWithWeeklyHoursById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Branch replay(IdempotencyRecord prior, byte[] fingerprint) {
        if (!MessageDigest.isEqual(fingerprint, prior.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
        }

        return objectMapper.readValue(prior.getResponseJson(), Branch.class);
    }

    /**
     * Every accepted command advances the version by exactly one, even when no column differs
     * (the former SQL always ran version=version+1). Hibernate only bumps a dirty branch row, and weekly-hour
     * rows are separate entities, so an unchanged row gets one explicit increment. A bulk UPDATE is used instead of
     * a FORCE_INCREMENT lock, which Hibernate skips when the entity already holds that lock in the same context.
     * The refresh re-reads the DB-maintained updated_at.
     */
    private Branch saveChange(com.openstudy.modules.branch.entity.Branch branch, long versionBefore) {
        branchRepository.flush();

        if (branch.getVersion() == versionBefore) {
            branchRepository.incrementVersion(branch.getId());
        }

        entityManager.refresh(branch);
        return BranchViews.toDto(branch);
    }

    private void recordChange(long actor, long id, String action, String before, String after, String requestId) {
        auditTrail.record(AuditEvent.change(actor, id, AUDIT_ENTITY_TYPE, id, action, before, after, requestId));
    }

    private static void applyWeeklyHours(com.openstudy.modules.branch.entity.Branch branch, List<WeeklyHours> hours) {
        for (WeeklyHours hour : hours) {
            branch.setWeeklyHour(hour.weekday(), hour.isClosed(), hour.opensMinute(), hour.closesMinute());
        }
    }

    private static boolean matchesKeyword(Branch branch, String keyword) {
        String searchable = branch.name() + " " + branch.address() + " " + branch.code();

        return searchable.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static String createCode() {
        String random = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, CODE_RANDOM_LENGTH);

        return CODE_PREFIX + random;
    }

    private static void requireCurrentVersion(Branch branch, long supplied) {
        if (branch.version() != supplied) {
            throw ApiException.versionConflict(branch.version(), "/api/branches/" + branch.id());
        }
    }

    private static void requireValidKey(String key) {
        boolean valid = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!valid) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(field, "값을 입력해 주세요.");
        }

        return value.strip();
    }

    private static String toDbStatus(String status) {
        return switch (status == null ? "" : status) {
            case "운영 중" -> "ACTIVE";
            case "운영 중지" -> "SUSPENDED";
            default -> throw ApiException.validation("status", "올바른 지점 상태를 선택해 주세요.");
        };
    }

    private static List<WeeklyHours> validateWeeklyHours(List<WeeklyHours> hours) {
        if (hours == null || hours.size() != DAYS_PER_WEEK) {
            throw ApiException.validation("weeklyHours", "7개 요일을 모두 지정해 주세요.");
        }

        Set<Integer> days = new HashSet<>();

        for (WeeklyHours hour : hours) {
            if (!isValidWeekday(hour, days)) {
                throw ApiException.validation("weeklyHours", "요일은 1~7까지 중복 없이 지정해 주세요.");
            }

            if (!isValidTimes(hour)) {
                throw ApiException.validation("weeklyHours", "휴무는 시각 없이, 영업은 시작보다 늦은 종료 분을 지정해 주세요.");
            }
        }

        return hours.stream()
                .sorted(Comparator.comparing(WeeklyHours::weekday))
                .toList();
    }

    /** Also records the weekday in {@code seen}, so a repeated weekday is invalid. */
    private static boolean isValidWeekday(WeeklyHours hour, Set<Integer> seen) {
        return hour != null
                && hour.weekday() != null
                && hour.weekday() >= 1
                && hour.weekday() <= DAYS_PER_WEEK
                && seen.add(hour.weekday())
                && hour.isClosed() != null;
    }

    /** A closed day has no times; an open day closes after it opens, at most the next day's end. */
    private static boolean isValidTimes(WeeklyHours hour) {
        if (hour.isClosed()) {
            return hour.opensMinute() == null && hour.closesMinute() == null;
        }

        return hour.opensMinute() != null
                && hour.closesMinute() != null
                && hour.opensMinute() >= 0
                && hour.opensMinute() <= LAST_OPENING_MINUTE
                && hour.closesMinute() <= LAST_CLOSING_MINUTE
                && hour.closesMinute() > hour.opensMinute();
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
