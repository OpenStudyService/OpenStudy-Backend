package com.openstudy.modules.workspace.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.workspace.dto.WorkspaceLookups;
import com.openstudy.modules.workspace.service.WorkspaceLookupService;

@RestController
@RequestMapping("/api/lookups")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceLookupService workspaceLookupService;

    @GetMapping
    public ResponseEntity<WorkspaceLookups> getLookups(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope
    ) {
        WorkspaceLookups lookups = workspaceLookupService.getLookups(Long.parseLong(actor.getName()), scope);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lookups);
    }
}
