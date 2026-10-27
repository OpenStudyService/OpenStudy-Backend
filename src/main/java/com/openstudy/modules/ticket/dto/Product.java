package com.openstudy.modules.ticket.dto;

import java.util.List;

/** {@code branchIds}는 현재 사용자가 볼 수 있는 적용 지점만이며 {@code canEdit}은 서버 검증을 대체하지 않는다. */
public record Product(String id, long version, String createdAt, String updatedAt, String code, String name,
        long priceKrw, ProductTerms terms, String status, ProductCategory category, List<String> branchIds,
        boolean canEdit) {}
