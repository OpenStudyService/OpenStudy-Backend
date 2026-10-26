package com.openstudy.modules.workspace.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.openstudy.modules.workspace.dto.WorkspaceLookups;
import com.openstudy.modules.workspace.service.WorkspaceLookupService;
import com.openstudy.modules.workspace.service.WorkspaceEventService;

@RestController
public class WorkspaceController {
    private final WorkspaceLookupService lookups;
    private final WorkspaceEventService events;
    public WorkspaceController(WorkspaceLookupService lookups,WorkspaceEventService events) {
        this.lookups=lookups;this.events=events;
    }
    @GetMapping("/api/lookups")
    public ResponseEntity<WorkspaceLookups> lookups(Authentication actor,@RequestParam(defaultValue="all") String scope) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(lookups.current(Long.parseLong(actor.getName()),scope));
    }
    @GetMapping(value="/api/workspace/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(Authentication actor,@RequestParam(defaultValue="all") String scope,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
                .body(events.open(Long.parseLong(actor.getName()),request.getSession(false).getId(),scope));
    }
}
