/**
 * 전사 고객 원장.
 * <p>전 지점 공통 고객 등록·검색·상태·응대 이력. 직원 계정과 분리하고 고객 상세에서 결제 정보를 별도 권한 없이 노출하지 않는다.</p>
 * <p>요구사항: OS-MEM-001~005. WBS: W17, W18.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. 현재 파일은 구조 설명이며 기능 구현이 아니다.</p>
 */
package com.openstudy.modules.customer;
