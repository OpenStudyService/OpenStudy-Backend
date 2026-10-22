/**
 * 지점 권한과 프리셋.
 * <p>현재 DB의 지점 소속·역할·행동/필드 권한, 한 지점씩 저장, 복사형 프리셋. 일반 권한 변경과 계정 제한에 따른 회수 정책을 구분한다.</p>
 * <p>요구사항: OS-EMP-002~005. WBS: W09, W10.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다. 현재 파일은 구조 설명이며 기능 구현이 아니다.</p>
 */
package com.openstudy.modules.authorization;
