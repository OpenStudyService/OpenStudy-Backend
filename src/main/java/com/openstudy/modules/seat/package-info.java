/**
 * 일반 좌석과 이용.
 * <p>현장 좌석·활성 이용 세션·이동·종료·직원 예외 지원·시간 정정. 차감/보정은 ticket과 같은 트랜잭션에서 반영한다. 고객 QR 실제 연동은 후속이다.</p>
 * <p>요구사항: OS-SEAT-001~005. WBS: W23, W24.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. 현재 파일은 구조 설명이며 기능 구현이 아니다.</p>
 */
package com.openstudy.modules.seat;
