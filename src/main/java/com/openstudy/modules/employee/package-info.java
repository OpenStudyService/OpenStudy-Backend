/**
 * 직원과 프로필.
 * <p>직원 계정·재직 상태·닉네임·사진·직원 version. 이메일 ID 불변, 프로필과 직원 정보의 version 공유. 응대 고객 이력은 customer와의 권한 계약으로 제공한다.</p>
 * <p>요구사항: OS-EMP-001, OS-EMP-006, OS-AUTH-005, OS-SET-001. WBS: W07~W10, W39.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. EmployeeAccountService는 인증용 계정·소속 조회와 로컬 최초 사장님 생성 계약을 공개한다. 직원 관리·프로필 수정은 후속 범위다.</p>
 */
package com.openstudy.modules.employee;
