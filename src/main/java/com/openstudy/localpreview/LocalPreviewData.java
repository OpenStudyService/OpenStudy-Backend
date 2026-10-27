package com.openstudy.localpreview;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.AccessDeniedException;

import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;

public class LocalPreviewData {
    public static final List<String> RESOURCES = List.of("branches", "employees", "members", "products", "passes", "rooms",
            "seats", "shifts", "attendance", "channels", "tasks", "facilities", "inquiries", "notices", "messages",
            "payments", "reservations", "usage", "lockers", "invitations", "corrections", "leaveGrants", "leaveRequests",
            "substitutions", "audit");
    private final CurrentUserService users;
    private final AuthorizationService authorization;

    public LocalPreviewData(CurrentUserService users, AuthorizationService authorization) {
        this.users = users;
        this.authorization = authorization;
    }

    public Map<String, List<Map<String, Object>>> snapshot(long employeeId, String scope) {
        CurrentUser current = users.current(employeeId);
        if (!scope.equals("all") && current.branches().stream().noneMatch(branch -> branch.id().equals(scope))) {
            throw new AccessDeniedException("지점 접근 권한이 없습니다.");
        }
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        RESOURCES.forEach(resource -> data.put(resource, new ArrayList<>()));
        String now = Instant.now().toString();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var user = current.user();
        data.get("employees").add(row(user.id(), user.branchId(), now, "name", user.name(), "email", user.email(),
                "nickname", user.nickname(), "avatar", user.avatar(), "status", user.status(), "owner", user.owner(),
                "memberships", user.memberships()));
        for (var branch : current.branches()) {
            // The selector needs every accessible branch even when a single scope is selected.
            data.get("branches").add(row(branch.id(), branch.id(), now, "name", branch.name(), "address", branch.address(),
                    "phone", branch.phone(), "hours", "09:00–23:00", "status", branch.status()));
            if (!scope.equals("all") && !scope.equals(branch.id())) continue;
            String id = branch.id();
            String coworker = "preview-employee-" + id;
            String member = "preview-member-" + id;
            data.get("employees").add(row(coworker, id, now, "name", "샘플 매니저", "email", "preview-" + id + "@example.com",
                    "nickname", "샘플 매니저", "avatar", "", "status", "활성", "owner", false,
                    "memberships", List.of(Map.of("branchId", id, "role", "staff", "permissions", List.of()))));
            data.get("members").add(row(member, id, now, "name", "샘플 회원", "email", "member-" + id + "@example.com",
                    "phone", "010-0000-0000", "status", "정상", "note", "화면 확인용 가상 회원입니다.", "branchIds", List.of(id)));
            for (int i = 1; i <= 12; i++) {
                boolean occupied = i <= 5;
                data.get("seats").add(row("preview-seat-" + id + "-" + i, id, now, "name", String.format("A-%02d", i),
                        "zone", i <= 6 ? "집중존" : "라운지", "status", occupied ? "이용 중" : "이용 가능",
                        "memberId", occupied ? member : ""));
            }
            for (int i = 0; i < 3; i++) {
                data.get("tasks").add(row("preview-task-" + id + "-" + i, id, now,
                        "title", List.of("오픈 전 좌석과 공용 공간 점검", "원두와 소모품 재고 확인", "스터디룸 정리 및 환기").get(i),
                        "body", "로컬 화면 확인을 위한 샘플 업무입니다.", "assigneeId", i == 1 ? coworker : user.id(),
                        "due", today.toString(), "priority", i == 0 ? "높음" : "보통", "category", "매장 운영",
                        "status", List.of("할 일", "진행 중", "완료").get(i), "checks", List.of(), "history", List.of()));
            }
            data.get("facilities").add(row("preview-facility-" + id, id, now, "title", "공기청정기 필터 점검", "category", "정기 점검",
                    "assigneeId", coworker, "body", "필터 오염도와 작동 상태를 확인해 주세요.", "cycleDays", 7,
                    "lastChecked", today.minusDays(7).toString(), "nextCheck", today.toString(),
                    "condition", "점검 필요", "status", "할 일", "result", ""));
            data.get("inquiries").add(row("preview-inquiry-" + id, id, now, "title", "스터디룸 이용 시간 문의", "memberId", member,
                    "assigneeId", user.id(), "body", "주말 스터디룸 이용 가능한 시간이 궁금합니다.", "reply", "", "status", "접수"));
            data.get("notices").add(row("preview-notice-" + id, id, now, "title", "쾌적한 학습 공간을 함께 만들어요", "pinned", true,
                    "body", "공용 공간 이용 후 정리와 정숙한 분위기 유지를 부탁드립니다. 이 공지는 샘플입니다.",
                    "authorId", user.id(), "status", "게시 중"));
            data.get("shifts").add(row("preview-shift-" + id, id, now, "employeeId", coworker,
                    "startsAt", today.atTime(9, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toString(),
                    "endsAt", today.atTime(18, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toString(),
                    "breakMinutes", 60, "status", "예정"));
            data.get("rooms").add(row("preview-room-" + id, id, now, "name", "4인 스터디룸", "capacity", 4,
                    "hourlyRate", 6000, "status", "이용 가능"));
            data.get("products").add(row("preview-product-" + id, id, now, "name", "집중 10시간권", "kind", "시간권",
                    "price", 20000, "amount", 600, "hourlyRate", 2000, "days", 30, "status", "판매 중"));
        }
        // Preview fixtures follow the same current role/grant boundary as operational selection data.
        // This is an optional employee projection, not an authorization gate for the whole home.
        // An explicit scope would throw for ordinary staff even though they can read that branch.
        var allowedPeople = authorization.scope(employeeId, "all", BranchAction.MANAGE_PEOPLE);
        data.get("employees").removeIf(row -> !(user.owner() && user.id().equals(row.get("id")))
                && (!allowedPeople.contains(parseBranch(row.get("branchId")))
                    || (!scope.equals("all") && !scope.equals(row.get("branchId")))));
        return data;
    }

    private static long parseBranch(Object id) {
        try { return Long.parseLong(String.valueOf(id)); }
        catch (NumberFormatException exception) { return -1; }
    }

    private static Map<String, Object> row(String id, String branchId, String now, Object... fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("branchId", branchId);
        row.put("version", 1);
        row.put("createdAt", now);
        row.put("updatedAt", now);
        for (int i = 0; i < fields.length; i += 2) row.put((String) fields[i], fields[i + 1]);
        return row;
    }
}
