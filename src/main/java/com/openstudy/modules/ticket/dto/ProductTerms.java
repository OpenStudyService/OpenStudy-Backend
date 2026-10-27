package com.openstudy.modules.ticket.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 유형별 상품 조건. {@code kind}는 API의 한국어 유형명이며 유형마다 허용 필드가 다르다(Q2: 기간권은 일수만 저장).
 * 유효기간이 없는 시간권·정액권은 {@code validDays=null}이며 0으로 대신하지 않는다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({@JsonSubTypes.Type(ProductTerms.Time.class), @JsonSubTypes.Type(ProductTerms.Period.class),
        @JsonSubTypes.Type(ProductTerms.StoredValue.class)})
public sealed interface ProductTerms {
    String TIME = "시간권";
    String PERIOD = "기간권";
    String STORED_VALUE = "정액권";

    String kind();

    record Time(String kind, long includedMinutes, Long validDays) implements ProductTerms {
        public Time { requireKind(TIME, kind); }
        public static Time of(long includedMinutes, Long validDays) { return new Time(TIME, includedMinutes, validDays); }
    }

    record Period(String kind, long periodDays) implements ProductTerms {
        public Period { requireKind(PERIOD, kind); }
        public static Period of(long periodDays) { return new Period(PERIOD, periodDays); }
    }

    record StoredValue(String kind, long creditKrw, long hourlyRateKrw, Long validDays) implements ProductTerms {
        public StoredValue { requireKind(STORED_VALUE, kind); }
        public static StoredValue of(long creditKrw, long hourlyRateKrw, Long validDays) {
            return new StoredValue(STORED_VALUE, creditKrw, hourlyRateKrw, validDays);
        }
    }

    private static void requireKind(String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("Unexpected product kind");
    }
}
