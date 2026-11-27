package com.openstudy.modules.seat.entity;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One seat occupancy sample (view {@code seat_occupancy_samples}, V20): how many seats of a branch were in use at a
 * sampled hour (09/12/15/18/21 Asia/Seoul) and how many seats it has. Read-only. It lets the AI generation path ask for
 * congestion by weekday and hour as an ordinary aggregate, with the same definition as registered analysis POC-002.
 */
@Entity
@Immutable
@Table(name = "seat_occupancy_samples")
@SemanticEntity(
        id = "seat_occupancy_sample",
        name = "좌석 점유 샘플",
        description = "지점별 9·12·15·18·21시에 이용 중이던 좌석 수와 가용 좌석 수. 요일·시간대 혼잡도의 원천",
        aliases = {"좌석 혼잡도", "좌석 점유율", "시간대별 혼잡", "붐비는 시간"},
        domain = "seats"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatOccupancySample {

    @Id
    @Column(length = 64)
    @SemanticField(id = "seat_occupancy_sample.id", name = "샘플 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"}, exposed = false)
    private String id;

    @Column(name = "branch_id", nullable = false)
    @SemanticField(id = "seat_occupancy_sample.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "seat_occupancy_sample.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "샘플 지점")
    private Long branchId;

    @Column(name = "sampled_at", nullable = false)
    @SemanticField(id = "seat_occupancy_sample.sampled_at", name = "샘플 시각", role = "TIME", allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant sampledAt;

    @Column(name = "weekday_kst", nullable = false)
    @SemanticField(id = "seat_occupancy_sample.weekday_kst", name = "요일", description = "월요일 1 ~ 일요일 7 (Asia/Seoul)", role = "CATEGORY",
            aliases = {"요일별", "무슨 요일"}, allowedOps = {"EQ", "NE", "IN"})
    private Long weekdayKst;

    @Column(name = "hour_kst", nullable = false)
    @SemanticField(id = "seat_occupancy_sample.hour_kst", name = "시간대", description = "샘플 시각의 시 (9·12·15·18·21, Asia/Seoul)", role = "CATEGORY",
            aliases = {"시간대별", "몇 시"}, allowedOps = {"EQ", "NE", "IN"})
    private Long hourKst;

    @Column(name = "occupied_seats", nullable = false)
    @SemanticField(id = "seat_occupancy_sample.occupied_seats", name = "이용 중 좌석 수", role = "MEASURE", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE"})
    private Long occupiedSeats;

    @Column(name = "available_seats", nullable = false)
    @SemanticField(id = "seat_occupancy_sample.available_seats", name = "가용 좌석 수", role = "MEASURE", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE"})
    private Long availableSeats;

    @Column(name = "occupancy_pct", precision = 12, scale = 6)
    @SemanticField(id = "seat_occupancy_sample.occupancy_pct", name = "좌석 점유율", role = "MEASURE", unit = "%", allowedOps = {"GT", "GTE", "LT", "LTE"})
    private BigDecimal occupancyPct;
}
