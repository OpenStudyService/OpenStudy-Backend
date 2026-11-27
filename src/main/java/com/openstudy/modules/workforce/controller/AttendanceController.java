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
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api")
@RequiredArgsConstructor
public class AttendanceController {

    private static final String RECORD_LOCATION = "/api/attendance-records/";
    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final AttendanceService attendanceService;

    @GetMapping("/me/attendance/current")
    public CurrentAttendance getMyCurrentAttendance(Authentication actor) {
        return attendanceService.current(actorId(actor));
    }

    @PostMapping("/me/attendance/clock-in")
    public ResponseEntity<AttendanceRecordView> clockIn(
            Authentication actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body
    ) {
        AttendanceRecordView result = attendanceService.clockIn(actorId(actor), key, body);

        return ResponseEntity.created(URI.create(RECORD_LOCATION + result.id()))
                .body(result);
    }

    @PostMapping("/me/attendance/break-start")
    public AttendanceRecordView startBreak(Authentication actor, @RequestBody Map<String, Object> body) {
        return attendanceService.startBreak(actorId(actor), body);
    }

    @PostMapping("/me/attendance/break-end")
    public AttendanceRecordView endBreak(Authentication actor, @RequestBody Map<String, Object> body) {
        return attendanceService.endBreak(actorId(actor), body);
    }

    @PostMapping("/me/attendance/clock-out")
    public AttendanceRecordView clockOut(Authentication actor, @RequestBody Map<String, Object> body) {
        return attendanceService.clockOut(actorId(actor), body);
    }

    @GetMapping("/attendance-records")
    public PageResponse<AttendanceRecordView> listAttendanceRecords(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) String mine,
            @RequestParam(required = false) String shiftId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        // The order is fixed by the contract (clockInAt, then id, both descending), so there is no sort parameter.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);

        return attendanceService.list(actorId(actor), scope, query, employeeId, mine, shiftId, status, from, to);
    }

    @GetMapping("/attendance-records/{id}")
    public AttendanceRecordView getAttendanceRecord(Authentication actor, @PathVariable long id) {
        return attendanceService.get(actorId(actor), id);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
