package com.openstudy.modules.seat.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.seat.dto.response.SeatBoard;
import com.openstudy.modules.seat.dto.response.SeatOccupancy;
import com.openstudy.modules.seat.dto.response.SeatOccupancyTrend;
import com.openstudy.modules.seat.dto.response.SeatView;
import com.openstudy.modules.seat.service.SeatFilters;
import com.openstudy.modules.seat.service.SeatOccupancyTrendService;
import com.openstudy.modules.seat.service.SeatService;

/** There is deliberately no DELETE for a seat: a seat is closed by stopping its operation (seat-usage-api.md). */
@RestController
@RequestMapping("/api/seats")
@RequiredArgsConstructor
public class SeatController {

    private static final Set<String> SORT_FIELDS = Set.of("id");
    private static final String DEFAULT_SORT = "id";

    private final SeatService seatService;
    private final SeatOccupancyTrendService seatOccupancyTrendService;

    @GetMapping
    public PageResponse<SeatView> listSeats(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String status
    ) {
        long actorId = actorId(actor);
        // The order is fixed by the contract (zone, seatNo, id), so there is no sort parameter.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, SORT_FIELDS, DEFAULT_SORT);
        SeatFilters filters = SeatFilters.parse(zone, kind, status);

        return seatService.list(actorId, scope, query, filters);
    }

    @GetMapping("/board")
    public SeatBoard getSeatBoard(
            Authentication actor,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String kind
    ) {
        return seatService.board(actorId(actor), branchId, kind);
    }

    /** Occupancy per hour or day of one branch (#116, M13-D09). */
    @GetMapping("/occupancy-trend")
    public SeatOccupancyTrend getSeatOccupancyTrend(
            Authentication actor,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String granularity
    ) {
        return seatOccupancyTrendService.trend(actorId(actor), branchId, from, to, granularity);
    }

    @GetMapping("/occupancy")
    public SeatOccupancy getSeatOccupancy(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return seatService.occupancy(actorId(actor), scope);
    }

    @GetMapping("/{id}")
    public SeatView getSeat(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return seatService.get(actorId(actor), id, scope);
    }

    @PostMapping
    public ResponseEntity<SeatView> createSeat(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);
        SeatView result = seatService.create(actorId(actor), scope, key, body, requestId);

        return ResponseEntity.created(URI.create("/api/seats/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public SeatView updateSeat(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return seatService.update(actorId(actor), id, scope, body, requestId);
    }

    @PostMapping("/{id}/status")
    public SeatView changeSeatStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        String requestId = ApiRequestFilter.requestId(request);

        return seatService.changeStatus(actorId(actor), id, scope, body, requestId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
