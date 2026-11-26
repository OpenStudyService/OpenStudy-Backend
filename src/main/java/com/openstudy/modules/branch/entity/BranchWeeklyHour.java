package com.openstudy.modules.branch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Minutes past local midnight; closes beyond 1440 run into the next day (CHECK ck_branch_weekly_hours_2). */
@Entity
@Table(name = "branch_weekly_hours")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchWeeklyHour {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    @Getter
    private int weekday;

    @Column(name = "is_closed", nullable = false)
    @Getter
    private boolean closed;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "opens_minute")
    @Getter
    private Integer opensMinute;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "closes_minute")
    @Getter
    private Integer closesMinute;

    BranchWeeklyHour(Branch branch, int weekday, boolean closed, Integer opensMinute, Integer closesMinute) {
        this.branch = branch;
        this.weekday = weekday;
        this.closed = closed;
        this.opensMinute = opensMinute;
        this.closesMinute = closesMinute;
    }

    void change(boolean closed, Integer opensMinute, Integer closesMinute) {
        this.closed = closed;
        this.opensMinute = opensMinute;
        this.closesMinute = closesMinute;
    }
}
