package com.openstudy.modules.passhistory.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.modules.passhistory.dto.response.CustomerPassHistory;
import com.openstudy.modules.passhistory.service.PassHistoryService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PassHistoryController {

    private final PassHistoryService passHistoryService;

    @GetMapping("/customers/{id}/pass-history")
    public CustomerPassHistory getCustomerPassHistory(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        long actorId = Long.parseLong(actor.getName());

        return passHistoryService.getHistory(actorId, id, scope, from, to);
    }
}
