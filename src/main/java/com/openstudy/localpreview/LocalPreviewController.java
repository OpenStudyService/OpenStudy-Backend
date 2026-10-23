package com.openstudy.localpreview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Available only with the local profile and preview enabled.
@RestController
@org.springframework.context.annotation.Profile("local")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "openstudy.local-preview.enabled", havingValue = "true", matchIfMissing = true)
public class LocalPreviewController {
    private static final List<String> LOOKUPS = List.of("branches", "employees", "members", "products", "passes", "rooms",
            "seats", "shifts", "attendance", "channels");
    private static final List<String> HOME = List.of("tasks", "seats", "facilities", "inquiries", "notices", "shifts", "messages");
    private final LocalPreviewData preview;

    public LocalPreviewController(LocalPreviewData preview) {
        this.preview = preview;
    }

    @GetMapping("/api/lookups")
    public Map<String, Object> lookups(Authentication auth, @RequestParam(defaultValue = "all") String scope) {
        var snapshot = preview.snapshot(Long.parseLong(auth.getName()), scope);
        Map<String, Object> result = new LinkedHashMap<>();
        LOOKUPS.forEach(key -> result.put(key, snapshot.get(key)));
        result.put("preview", true);
        return result;
    }

    @GetMapping("/api/home")
    public Map<String, Object> home(Authentication auth, @RequestParam(defaultValue = "all") String scope) {
        var snapshot = preview.snapshot(Long.parseLong(auth.getName()), scope);
        Map<String, Object> result = new LinkedHashMap<>();
        HOME.forEach(key -> result.put(key, snapshot.get(key)));
        return result;
    }

    @GetMapping("/api/{resource:tasks|seats|facilities|inquiries|notices|shifts|rooms|products|members|employees|branches|passes|attendance|channels|payments|reservations|usage|lockers|invitations|corrections|leaveGrants|leaveRequests|substitutions|audit}")
    public Map<String, Object> list(Authentication auth, @PathVariable String resource,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String status) {
        var rows = preview.snapshot(Long.parseLong(auth.getName()), scope).get(resource).stream()
                .filter(row -> status.isBlank() || status.equals(row.get("status")))
                .filter(row -> q.isBlank() || row.values().stream().anyMatch(value -> value.toString().contains(q)))
                .toList();
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "페이지 범위를 확인해 주세요.");
        int from = (int) Math.min((long) (page - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        return Map.of("items", rows.subList(from, to), "total", rows.size(), "page", page, "pageSize", pageSize);
    }

    @GetMapping("/api/{resource:tasks|seats|facilities|inquiries|notices|shifts|rooms|products|members|employees|branches}/{id}")
    public Map<String, Object> detail(Authentication auth, @PathVariable String resource, @PathVariable String id,
            @RequestParam(defaultValue = "all") String scope) {
        return preview.snapshot(Long.parseLong(auth.getName()), scope).get(resource).stream()
                .filter(row -> id.equals(row.get("id"))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "샘플 항목을 찾을 수 없습니다."));
    }

    @RequestMapping(value = {"/api/{resource:tasks|seats|facilities|inquiries|notices|shifts|rooms|products|members|employees|branches|payments|reservations|usage|lockers}",
            "/api/{resource:tasks|seats|facilities|inquiries|notices|shifts|rooms|products|members|employees|branches|payments|reservations|usage|lockers}/**"},
            method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public void readOnly() {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "로컬 샘플은 조회 전용입니다. 저장 기능은 아직 구현되지 않았습니다.");
    }
}
