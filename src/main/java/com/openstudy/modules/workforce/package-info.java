/**
 * 근무와 근태.
 * <p>근무 계획·실제 출퇴근/휴게·정정 승인·휴가 부여/사용/복원·대체근무. 전 지점 직원 시간 충돌과 직원/연도 휴가 잔량을 한 경계 안에서 관리한다.</p>
 * <p>요구사항: OS-SHIFT-001~004, OS-ATT-001~006, OS-LEAVE-001~005, OS-SWAP-001~003. WBS: W33~W36.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. 현재 파일은 구조 설명이며 기능 구현이 아니다.</p>
 */
package com.openstudy.modules.workforce;
