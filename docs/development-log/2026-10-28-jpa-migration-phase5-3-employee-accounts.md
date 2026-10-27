# 2026-10-28 JPA 전환 5-3단계: 직원 계정

관련: [5-2단계 기록](2026-10-28-jpa-migration-phase5-2-memberships.md), [0단계 기록](2026-10-27-jpa-migration-phase0.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

5단계의 마지막 PR이다. 직원 계정은 `EmployeeAccountRepository`와 `EmployeeManagementRepository`의 JDBC SQL로 구현되어 있었다. 5-2에서 소속 처리를 authorization으로 옮긴 뒤 이 두 저장소에는 직원 행만 남았고, 둘 다 employee 모듈 안에서만 쓰인다.

옮기기 전에 확인한 기존 동작은 다음과 같다.

- 계정 변경(닉네임, 관리 수정, 소속 변경 반영)은 값이 같아도 항상 `version=version+1`이었다.
- 승인 시 계정 활성화는 `WHERE status IN ('PENDING','ACTIVE')` 조건부 UPDATE였다. 이용 제한·퇴사 계정은 상태와 version이 모두 그대로였다.
- 로그인 기록은 `last_login_at=UTC_TIMESTAMP(6)`만 바꾸고 version은 올리지 않았다.
- 비활성화(활성 외 상태로 변경) 시 같은 트랜잭션에서 `SPRING_SESSION` 행을 직접 삭제했다.
- 직원 선택 목록(`lookup`)과 지점 관련 여부(`relatedToBranches`)는 소속 EXISTS를 SQL로 판정했다.
- 신규 행의 `created_by`/`updated_by`는 비워 두었다.

## 2. 검토한 선택지

- **로그인 기록**: 엔티티 필드 변경은 `@Version`을 올려, 로그인할 때마다 클라이언트의 version이 낡게 된다. version을 건드리지 않는 JPQL 일괄 UPDATE로 유지했다.
- **세션 삭제**
  - Spring Session의 삭제 API: 별도 트랜잭션(`REQUIRES_NEW`)으로 커밋하므로, 직원 상태 변경이 롤백돼도 세션 삭제는 남는다.
  - 같은 트랜잭션의 native DELETE: 기존 동작과 같다. 이 방식을 택했다(JPA-01 예외).
- **소속 EXISTS 판정**: 소속 엔티티는 authorization 소유(JPA-03)다. 2단계의 지점 조회와 같은 이유로 native query로 유지했다.
- **저장소 구조**: 두 저장소를 엔티티 하나에 대한 Spring Data 저장소 하나(`EmployeeRepository`)로 합쳤다. 감사·이벤트 기록과 표시 코드 변환은 저장소가 아니므로 서비스 패키지의 `EmployeeChanges`로 옮겼다.

## 3. 결정 및 이유

- 0단계의 `Employee` 엔티티(`@Version`, JPA-02)에 변경 메서드를 추가했다: `localOwner`, `changeAccount`, `touch`, `approveInvited`.
- version은 3~5-2단계와 같은 방식이다. flush 후 그대로면 JPQL로 정확히 1 올리고 `refresh`한다(`EmployeeChanges.saved`).
- 승인 활성화는 `approveInvited`가 PENDING·ACTIVE일 때만 바꾸고 `true`를 반환한다. 그 외 상태는 아무것도 바꾸지 않아 version도 그대로다. 기존 조건부 UPDATE가 0행을 갱신하던 것과 같다.
- 신규 행의 `created_by`/`updated_by`는 기존처럼 비워 둔다.
- 서비스 공개 메서드는 그대로라서, 이를 호출하는 인증·초대·워크스페이스 코드는 바뀌지 않는다.

## 4. 구현 내용

- `employee/repository`: `EmployeeAccountRepository`, `EmployeeManagementRepository` 삭제 → `EmployeeRepository`(Spring Data).
  - 잠금 조회 2종(ID·이메일)
  - native 3개: 선택 목록, 지점 관련 여부, 세션 삭제
  - JPQL 2개: 로그인 기록, version 증가
- `employee/service/EmployeeChanges`(신규, 패키지 내부): version 1회 증가, 감사·이벤트 기록, 엔티티 → `EmployeeAccount` 변환, 상태 표시 문구.
- `EmployeeAccountService`, `EmployeeManagementService`: 엔티티 기반으로 전환. 잠금 순서(직원 행 → 소속 → 권한)와 실행 순서(소속 처리 → 직원 행 갱신, 직원 UPDATE → 세션 삭제)는 그대로다.
- 신규 `EmployeeJpaBehaviorTests`(5개)
  - ID·이메일(대소문자 무시) 조회, 선택 목록, 지점 관련 여부가 삭제한 기존 SQL 결과와 같은지
  - 로그인 기록은 version을 올리지 않고, 같은 닉네임 저장은 version +1, `updated_by`=본인
  - 이용 제한 계정은 승인돼도 상태·version이 그대로인지
  - 비활성화 시 같은 트랜잭션에서 세션 행이 삭제되고 version +1

## 5. 검증 결과

- 집중 실행: employee·invitation·authorization·auth·workspace·integration·architecture 패키지 196개와 신규 5개가 통과했다. 기존 테스트는 수정하지 않았다.
- 운영 코드에서 `JdbcTemplate`·`prepareStatement`가 남은 곳은 초대 저장소 2개(6단계 대상)와 `OwnerBootstrap`·`LocalPreviewConfiguration`(7단계 대상)뿐이다. 직원·소속 행을 JDBC로 쓰는 운영 코드는 없다.
- 전체 회귀: `test` **241개/35개 suite**(5-2 병합 후 236개 + 신규 5개), 실패·오류·건너뜀 0, 5분29초 BUILD SUCCESSFUL.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 6단계: 초대 모듈(관리·수락·발송) 전환. 2단계에서 만든 읽기 전용 `EmployeeInvitation` 엔티티를 쓰기에 사용한다.
- 7단계: `OwnerBootstrap`·`LocalPreviewConfiguration` 정리, 모듈 코드의 `JdbcTemplate` 금지 규칙, 설계 문서 갱신.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
