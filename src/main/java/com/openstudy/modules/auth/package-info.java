/**
 * 직원 인증과 세션.
 * <p>로그인·로그아웃, 자격 증명 검증, JDBC 세션, CSRF, 60분/12시간 만료. 프로필 저장은 employee의 공개 계약을 사용한다.</p>
 * <p>요구사항: OS-AUTH-001~006. WBS: W07, W08.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. 기본 이메일 로그인·세션·CSRF를 구현하며 프로필·초대는 후속 범위다.</p>
 */
package com.openstudy.modules.auth;
