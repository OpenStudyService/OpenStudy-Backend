package com.openstudy.modules.branch.controller;

import java.net.URI;
import java.util.Set;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.common.web.*;
import com.openstudy.modules.branch.dto.*;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.common.exception.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BranchController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name");
    private static final String DEFAULT_SORT = "createdAt";
    private static final String ALL_SCOPE = "all";
    private static final Set<String> NON_NULL_FIELDS = Set.of("name", "address", "phone", "weeklyHours");

    private final BranchService branchService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @GetMapping("/branches")
    public PageResponse<Branch> listBranches(
            Authentication actor,
            @RequestParam(defaultValue = ALL_SCOPE) String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return branchService.list(actorId(actor), scope, query, status);
    }

    @GetMapping("/branches/{id}")
    public Branch getBranch(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = ALL_SCOPE) String scope
    ) {
        requireSelectedScope(id, scope);

        return branchService.get(actorId(actor), id);
    }

    @PostMapping("/branches")
    public ResponseEntity<Branch> createBranch(
            Authentication actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody BranchRequests.Create body,
            HttpServletRequest request
    ) {
        Branch result = branchService.create(actorId(actor), key, body, ApiRequestFilter.requestId(request));

        return ResponseEntity.created(URI.create("/api/branches/" + result.id())).body(result);
    }

    @PatchMapping("/branches/{id}")
    public Branch updateBranch(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = ALL_SCOPE) String scope,
            @RequestBody JsonNode body,
            HttpServletRequest request
    ) {
        requireSelectedScope(id, scope);
        BranchRequests.Update update = readUpdate(body);

        return branchService.update(actorId(actor), id, update, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/branches/{id}/status")
    public Branch changeBranchStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = ALL_SCOPE) String scope,
            @Valid @RequestBody BranchRequests.Status body,
            HttpServletRequest request
    ) {
        requireSelectedScope(id, scope);

        return branchService.changeStatus(actorId(actor), id, body, ApiRequestFilter.requestId(request));
    }

    /** PATCH distinguishes an absent field from an explicit null, unlike nullable record components. */
    private BranchRequests.Update readUpdate(JsonNode body) {
        if (!body.isObject()) {
            throw ApiException.validation("body", "JSON 객체가 필요합니다.");
        }

        for (String field : NON_NULL_FIELDS) {
            boolean explicitNull = body.has(field) && body.get(field).isNull();

            if (explicitNull) {
                throw ApiException.validation(field, "null은 허용하지 않습니다.");
            }
        }

        BranchRequests.Update update;

        try {
            update = objectMapper.treeToValue(body, BranchRequests.Update.class);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw ApiException.validation("body", "허용된 필드와 형식을 확인해 주세요.");
        }

        Set<ConstraintViolation<BranchRequests.Update>> violations = validator.validate(update);

        if (!violations.isEmpty()) {
            String field = violations.iterator()
                    .next()
                    .getPropertyPath()
                    .toString();
            throw ApiException.validation(field, "입력값을 확인해 주세요.");
        }

        return update;
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private static void requireSelectedScope(long id, String scope) {
        boolean selected = ALL_SCOPE.equals(scope) || Long.toString(id).equals(scope);

        if (!selected) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
