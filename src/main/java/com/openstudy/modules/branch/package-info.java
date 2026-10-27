/**
 * 지점.
 * <p>지점 정보·운영시간·운영 상태. 자원의 상세 상태는 각 자원 모듈이 소유한다.</p>
 * <p>요구사항: OS-BR-001~004. WBS: W09, W10.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. SessionBranchService는 인증 응답에 필요한 접근 가능 지점 조회와, 지점 권한이 아직 없는 승인 상태 화면용 지점 이름 조회 계약을 공개한다. 지점 관리·운영시간 API는 후속 범위다.</p>
 */
package com.openstudy.modules.branch;
