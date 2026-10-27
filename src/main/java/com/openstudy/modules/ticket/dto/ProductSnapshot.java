package com.openstudy.modules.ticket.dto;

import java.util.List;

/** 변경 이력과 구매 시점 조건 보존에 쓰는 불변 스냅샷. 분류는 이름 변경에 영향받지 않도록 값을 복사한다. */
public record ProductSnapshot(int schemaVersion, String code, String name, long priceKrw, ProductTerms terms,
        String status, String categoryCode, String categoryName, List<String> branchIds) {
    public static final int SCHEMA_VERSION = 1;
}
