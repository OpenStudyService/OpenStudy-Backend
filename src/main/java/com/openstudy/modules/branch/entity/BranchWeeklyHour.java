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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Minutes past local midnight; closes beyond 1440 run into the next day (CHECK ck_branch_weekly_hours_2). */
@Entity
@Table(name = "branch_weekly_hours")
public class BranchWeeklyHour {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;
    @JdbcTypeCode(SqlTypes.TINYINT) @Column(nullable = false)
    private int weekday;
    @Column(name = "is_closed", nullable = false)
    private boolean closed;
    @JdbcTypeCode(SqlTypes.SMALLINT) @Column(name = "opens_minute")
    private Integer opensMinute;
    @JdbcTypeCode(SqlTypes.SMALLINT) @Column(name = "closes_minute")
    private Integer closesMinute;

    protected BranchWeeklyHour() {}

    BranchWeeklyHour(Branch branch, int weekday, boolean closed, Integer opensMinute, Integer closesMinute) {
        this.branch = branch; this.weekday = weekday; this.closed = closed;
        this.opensMinute = opensMinute; this.closesMinute = closesMinute;
    }

    public Long getId() { return id; }
    public int getWeekday() { return weekday; }
    public boolean isClosed() { return closed; }
    public Integer getOpensMinute() { return opensMinute; }
    public Integer getClosesMinute() { return closesMinute; }
}
