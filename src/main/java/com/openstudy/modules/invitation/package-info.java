/**
 * 직원 초대와 승인.
 * <p>초대·수락·최종 승인과 발송 revision. 매 발송/재시도 새 토큰, 최초 24시간 유지. DB/outbox에 원문 또는 복호화 가능한 토큰을 저장하지 않는다.</p>
 * <p>요구사항: OS-INV-001~006. WBS: W11, W12.</p>
 * <p>관리 목록·상세·생성·재발급·취소·반려와 예정 권한·발송 작업을 제공한다. SMTP worker는 lease와 revision으로 재시도하며 공개 수락·최종 승인은 후속 범위다.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다.</p>
 */
package com.openstudy.modules.invitation;
