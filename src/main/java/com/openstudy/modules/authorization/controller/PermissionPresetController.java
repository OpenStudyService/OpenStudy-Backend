package com.openstudy.modules.authorization.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.PermissionPreset;
import com.openstudy.modules.authorization.service.PermissionPresetService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PermissionPresetController {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name");
    private static final String DEFAULT_SORT = "createdAt";

    private final PermissionPresetService permissionPresetService;

    @GetMapping("/permission-presets")
    public PageResponse<PermissionPreset> listPermissionPresets(
            Authentication actor,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);

        return permissionPresetService.list(actorId(actor), query);
    }

    @PostMapping("/permission-presets")
    public ResponseEntity<PermissionPreset> createPermissionPreset(
            Authentication actor,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            HttpServletRequest request
    ) {
        PermissionPreset result = permissionPresetService.create(
                actorId(actor),
                body,
                key,
                ApiRequestFilter.requestId(request)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/permission-presets/{id}")
    public PermissionPreset updatePermissionPreset(
            Authentication actor,
            @PathVariable long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        return permissionPresetService.update(actorId(actor), id, body, ApiRequestFilter.requestId(request));
    }

    private static long actorId(Authentication actor) {
        return Long.parseLong(actor.getName());
    }
}
