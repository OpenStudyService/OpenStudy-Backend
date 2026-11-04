package com.openstudy.modules.workforce.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.workforce.dto.response.AttendanceRecordView;
import com.openstudy.modules.workforce.dto.response.CurrentAttendance;
import com.openstudy.modules.workforce.service.AttendanceService;

/**
 * The clock commands live under /me/attendance and name no employee and no attendance: the server only ever changes the
 * requester's own attendance in progress (M8-D08). There is no DELETE and no correction here; corrections are M12.
 */
@RestController
@RequiredArgsConstructor
public class AttendanceController {
    private final AttendanceService service;

    @GetMapping("/api/me/attendance/current")
    public CurrentAttendance current(Authentication actor) {
        return service.current(actor(actor));
    }

    @PostMapping("/api/me/attendance/clock-in")
    public ResponseEntity<AttendanceRecordView> clockIn(Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        var result = service.clockIn(actor(actor), key, body);
        return ResponseEntity.created(URI.create("/api/attendance-records/" + result.id())).body(result);
    }

    @PostMapping("/api/me/attendance/break-start")
    public AttendanceRecordView startBreak(Authentication actor, @RequestBody Map<String, Object> body) {
        return service.startBreak(actor(actor), body);
    }

    @PostMapping("/api/me/attendance/break-end")
    public AttendanceRecordView endBreak(Authentication actor, @RequestBody Map<String, Object> body) {
        return service.endBreak(actor(actor), body);
    }

    @PostMapping("/api/me/attendance/clock-out")
    public AttendanceRecordView clockOut(Authentication actor, @RequestBody Map<String, Object> body) {
        return service.clockOut(actor(actor), body);
    }

    @GetMapping("/api/attendance-records")
    public PageResponse<AttendanceRecordView> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String shiftId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        // The order is fixed by the contract (clockInAt, then id, both descending), so there is no sort parameter.
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"), employeeId, mine,
                shiftId, status, from, to);
    }

    @GetMapping("/api/attendance-records/{id}")
    public AttendanceRecordView get(Authentication actor, @PathVariable long id) {
        return service.get(actor(actor), id);
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
