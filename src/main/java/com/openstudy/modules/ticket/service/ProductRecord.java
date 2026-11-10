package com.openstudy.modules.ticket.service;

import java.time.Instant;
import java.util.List;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;

/** 저장된 상품 전체 상태. {@code dbVersion}은 API 버전보다 1 작다. 적용 지점은 권한 필터 전의 전체 목록이다. */
record ProductRecord(long id, long dbVersion, Instant createdAt, Instant updatedAt, String code, String name,
        long priceKrw, ProductTerms terms, boolean onSale, ProductCategory category, List<Long> branchIds, Sales sales) {
    /** The optional sale period and limit (M13-D06); every null means no restriction. */
    record Sales(Instant startsAt, Instant endsAt, Integer limit) {
        static final Sales NONE = new Sales(null, null, null);
    }

    public long categoryId() { return Long.parseLong(category.id()); }
}
