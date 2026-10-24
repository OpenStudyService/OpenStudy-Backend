package com.openstudy.modules.authorization.controller;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.authorization.dto.PermissionDefinition;
import com.openstudy.modules.authorization.service.PermissionPresetService;

@RestController
public class PermissionDefinitionController {
    private final PermissionPresetService service;
    public PermissionDefinitionController(PermissionPresetService service) { this.service = service; }
    @GetMapping("/api/permissions")
    public List<PermissionDefinition> definitions(Authentication actor) {
        return service.definitions(Long.parseLong(actor.getName()));
    }
}
