package com.openstudy.modules.ticket.service;

import java.time.Instant;
import java.util.List;
import com.openstudy.modules.ticket.dto.ProductCategory;
import com.openstudy.modules.ticket.dto.ProductTerms;

/** 저장된 상품 전체 상태. {@code dbVersion}은 API 버전보다 1 작다. 적용 지점은 권한 필터 전의 전체 목록이다. */
record ProductRecord(long id, long dbVersion, Instant createdAt, Instant updatedAt, String code, String name,
        long priceKrw, ProductTerms terms, boolean onSale, ProductCategory category, List<Long> branchIds) {
    public long categoryId() { return Long.parseLong(category.id()); }
}
