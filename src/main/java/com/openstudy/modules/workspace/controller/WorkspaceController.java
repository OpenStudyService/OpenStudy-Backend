package com.openstudy.modules.workspace.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.workspace.dto.WorkspaceLookups;
import com.openstudy.modules.workspace.service.WorkspaceLookupService;

@RestController
public class WorkspaceController {
    private final WorkspaceLookupService lookups;
    public WorkspaceController(WorkspaceLookupService lookups) {
        this.lookups=lookups;
    }
    @GetMapping("/api/lookups")
    public ResponseEntity<WorkspaceLookups> lookups(Authentication actor,@RequestParam(defaultValue="all") String scope) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lookups.current(Long.parseLong(actor.getName()),scope));
    }
}
