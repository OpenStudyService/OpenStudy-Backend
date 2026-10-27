# 2026-10-28 JPA 전환 6-2단계: 초대 관리·수락

관련: [6-1단계 기록](2026-10-28-jpa-migration-phase6-1-invitation-delivery.md), [5-3단계 기록](2026-10-28-jpa-migration-phase5-3-employee-accounts.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택).

## 1. 배경 / 문제

6단계의 마지막 PR이다. `InvitationManagementRepository`(초대 생성·재발송·취소·거절·수락·승인, 조회, 멱등)는 JDBC SQL로 구현되어 있었다.

옮기기 전에 확인한 기존 동작은 다음과 같다.

- 모든 전이는 조건 없는 UPDATE로 `version+1`을 한다. 수락은 `status='PENDING'`일 때만, 승인은 `status='ACCEPTED'`일 때만 바뀐다.
- 취소·거절·수락·승인 시각과 새 발송 작업의 `next_attempt_at`은 DB의 `UTC_TIMESTAMP(6)`이다.
- 관리 쪽의 작업 정리는 열린 작업을 SKIPPED로 바꾸되 `last_error_code`를 건드리지 않는다. 워커의 만료와 다르다.
- 잠금 조회는 초대 `FOR UPDATE` → 초대 권한(권한 정의 조인) `FOR SHARE` → 현재 revision 작업 `FOR SHARE` 순서다.
- 링크 조회는 지점과 조인해 지점 이름을 가져온다.
- 초대 권한 테이블은 authorization 소유의 권한 정의와 조인한다(JPA-03 경계).

## 2. 검토한 선택지

- **전이 방식**: 6-1과 같은 이유로, 기존 UPDATE와 1:1인 JPQL UPDATE를 택했다. 새 발송 작업도 DB 시각을 쓰므로 HQL INSERT로 넣는다.
- **초대 권한**
  - 권한 정의 엔티티를 참조: JPA-03에 어긋난다.
  - 권한 ID 집합(`@ElementCollection`) + authorization 공개 서비스로 코드 ↔ ID 변환: 이 방식을 택했고 `PermissionCatalog`를 추가했다.
- **지점 이름**: 2단계처럼 `SessionBranchService.names`로 받는다. 기존 조인은 잠금 조회 때 지점 행도 `FOR UPDATE`로 잠갔지만, 그 호출부(`publicCommand`)는 이미 모든 지점 행을 잠근 상태라 실제 잠금 범위는 같다.
- **권한 정의 행의 잠금**: 기존 잠금 조회는 조인한 권한 정의 행도 `FOR SHARE`로 잠갔다. 새 코드는 초대 권한 행만 잠근다. 권한 정의는 마이그레이션·시드로만 바뀌는 사전이라 앱 안에서 경합할 쓰기가 없다.

## 3. 결정 및 이유

- `InvitationManagementRepository` 클래스와 공개 API(`Stored`, `Link`, 메서드)는 그대로 두고, 내부만 6-1의 `InvitationRepository`·`InvitationSendJobRepository`와 `PermissionCatalog`·`SessionBranchService`로 바꿨다. 서비스 코드는 바꾸지 않았다.
- 모든 전이 쿼리는 `flushAutomatically`·`clearAutomatically`로 실행한다. 수락·승인 흐름에서 앞서 바뀐 소속·직원 엔티티는 전이 직전에 flush되고, 이후에는 새로 조회한 값만 쓴다.
- **중복 키 예외 타입(5-3 회귀 포함)**
  - JDBC는 UNIQUE 위반을 `DuplicateKeyException`으로 냈지만, JPA flush는 같은 MySQL 오류를 `DataIntegrityViolationException`으로 낸다.
  - 이 단계의 동등성 테스트에서 이를 발견했다. 그 결과 다음 두 곳이 기존 응답(409)이 아닌 500을 반환하게 되어 있었다.
    - 5-3에서 전환한 초대 계정 생성: 수락 서비스의 "이미 가입된 이메일" 처리
    - 이번에 전환한 초대 생성: 관리 서비스의 `DUPLICATE_INVITATION` 처리
  - 두 경로 모두 앞선 잠금 조회 때문에 운영에서 도달하기는 어렵지만, 방어 동작이 바뀐 것이므로 고쳤다.
  - `common/persistence/DuplicateKeys`를 추가했다. MySQL 오류 1062(ER_DUP_ENTRY)만 `DuplicateKeyException`으로 좁히고, 다른 무결성 오류는 그대로 둔다.
- 비잠금 조회의 초대 권한은 지연 컬렉션 대신 JPQL로 읽는다. 일부 서비스가 트랜잭션 밖에서 조회해, 처음 구현에서 `LazyInitializationException`이 났기 때문이다.

## 4. 구현 내용

- `authorization/service/PermissionCatalog`(신규, 공개): `ids(codes)`(없는 코드 제외), `codes(ids)`(코드 순 정렬).
- `invitation/entity/EmployeeInvitation`: 초대 권한 ID 컬렉션과 생성 팩터리 `create`를 추가하고, 읽기 전용 주석을 갱신했다.
- `invitation/repository/InvitationRepository`: ID 목록, 활성 초대 잠금 조회, 토큰 조회, 권한 ID 조회(일반·`FOR SHARE` native), 전이 JPQL 6개(관리자 만료, 재발송, 취소, 거절, 수락, 승인).
- `invitation/repository/InvitationSendJobRepository`: revision별 작업 조회(일반·`PESSIMISTIC_READ`), HQL INSERT `enqueue`, 오류 코드를 남기는 작업 정리 `skipOpenJobs`.
- `invitation/repository/InvitationManagementRepository`: `JdbcTemplate` 제거.
- `common/persistence/DuplicateKeys`(신규). 적용 위치는 초대 생성과 `EmployeeAccountService.createInvitedAccount`.
- 신규 `InvitationManagementJpaTests`(5개)
  - 생성 행(revision 1, version 0, 작성자, 없는 권한 코드 제외, 첫 작업)과 조회 결과(일반·잠금·만료 표시)가 삭제한 기존 SQL 결과와 같은지
  - 재발송·취소·거절 후 행(revision·version·처리자·시각, 이전 작업의 오류 코드 유지)
  - 수락·승인의 상태 조건(맞지 않으면 변화 없음), 토큰으로 링크 조회와 지점 이름
  - 초대 계정 중복 이메일이 `DuplicateKeyException`인지
  - 중복 활성 초대는 `DuplicateKeyException`, CHECK 위반은 일반 무결성 오류로 남는지

## 5. 검증 결과

- 첫 집중 실행에서 초대 테스트 17건이 **실패**했다. 원인은 트랜잭션 밖 조회에서 초대 권한 지연 컬렉션을 읽은 것(`LazyInitializationException`)이었다. 예외 처리기에 원인 로그를 임시로 넣어 찾았고, 확인 후 되돌렸다. JPQL 조회로 고친 뒤 149개가 통과했다. 실패 실행은 성공으로 집계하지 않는다.
- 신규 테스트 첫 실행에서 중복 이메일 검사 1건이 **실패**했다(위 5-3 회귀). 수정 후 5개 모두 통과했다.
- 세션 경계 테스트에서 서버 로그 `HttpMediaTypeNotAcceptableException` 1건이 보인다. 6-1 상태(현재 main과 같음)에서도 같은 1건이 나오는 기존 동작으로, 이번 변경과 무관하다. 테스트는 통과한다.
- 운영 코드에서 `JdbcTemplate`·`prepareStatement`가 남은 곳은 `OwnerBootstrap`·`LocalPreviewConfiguration`뿐이다. 업무 모듈(`modules/`)에는 없다.
- 전체 회귀: `test` **249개/37개 suite**(6-1 병합 후 244개 + 신규 5개), 실패·오류·건너뜀 0, 5분51초 BUILD SUCCESSFUL. 서버 오류 로그는 위의 기존 `HttpMediaTypeNotAcceptableException` 1건과, API 계약 테스트가 500 응답 마스킹을 확인하려고 일부러 던지는 `IllegalStateException` 1건뿐이다.
- 실제 서버 기동·메일 수신·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 7단계
  - `OwnerBootstrap`·`LocalPreviewConfiguration` 정리
  - 모듈 코드의 `JdbcTemplate` 금지 아키텍처 규칙
  - 설계 문서(디렉토리 구조·결정 기록) 갱신
- 전이 쿼리의 `clearAutomatically`는 영속성 컨텍스트 전체를 비운다. 같은 트랜잭션에서 그 뒤에 이전 엔티티를 수정해도 반영되지 않으므로, 전이 이후에는 새로 조회해서 써야 한다. 현재 흐름은 이 규칙을 지킨다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
