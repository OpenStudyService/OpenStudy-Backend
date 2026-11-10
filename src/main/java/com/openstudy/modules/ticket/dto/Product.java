package com.openstudy.modules.ticket.dto;

import java.util.List;

/**
 * {@code branchIds}는 현재 사용자가 볼 수 있는 적용 지점만이며 {@code canEdit}은 서버 검증을 대체하지 않는다.
 * 판매 기간·한도와 판매 수·남은 수량·판매 가능 여부는 M13(#113, M13-D02·D06)이다.
 */
public record Product(String id, long version, String createdAt, String updatedAt, String code, String name,
        long priceKrw, ProductTerms terms, String status, ProductCategory category, List<String> branchIds,
        boolean canEdit, String salesStartsAt, String salesEndsAt, Integer salesLimit, long soldQuantity,
        Long remainingQuantity, String saleAvailability) {}
