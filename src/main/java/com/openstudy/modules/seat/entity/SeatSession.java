package com.openstudy.modules.seat.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One stay on a seat. The active rows are unique per seat and per customer through generated columns
 * (uk_seat_sessions_1·2), and a stay ends once: closing stores the end key and what the pass settled. Customer, pass,
 * branch and check-in time never change; the seat changes only through a move (#63). The exception columns name who
 * handled the check-in, because every M5 check-in is a staff exception (FR-SEAT-06).
 */
@Entity
@Table(name = "seat_sessions")
@SemanticEntity(
        id = "seat_session",
        name = "좌석 입퇴실 세션",
        description = "좌석 체크인·체크아웃 이용 세션 및 시간대별 혼잡도 원장",
        aliases = {"혼잡도", "입실", "퇴실", "이용세션", "피크 시간대"},
        domain = "seats"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatSession {
    private static final String INT_UNSIGNED = "int unsigned";

    public enum Status {
        ACTIVE("이용 중"), CLOSED("종료");

        private final String label;

        Status(String label) { this.label = label; }

        public String label() { return label; }
    }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "seat_session.id", name = "좌석 세션 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    @SemanticRelation(id = "seat_session.customer", targetEntity = "customer", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "입실 회원")
    private long customerId;
    @Column(name = "pass_id", nullable = false, updatable = false)
    @SemanticRelation(id = "seat_session.pass", targetEntity = "pass", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "사용 이용권")
    private long passId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "seat_session.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "seat_session.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "좌석 이용 지점")
    private long branchId;
    @Column(name = "seat_id", nullable = false)
    @SemanticField(id = "seat_session.seat_id", name = "좌석 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "seat_session.seat", targetEntity = "seat", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "입실 좌석")
    private long seatId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "seat_session.status", name = "세션 상태", role = "STATUS", allowedValues = {"ACTIVE", "CLOSED"}, allowedOps = {"EQ", "NE", "IN"})
    private Status status;
    @Column(name = "checked_in_at", nullable = false, updatable = false)
    @SemanticField(id = "seat_session.checked_in_at", name = "입실 시각", role = "TIME", aliases = {"입실", "체크인"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant checkedInAt;
    @Column(name = "effective_checked_in_at", nullable = false, updatable = false)
    private Instant effectiveCheckedInAt;
    @Column(name = "checked_out_at")
    @SemanticField(id = "seat_session.checked_out_at", name = "퇴실 시각", role = "TIME", aliases = {"퇴실", "체크아웃"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IS_NULL"})
    private Instant checkedOutAt;
    @Column(name = "effective_checked_out_at")
    private Instant effectiveCheckedOutAt;
    @Column(name = "billed_minutes", nullable = false, columnDefinition = INT_UNSIGNED)
    private long billedMinutes;
    @Column(name = "billed_krw", nullable = false)
    private long billedKrw;
    @Column(name = "overage_minutes", nullable = false, columnDefinition = INT_UNSIGNED)
    private long overageMinutes;
    @Column(name = "overage_krw", nullable = false)
    private long overageKrw;
    @Column(name = "exception_actor_id", updatable = false)
    private Long exceptionActorId;
    @Column(name = "exception_reason", length = 500, updatable = false)
    @SemanticField(id = "seat_session.exception_reason", name = "예외 사유", exposed = false)
    private String exceptionReason;
    @Column(name = "start_request_key", nullable = false, length = 120, updatable = false)
    private String startRequestKey;
    @Column(name = "end_request_key", length = 120)
    @SemanticField(id = "seat_session.end_reason", name = "종료 구분", role = "CATEGORY", allowedOps = {"EQ", "NE", "IN", "IS_NULL"})
    private String endRequestKey;
    @Version
    private long version;

    /** Both check-in columns take the same server instant; corrections are out of scope (seat-usage-api.md). */
    public static SeatSession start(long customerId, long passId, long branchId, long seatId, Instant checkedInAt, long actor,
            String reason, String startRequestKey) {
        var session = new SeatSession();
        session.customerId = customerId; session.passId = passId; session.branchId = branchId; session.seatId = seatId;
        session.status = Status.ACTIVE; session.checkedInAt = checkedInAt; session.effectiveCheckedInAt = checkedInAt;
        session.exceptionActorId = actor; session.exceptionReason = reason; session.startRequestKey = startRequestKey;
        return session;
    }

    /** Only the seat changes; the stay, the pass and the check-in time go on (#63). */
    public void moveTo(long seatId) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Seat session already closed");
        this.seatId = seatId;
    }

    /** billed_krw holds what was really deducted; a time pass's minutes follow from billed minutes minus overage. */
    public void close(Instant checkedOutAt, long usedMinutes, long deductedKrw, long overageMinutes, long overageKrw,
            String endRequestKey) {
        if (status != Status.ACTIVE) throw new IllegalStateException("Seat session already closed");
        status = Status.CLOSED;
        this.checkedOutAt = checkedOutAt; effectiveCheckedOutAt = checkedOutAt;
        billedMinutes = usedMinutes; billedKrw = deductedKrw;
        this.overageMinutes = overageMinutes; this.overageKrw = overageKrw;
        this.endRequestKey = endRequestKey;
    }
}
