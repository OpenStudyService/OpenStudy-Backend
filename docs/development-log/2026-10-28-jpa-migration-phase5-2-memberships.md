# 2026-10-28 JPA 전환 5-2단계: 소속·소속 권한을 authorization 모듈로 이관

관련: [5-1단계 기록](2026-10-27-jpa-migration-phase5-1-permission-presets.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

JPA-03 A는 지점 소속(`branch_memberships`)과 소속 권한(`membership_permission_grants`)을 authorization 모듈이 소유한다고 정했다. 그러나 이 테이블은 아래 세 곳에서 SQL로 직접 다뤄지고 있었다.

- employee 모듈의 `EmployeeAccountRepository`: 초대 수락·승인·거절 시 소속 대기 생성·재사용, 활성화, 회수, 현재 사용자의 활성 소속 조회.
- employee 모듈의 `EmployeeManagementRepository`: 직원 관리의 소속 조회(잠금 포함)와 소속 수정.
- authorization 모듈의 `AuthorizationRepository`: 모든 명령이 쓰는 잠금(가드 → 직원 → 지점 → 소속)과 권한 상태 조회(`FOR SHARE`).

옮기기 전에 확인한 기존 동작은 다음과 같다.

- 소속 쓰기는 모두 `version=version+1`이었다. 수정은 값이 같아도 1 증가했다. 소속이 바뀌면 같은 명령에서 직원 행도 `version+1`·`updated_by`를 갱신했다.
- 활성화의 `approved_at`은 DB의 `UTC_TIMESTAMP(6)`이었다. 권한 삽입은 `INSERT ... SELECT`라 없는 코드는 조용히 건너뛰었다.
- 잠금 조회는 직원 행 `FOR UPDATE` → 소속 행 `FOR UPDATE` → 권한 행 `FOR SHARE` 순서였다.
- 소속 version은 직원 관리 화면에 노출되고 409 충돌 검사(`requireCurrent`)에 쓰인다.

## 2. 검토한 선택지

- **소속 처리 위치**
  - employee에 소속 엔티티를 두기: JPA-03과 어긋난다.
  - authorization에 엔티티와 공개 서비스를 두고 employee가 호출하기: 기존 의존 방향(`employee → authorization`)과 같아 순환이 생기지 않는다. 이 방식을 택했다.
- **`AuthorizationRepository`**: 엔티티 조회로 바꾸면 SQL 형태와 잠금 범위가 달라진다. 이 저장소의 쿼리는 모두 잠금 순서이거나 잠금 아래의 권한 판정이므로, JPA-01 예외로 SQL을 그대로 Spring Data native query로 옮겼다. 호출부가 쓰는 메서드 이름은 인터페이스의 default 메서드로 유지했다.
- **version 1회 증가**
  - Hibernate 기본 동작: 소유한 컬렉션(권한)이 바뀌면 version을 올린다. 필드 변경과 겹치거나, 활성화처럼 일괄 UPDATE를 함께 쓰면 증가 횟수가 기존과 달라질 수 있다.
  - 권한 컬렉션을 version 대상에서 제외(`@OptimisticLock(excluded = true)`)하고, 서비스가 명령마다 정확히 1 증가를 보장: 3~5-1단계와 같은 방식이다. 이 방식을 택했다.
- **활성화 시각**: 애플리케이션 시계 / DB 시각. 기존과 같게 DB 시각을 쓰도록 활성화는 JPQL 일괄 UPDATE 한 문장(`cast(sql('UTC_TIMESTAMP(6)') as Instant)`, `version+1`)으로 옮겼다.

## 3. 결정 및 이유

- `authorization/entity/BranchMembership`(`@Version`, JPA-02)과 `MembershipStatus`를 추가했다. 권한은 5-1의 `PermissionDefinition`과 `@ManyToMany`로 연결한다.
- `authorization/service/MembershipService`(공개 서비스, 호출자 트랜잭션 필수)를 추가했다.
  - 메서드: `active`, `all(lock)`, `lock`, `revokePending`, `pend`, `activatePending`, `update`
  - 다른 모듈에는 `MembershipRecord` DTO만 전달한다. 역할·상태는 저장 코드(`MANAGER`, `PENDING` 등) 문자열이다.
- 직원 행 갱신(`version+1`, 활성화 시 계정 상태 변경)은 employee에 남겼다. 5-3에서 전환한다. 서비스가 "소속 처리 → 직원 행 갱신" 순서로 호출하므로 SQL 실행 순서는 기존과 같다.
- `ManagedEmployee` 조립은 저장소에서 `EmployeeManagementService`로 옮겼다. 저장소는 직원 컬럼만 반환한다.
- `EmployeeAccountService`의 공개 메서드는 그대로라서, 이를 호출하는 초대 모듈 코드는 바뀌지 않는다.

## 4. 구현 내용

- authorization
  - `entity/BranchMembership`, `entity/MembershipStatus`
  - `repository/BranchMembershipRepository`: 잠금 조회 3종, 권한 `FOR SHARE` native, 활성화 JPQL, `incrementVersion`
  - `repository/AuthorizationRepository`: JDBC 클래스 → Spring Data 인터페이스. native query 13개, default 메서드로 기존 API(`current`, `currentLocked`, `lockGuard`, `lockEmployees`, `lockBranches`, `lockMemberships`, `activeOwners`, `activeAdministrators`) 유지
  - `service/MembershipService`, `dto/MembershipRecord`
- employee
  - `EmployeeAccountRepository`: 소속 SQL을 제거하고, `lockInvitationAccount`·`touch`·`activateInvitedAccount`로 직원 행만 다룬다.
  - `EmployeeManagementRepository`: 소속 SQL을 제거하고 `account`·`touch`를 둔다.
  - 두 서비스가 `MembershipService`를 호출한다.
- 신규 `MembershipJpaBehaviorTests`(4개)
  - 권한 상태(잠금·비잠금)와 관리자·소유자 수가 삭제한 기존 SQL 결과와 같은지
  - 현재 사용자 소속이 기존 SQL 결과와 같은지
  - 초대 수명주기(대기 생성 → 활성화 → 회수 → 재사용 대기 → 거절) 각 단계에서 소속·직원 version이 정확히 1씩 증가하는지, 승인 시각, 없는 권한 코드 건너뛰기
  - 소속 수정(변경 없음 / 권한만 / 전부)의 version +1

## 5. 검증 결과

- 집중 실행: employee·invitation·authorization·auth·workspace·integration·architecture 패키지 테스트 192개가 통과했다. 기존 테스트는 수정하지 않았다.
- `MembershipJpaBehaviorTests` 첫 실행에서 1건 **실패**했다. 테스트가 같은 트랜잭션에서 JDBC로 소속 상태를 직접 바꿨기 때문에, 영속성 컨텍스트에 남은 엔티티가 그 변경을 보지 못했다. 운영 코드에는 소속 테이블을 JDBC로 쓰는 곳이 더 이상 없으므로(grep 확인), 테스트도 `MembershipService`로 회수하도록 고친 뒤 4개 모두 통과했다. 실패 실행은 성공으로 집계하지 않는다.
- 전체 회귀: `test` **236개/34개 suite**(5-1 병합 후 232개 + 신규 4개), 실패·오류·건너뜀 0, 5분37초 BUILD SUCCESSFUL.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 같은 트랜잭션에서 소속 행을 JPA 밖(JDBC·native UPDATE)으로 바꾸면, 이미 로드된 엔티티가 그 값을 보지 못한다. 앞으로 소속 쓰기는 `MembershipService`로만 해야 한다. 7단계에서 모듈 코드의 `JdbcTemplate` 금지 규칙으로 막는다.
- employee의 직원 조회 2곳(`lookup`, `relatedToBranches`)은 소속 EXISTS를 SQL로 읽는다. 5-3에서 native query로 옮긴다(JPA-01 권한 판정 예외). 지점 모듈의 `findAccessible`은 2단계에서 정한 대로 유지한다.
- 5-3: 직원 계정 전환.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
