# 2026-10-27 JPA 전환 2단계: 단순 조회 저장소 전환

관련: [1단계 기록](2026-10-27-jpa-migration-phase1-shared-records.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

계획의 2단계 대상은 조회만 하는 저장소 4개였다. 이 중 `LoginAuditRepository`와 `WorkspaceOutboxRepository`는 1단계에서 공통 컴포넌트로 옮겼다. 그래서 이번 범위는 나머지 두 개다.

- `SessionBranchRepository`: 로그인 사용자가 접근할 수 있는 지점 목록(`/api/me`의 `branches`).
- `ApprovalStatusRepository`: 초대를 수락한 직원의 승인 진행 상태(`/api/invitations/approval-status`).

두 저장소 모두 다른 모듈의 테이블을 SQL로 함께 읽고 있었다. 지점 조회는 소속(`branch_memberships`, JPA-03에 따라 authorization 소유)을, 승인 상태는 지점(`branches`, branch 소유)을 읽었다. JPA로 옮기면서 다른 모듈의 엔티티를 직접 참조하지 않는 방법이 필요했다.

## 2. 검토한 선택지

**접근 가능 지점**
- 소속 엔티티를 branch 모듈에 매핑: JPA-03(소속 테이블은 authorization 소유)에 어긋난다.
- authorization의 공개 service로 소속 지점 ID를 받은 뒤 지점 조회: 쿼리가 둘로 나뉘고, branch → authorization 호출 계약을 새로 만들어야 한다.
- 지점 엔티티를 반환하는 native query 하나로 소속 EXISTS 판정 유지: JPA-01 A가 허용한 "EXISTS 권한 판정"에 해당하고, 기존과 같은 SQL 한 문장을 유지한다.

**승인 상태**
- native JOIN을 유지: JPA-01이 허용한 범주(잠금 순서·권한 판정·집계)에 들지 않는 단순 표시용 조인이다.
- `BranchService`의 지점 조회 사용: 요청자 권한 검사를 거친다. 승인 대기 중인 직원은 지점 권한이 없어 사용할 수 없다.
- 초대 엔티티로 조회한 뒤, 지점 이름은 branch 모듈의 인증 흐름용 공개 계약(`SessionBranchService`)에서 받기.

## 3. 결정 및 이유

- 접근 가능 지점은 native EXISTS 조회로 `Branch` 엔티티를 받고, 서비스에서 DTO로 바꾼다. 기존 SQL과 같은 한 문장이고 모듈 경계도 지킨다.
- 승인 상태는 `EmployeeInvitation` 엔티티를 Spring Data 파생 쿼리로 조회한다. 지점 이름은 `SessionBranchService.names`로 받는다.
  - 조인 1회가 쿼리 2회로 바뀐다. 두 쿼리는 같은 읽기 트랜잭션 안에서 실행된다.
  - `employee_invitations.branch_id`는 FK이므로 조인에서 빠지는 행이 원래 없었다.
  - 새 모듈 의존 `invitation → branch`가 생긴다. 기존 의존(`branch → authorization`, `invitation → auth·authorization·employee`)과 순환하지 않는다.
- `EmployeeInvitation`은 이번 단계에서 전체 컬럼을 매핑하되 읽기 전용으로 둔다(setter 없음). 6단계에서 초대 쓰기를 옮길 때의 매핑 위험을 미리 `validate`로 확인하려는 것이다. 초대 명령은 version 충돌을 409로 거절하므로(`ApiVersion.requireCurrent`) JPA-02 A에 따라 `@Version`을 둔다.

## 4. 구현 내용

- `branch/repository/SessionBranchRepository`: JDBC 클래스 → Spring Data 인터페이스(`Repository<Branch, Long>`).
  - `findAccessible`: native EXISTS 조회.
  - `findByIdIn`: 지점 이름 조회용.
- `branch/service/SessionBranchService`: 엔티티 → `SessionBranch` DTO 변환을 서비스로 옮기고, 공개 계약 `names(Collection<Long>)`를 추가했다. `branch/package-info.java`의 공개 계약 설명도 갱신했다.
- `invitation/entity`: `EmployeeInvitation`, `InvitationStatus`, `InitialRole`. `.gitkeep`은 제거했다.
  - 생성 컬럼 `active_invitation_slot`은 매핑하지 않는다.
  - `token_hash`는 `BINARY(32)` 매핑이다.
  - 처리자 컬럼은 다른 모듈 엔티티 대신 직원 ID(`Long`)로 둔다.
- `invitation/repository/ApprovalStatusRepository`: JDBC 클래스 → Spring Data 인터페이스. 상태 → 화면 문구 변환은 `ApprovalStatusService`로 옮겼다.
- 신규 `JpaSimpleReadsTests`(2개): 같은 DB 상태에서 **삭제한 기존 SQL과 행 변환 코드를 그대로 실행한 결과**를 기대값으로 삼아 새 경로의 결과와 `equals`로 비교한다.
  - 지점: 소유자/일반, 소속 ACTIVE·REVOKED·PENDING, 지점 운영 중지, 전화번호 없음, version 증가.
  - 초대: ACCEPTED·APPROVED·REJECTED(사유 포함)·PENDING·CANCELLED, 다른 직원의 초대.

## 5. 검증 결과

- 집중 실행: `JpaSimpleReadsTests` 2개, `JpaMappingTests` 3개, `ModuleBoundaryTests` 1개 통과. `EmployeeInvitation` 매핑이 `validate`를 통과했다.
- 기존 테스트가 승인 상태의 `status`와 `requiresLogin`만 확인하고, 지점 이름·지점 ID·거절 사유·수정 시각·정렬 순서는 확인하지 않는다는 점을 확인했다. 이 공백을 위의 동등성 테스트로 보완했다.
- 전체 회귀: `test` **220개/30개 suite**(1단계 병합 후 218개 + 신규 2개), 실패·오류·건너뜀 0, 5분41초 BUILD SUCCESSFUL. 기존 테스트는 수정하지 않았다.

## 6. 남은 작업 / 보류 사항

- `EmployeeInvitation`은 아직 읽기 전용이다. 초대 생성·수락·승인·발송 쓰기는 6단계에서 JPA로 옮긴다.
- 3단계에서 `BranchRepository`(지점 관리)를 JPA로 옮길 때, `SessionBranchRepository`와 지점 조회 인터페이스를 하나로 합칠지 정한다.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
