/**
 * 직원 초대와 승인.
 * <p>초대·수락·최종 승인과 발송 revision. 매 발송/재시도 새 토큰, 최초 24시간 유지. DB/outbox에 원문 또는 복호화 가능한 토큰을 저장하지 않는다.</p>
 * <p>요구사항: OS-INV-001~006. WBS: W11, W12.</p>
 * <p>모듈 간 접근은 api 패키지만 허용한다. 현재 파일은 구조 설명이며 기능 구현이 아니다.</p>
 */
package com.openstudy.modules.invitation;
