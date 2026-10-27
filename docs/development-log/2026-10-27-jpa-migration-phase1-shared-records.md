# 2026-10-27 JPA 전환 1단계: 감사·멱등·outbox 공통 컴포넌트

관련: [0단계 기록](2026-10-27-jpa-migration-phase0.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

JPA-01~04가 모두 A로 확정되었다. JPA-04의 전제(진행 중인 백엔드 작업 브랜치 병합)는 착수 시점에 확인했다. 작업 브랜치 14개 중 12개는 PR 병합 완료였다. `feature/session-boundaries`의 2개 커밋은 같은 변경이 이미 `main`에 있었고(`git cherry` 결과 모두 `-`), `chore/organization-integration-check`는 병합된 브랜치를 합친 병합 커밋만 가지고 있었다.

`audit_events`, `idempotency_records`, `workspace_event_outbox`는 6개 모듈 저장소 8곳이 각자 SQL로 기록하고 있었다. 모듈별 엔티티 전환(2단계 이후) 전에 이 세 테이블의 쓰기 경로를 하나로 모아야 각 모듈 전환에서 같은 SQL을 반복해서 옮기지 않는다.

## 2. 검토한 선택지

- 모듈 저장소의 메서드 시그니처를 유지하고 내부 SQL만 공통 컴포넌트 호출로 바꾸는 방식: 서비스·HTTP 계층을 건드리지 않아 기존 통합 테스트가 그대로 회귀 검증이 된다.
- 서비스가 공통 컴포넌트를 직접 호출하도록 함께 바꾸는 방식: 최종 구조에 더 가깝지만, 이번 단계에서 서비스 코드까지 바뀌어 변경 범위가 커진다. 각 모듈 전환 단계에서 저장소를 정리할 때 함께 처리하는 편이 낫다고 판단했다.

## 3. 결정 및 이유

첫 번째 방식을 선택했다. 위치는 [디렉토리 구조](../architecture/directory-structure.md)가 이미 정한 `common/audit`, `common/idempotency`, `common/outbox`를 따랐다. 공통 패키지는 업무 모듈을 참조하지 않는다.

- **JPA-01 native 예외 1건**: 상품 분류 변경 시 모든 지점에 갱신 이벤트를 쓰는 `INSERT ... SELECT id FROM branches`는 native query로 유지했다. 커밋 시점의 지점 전체에 한 문장으로 쓰는 원자성을 지키려는 것이다. 또 HQL insert-select로 바꾸면 common이 branch 모듈의 엔티티를 참조해야 한다.
- **DB 시각 유지**: outbox 전달 조회·완료 표시는 기존처럼 DB의 `UTC_TIMESTAMP(6)`을 쓴다. 테스트의 `MutableClock`이 전달 시점에 영향을 주지 않게 하려는 것이다. Hibernate MySQL 방언은 `utc_timestamp`를 인자 없는 함수로만 등록하므로 HQL `cast(sql('UTC_TIMESTAMP(6)') as Instant)`로 정밀도를 유지했다.
- **멱등 만료 시각의 기준 변경**: 지점·상품·상품 분류·권한 프리셋 생성의 `expires_at`은 DB 시각(`UTC_TIMESTAMP(6)+24h`)에서 애플리케이션 `Clock`+24시간으로 바뀌었다. 이 값은 저장만 되고 해당 명령에서는 읽지 않는다. 만료를 실제로 비교하는 초대 생성은 원래부터 애플리케이션 시각을 썼으므로 기준이 하나로 통일된다. 운영 `Clock`은 `Clock.systemUTC()`다.

## 4. 구현 내용

- `common/audit`: `AuditEvent`(추가 전용 엔티티), `AuditResult`, `AuditEventRepository`, `AuditTrail`.
  - 변경 기록(`change`)과 인증 시도(`attempt`) 두 가지 생성 경로만 둔다.
- `common/idempotency`: `IdempotencyRecord`, `IdempotencyState`, `IdempotencyRecordRepository`, `IdempotencyStore`.
  - 조회는 `@Lock(PESSIMISTIC_WRITE)`로 기존 `SELECT ... FOR UPDATE`와 같은 잠금을 건다(없는 키의 gap lock 포함).
  - 해시 계산과 재요청 비교는 기존처럼 각 명령에 남겼다.
- `common/outbox`: `WorkspaceEvent`, `WorkspaceEventType`, `WorkspaceEventRepository`, `WorkspaceOutbox`.
  - 쓰기는 `invalidate`, `notifyEmployee`, `invalidateEveryBranch`.
  - 전달 완료 표시는 SSE 폴러가 트랜잭션 없이(`NOT_SUPPORTED`) 호출하므로 메서드 단위 트랜잭션을 둔다. 기존의 자동 커밋 UPDATE와 같은 단위다.
- 저장소 9곳(LoginAudit, PermissionPreset, Branch, EmployeeManagement, InvitationDelivery, InvitationManagement, ProductCategory, Product, WorkspaceOutbox)의 해당 SQL을 공통 컴포넌트 호출로 교체했다. 메서드 시그니처와 서비스 코드는 바꾸지 않았다.
- 매핑에서 확인한 사항:
  - MySQL `json` 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)` + `String`으로 매핑한다. 처음 시도한 `LONG32VARCHAR`는 MySQL에서 CLOB(`tinytext`)으로 해석되어 `validate`가 거절했다.
  - `JSON` 매핑은 이미 직렬화된 문자열을 다시 인코딩하지 않고 JSON 문서(`JSON_TYPE` = `OBJECT`)로 저장한다. 다만 읽을 때는 MySQL의 공백 정규화 대신 압축 형식 문자열이 돌아온다. 호출부는 이 문자열을 다시 파싱하므로 의미는 같다.
  - `binary(32)`는 `@JdbcTypeCode(SqlTypes.BINARY)` + `byte[]`로 매핑한다.
- 테스트 생성자 연결: `InvitationEmailWorkerIntegrationTests`가 저장소를 직접 생성하므로 생성자 인자 2개만 추가했다. 검증 내용은 바꾸지 않았다.
- 신규 `SharedRecordsTests`(4개):
  - 감사 JSON이 문서로 저장되는지, 인증 시도의 결과·사유가 저장되는지
  - 멱등 해시 32바이트 왕복, 키의 대소문자 구분, 만료 기록 삭제
  - outbox가 한 번만 완료 표시되고 `attempts`가 증가하는지
  - 전체 지점 무효화가 지점마다 1행씩 쓰이는지

## 5. 검증 결과

- 집중 실행: `SharedRecordsTests` 4개, `JpaMappingTests` 3개 통과.
- 시행착오도 기록한다. 아래 실패는 성공으로 집계하지 않았다.
  - `LONG32VARCHAR` 매핑: `validate` 실패.
  - `function('utc_timestamp', 6)`: 쿼리 검증 실패(인자 개수).
  - `sql(...)`만 사용: 타입 비교 실패.
  - JSON 공백 문자열 비교: 테스트 기대값 오류.
- 전체 회귀: `test` **218개/29개 suite**, 실패·오류·건너뜀 0, 5분32초 BUILD SUCCESSFUL. 신규 4개를 빼면 214개이며, 기존 테스트의 검증 내용은 바꾸지 않았다. 이번 브랜치 시작점(`main` 75aeaa8)에서 별도로 기준선을 다시 돌리지는 않았다.
  - 실행 로그에 SSE 폴러 실패 로그(`Workspace dispatch failed`)는 0건이었다. 트랜잭션 없는 폴링에서 완료 표시가 정상 동작했다는 근거다.
- 테스트 위치 이동: 전체 실행 뒤 [디렉토리 구조](../architecture/directory-structure.md)의 테스트 위치 규칙에 따라 `SharedRecordsTests`를 `integration/database`에서 `common`으로 옮겼다. 이동 후 `SharedRecordsTests` 4개와 `ModuleBoundaryTests` 1개를 다시 실행해 통과했다.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- `OwnerBootstrap`은 Spring 컨텍스트와 분리된 초기화 명령에서 직접 Connection으로 `audit_events`에 쓴다. 이번 범위에서 제외했고 7단계 정리에서 처리 방식을 정한다.
- 모듈 저장소가 아직 감사·outbox·멱등 래퍼 메서드를 가지고 있다. 각 모듈 전환 단계에서 서비스가 공통 컴포넌트를 직접 쓰도록 정리한다.
- 멱등 기록 만료 정리 작업은 원래 없었고 이번에도 추가하지 않았다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
