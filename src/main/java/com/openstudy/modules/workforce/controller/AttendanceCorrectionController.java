package com.openstudy.modules.workforce.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.workforce.dto.response.AttendanceCorrectionView;
import com.openstudy.modules.workforce.dto.response.WorkforceRequestConflictError;
import com.openstudy.modules.workforce.service.CorrectionService;
import com.openstudy.modules.workforce.service.WorkforceRequestRefusal;

/**
 * Attendance corrections (#107, workforce-requests-api.md "근태 정정"). There is no DELETE, no edit and no cancellation of
 * an approval (M12-D08): a further change is a new correction.
 */
@RestController
@RequiredArgsConstructor
public class AttendanceCorrectionController {
    private final CorrectionService service;

    @GetMapping("/api/attendance-corrections")
    public PageResponse<AttendanceCorrectionView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String status, @RequestParam(required = false) String attendanceRecordId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        // The order is fixed by the contract (createdAt, then id, both descending).
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), employeeId, mine,
                status, attendanceRecordId, from, to);
    }

    @PostMapping("/api/attendance-records/{id}/corrections")
    public ResponseEntity<AttendanceCorrectionView> create(Authentication actor, @PathVariable long id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        var result = service.create(actor(actor), id, key, body);
        return ResponseEntity.created(URI.create("/api/attendance-corrections/" + result.id())).body(result);
    }

    @GetMapping("/api/attendance-corrections/{id}")
    public AttendanceCorrectionView get(Authentication actor, @PathVariable long id) { return service.get(actor(actor), id); }

    @PostMapping("/api/attendance-corrections/{id}/approve")
    public AttendanceCorrectionView approve(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.approve(actor(actor), id, body);
    }

    @PostMapping("/api/attendance-corrections/{id}/reject")
    public AttendanceCorrectionView reject(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return service.reject(actor(actor), id, body, ApiRequestFilter.requestId(request));
    }

    @PostMapping("/api/attendance-corrections/{id}/cancel")
    public AttendanceCorrectionView cancel(Authentication actor, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return service.cancel(actor(actor), id, body);
    }

    /**
     * The contract's WorkforceRequestConflictError. A handler here, not a scoped advice: the global handler's
     * {@code Exception} fallback would win over an advice and answer 500 (#108).
     */
    @ExceptionHandler(WorkforceRequestRefusal.class)
    ResponseEntity<WorkforceRequestConflictError> refused(WorkforceRequestRefusal refusal, HttpServletRequest request) {
        return ResponseEntity.status(refusal.status()).body(refusal.error(ApiRequestFilter.requestId(request)));
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
