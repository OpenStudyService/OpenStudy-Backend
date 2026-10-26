package com.openstudy.localpreview;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;
import java.math.BigInteger;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;

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

    @GetMapping("/api/local-preview/lookups")
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
    public PageResponse<Map<String, Object>> list(Authentication auth, @PathVariable String resource,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String status, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        // Preview keeps its legacy resource fields; operational endpoints define their own whitelist.
        Set<String> sorts = switch (resource) {
            case "tasks" -> Set.of("createdAt", "name", "status", "due");
            default -> Set.of("createdAt", "name", "status");
        };
        var query = ListQuery.parse(page, pageSize, q, sort, direction, sorts, "createdAt");
        var rows = preview.snapshot(Long.parseLong(auth.getName()), scope).get(resource).stream()
                .filter(row -> status.isBlank() || status.equals(row.get("status")))
                .filter(row -> query.q().isBlank() || java.util.stream.Stream.of("name", "title", "code")
                        .anyMatch(field -> String.valueOf(row.getOrDefault(field, "")).contains(query.q())))
                .toList();
        return PageResponse.fromAuthorized(rows, query,
                Comparator.comparing(row -> String.valueOf(row.getOrDefault(query.sort(), ""))),
                (left, right) -> compareIds(String.valueOf(left.get("id")), String.valueOf(right.get("id"))));
    }

    private static int compareIds(String left, String right) {
        if (left.matches("[0-9]+") && right.matches("[0-9]+")) return new BigInteger(left).compareTo(new BigInteger(right));
        return left.compareTo(right); // Preview IDs also contain resource prefixes.
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
