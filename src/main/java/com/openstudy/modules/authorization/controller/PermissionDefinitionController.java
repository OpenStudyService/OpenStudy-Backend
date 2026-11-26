package com.openstudy.modules.authorization.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.authorization.dto.PermissionDefinition;
import com.openstudy.modules.authorization.service.PermissionPresetService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PermissionDefinitionController {

    private final PermissionPresetService permissionPresetService;

    @GetMapping("/permissions")
    public List<PermissionDefinition> listPermissionDefinitions(Authentication actor) {
        return permissionPresetService.definitions(Long.parseLong(actor.getName()));
    }
}
