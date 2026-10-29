/**
 * Mock 결제와 환불.
 * <p>Mock 승인/실패·거래·미사용 전액 환불·원거래/환불 원장. 구매 지점·환불 처리 지점 보존. 실제 PG와 부분 환불은 범위 밖이다.</p>
 * <p>요구사항: OS-PAY-001~007. WBS: W21, W22.</p>
 * <p>구현 범위: 이용권 Mock 결제 생성·목록·상세(#57). 환불은 #58, 스터디룸·사물함 결제는 M6에서 구현한다.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다.</p>
 */
package com.openstudy.modules.payment;
