/**
 * 지점 권한과 프리셋.
 * <p>현재 DB의 지점 소속·역할·행동/필드 권한, 한 지점씩 저장, 복사형 프리셋. 일반 권한 변경과 계정 제한에 따른 회수 정책을 구분한다.</p>
 * <p>요구사항: OS-EMP-002~005. WBS: W09, W10.</p>
 * <p>현재 DB projection의 역할·행위·범위 정책과 동일 트랜잭션 쓰기 가드를 제공한다.
 * 모듈 간 접근은 공개 service·DTO만 허용하며 다른 모듈 entity·repository에 접근하지 않는다.
 * 상세 통합 규칙은 docs/architecture/authorization-policies.md를 따른다.</p>
 */
package com.openstudy.modules.authorization;
