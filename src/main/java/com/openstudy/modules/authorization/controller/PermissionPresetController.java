package com.openstudy.modules.authorization.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.PermissionPreset;
import com.openstudy.modules.authorization.service.PermissionPresetService;

@RestController
@RequestMapping("/api/permission-presets")
public class PermissionPresetController {
    private final PermissionPresetService service;
    public PermissionPresetController(PermissionPresetService service) { this.service=service; }
    @GetMapping
    public PageResponse<PermissionPreset> list(Authentication actor,@RequestParam(defaultValue="1") long page,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String q,
            @RequestParam(required=false) String sort,@RequestParam(required=false) String direction) {
        return service.list(Long.parseLong(actor.getName()),ListQuery.parse(page,pageSize,q,sort,direction,Set.of("createdAt","name"),"createdAt"));
    }
    @PostMapping
    public ResponseEntity<PermissionPreset> create(Authentication actor,@RequestBody Map<String,Object> body,
            @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.create(Long.parseLong(actor.getName()),body,key,ApiRequestFilter.requestId(request)));
    }
    @PatchMapping("/{id}")
    public PermissionPreset update(Authentication actor,@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return service.update(Long.parseLong(actor.getName()),id,body,ApiRequestFilter.requestId(request));
    }
}
