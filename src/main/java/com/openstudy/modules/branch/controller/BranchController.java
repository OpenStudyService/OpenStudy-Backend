package com.openstudy.modules.branch.controller;

import java.net.URI;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.openstudy.common.web.*;
import com.openstudy.modules.branch.dto.*;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.common.exception.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;

@RestController
@RequestMapping("/api/branches")
public class BranchController {
    private final BranchService service;
    private final ObjectMapper json;
    private final Validator validator;
    public BranchController(BranchService service,ObjectMapper json,Validator validator) { this.service=service;this.json=json;this.validator=validator; }
    @GetMapping
    public PageResponse<Branch> list(Authentication actor,@RequestParam(defaultValue="all") String scope,
            @RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String q,@RequestParam(required=false) String sort,
            @RequestParam(required=false) String direction,@RequestParam(required=false) String status) {
        return service.list(id(actor),scope,ListQuery.parse(page,pageSize,q,sort,direction,Set.of("createdAt","name"),"createdAt"),status);
    }
    @GetMapping("/{id}")
    public Branch get(Authentication actor,@PathVariable long id,@RequestParam(defaultValue="all") String scope) {
        selectedScope(id,scope);return service.get(id(actor),id);
    }
    @PostMapping
    public ResponseEntity<Branch> create(Authentication actor,@RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody BranchRequests.Create body,HttpServletRequest request) {
        var result=service.create(id(actor),key,body,ApiRequestFilter.requestId(request));
        return ResponseEntity.created(URI.create("/api/branches/"+result.id())).body(result);
    }
    @PatchMapping("/{id}")
    public Branch update(Authentication actor,@PathVariable long id,@RequestParam(defaultValue="all") String scope,@RequestBody JsonNode body,HttpServletRequest request) {
        selectedScope(id,scope);
        // PATCH distinguishes an absent field from an explicit null, unlike nullable record components.
        if(!body.isObject()) throw ApiException.validation("body","JSON 객체가 필요합니다.");
        for(String field:Set.of("name","address","phone","weeklyHours"))
            if(body.has(field)&&body.get(field).isNull()) throw ApiException.validation(field,"null은 허용하지 않습니다.");
        BranchRequests.Update update;
        try { update=json.treeToValue(body,BranchRequests.Update.class); }
        catch(IllegalArgumentException|tools.jackson.core.JacksonException exception) { throw ApiException.validation("body","허용된 필드와 형식을 확인해 주세요."); }
        var invalid=validator.validate(update);
        if(!invalid.isEmpty()) throw ApiException.validation(invalid.iterator().next().getPropertyPath().toString(),"입력값을 확인해 주세요.");
        return service.update(id(actor),id,update,ApiRequestFilter.requestId(request));
    }
    @PostMapping("/{id}/status")
    public Branch status(Authentication actor,@PathVariable long id,@RequestParam(defaultValue="all") String scope,@Valid @RequestBody BranchRequests.Status body,HttpServletRequest request) {
        selectedScope(id,scope);
        return service.changeStatus(id(actor),id,body,ApiRequestFilter.requestId(request));
    }
    private static long id(Authentication authentication) { return Long.parseLong(authentication.getName()); }
    private static void selectedScope(long id,String scope) {
        if(!"all".equals(scope)&&!Long.toString(id).equals(scope))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
    }
}
