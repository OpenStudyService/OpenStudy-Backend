# 2026-10-28 JPA 전환 7단계: 정리와 재발 방지 규칙

관련: [6-2단계 기록](2026-10-28-jpa-migration-phase6-2-invitation-management.md), [0단계 기록](2026-10-27-jpa-migration-phase0.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04, M1-10.

## 1. 배경 / 문제

6단계까지 업무 모듈의 저장소를 모두 JPA로 옮겼다. 운영 코드에서 직접 JDBC가 남은 곳은 다음 두 곳이었다.

- `bootstrap/OwnerBootstrap`: 최초 사장님 초기화 명령. `DriverManager`로 연결을 직접 연다.
- `localpreview/LocalPreviewConfiguration`: local 프로필에서 지점 샘플 2개를 넣는 시드. `JdbcTemplate`을 쓴다.

또 5-2단계에서 확인한 제약(같은 트랜잭션에서 JPA 밖으로 엔티티 행을 바꾸면 이미 로드된 엔티티가 그 변경을 보지 못함)이 다시 생기지 않도록 막는 규칙이 필요했다. 설계 문서(디렉토리 구조, 결정 기록, 아키텍처 검사 README)도 전환 완료 상태와 맞지 않았다.

## 2. 검토한 선택지

- **`OwnerBootstrap`**
  - JPA로 전환: Spring·Hibernate 컨텍스트를 띄워야 한다. M1-10은 이 명령을 일반 서버 기동과 분리된 독립 명령으로 정했다. 컨텍스트를 띄우면 웹 서버·스케줄러·Flyway 로그 등 기동 부수효과를 따로 꺼야 하고 명령의 성격이 바뀐다.
  - 직접 JDBC 유지 + 유일한 예외로 명시: 이 방식을 택했다. 명령은 한 연결, 한 트랜잭션에서 가드 → 직원 순서로 잠그고 소유자 1명·감사 1행만 쓴다. 같은 트랜잭션에 JPA가 없으므로 5-2 제약에도 해당하지 않는다.
- **`LocalPreviewConfiguration`**
  - 기동 시점 시드에는 요청자가 없어 `BranchService.create`(소유자 명령·멱등 키 필요)를 쓸 수 없다.
  - 기존 SQL도 `branches` 테이블에 직접 넣었으므로, 같은 수준인 `BranchRepository`·`Branch` 엔티티로 옮겼다. `localpreview`는 업무 모듈이 아니라 local 프로필 전용 개발 도구라 모듈 경계 검사 대상도 아니다.
- **규칙 구현**: 프로젝트에는 ArchUnit 의존성이 없고, 기존 `ModuleBoundaryTests`는 소스 파일을 읽어 검사한다. 같은 방식을 택해 의존성을 추가하지 않았다.

## 3. 결정 및 이유

- `OwnerBootstrap`은 직접 JDBC를 유지한다. 클래스 주석과 설계 문서에 유일한 예외임을 적고, 아키텍처 테스트의 허용 목록에도 이 파일 하나만 둔다.
- 로컬 미리보기 시드는 `BranchRepository.saveAll`로 바꿨다. 기존처럼 `created_by`는 비우고 운영시간 행은 만들지 않는다(화면에서는 휴무로 표시).
- `PersistenceAccessTests`를 추가했다.
  - 직접 JDBC(`org.springframework.jdbc.core`, `JdbcTemplate`, `JdbcClient`, `java.sql.Connection`·`DriverManager`·`Statement`·`PreparedStatement`, `prepareStatement(`, `createStatement(`)는 `OwnerBootstrap`에만 허용한다.
  - native SQL(`nativeQuery = true`, `createNativeQuery(`)은 `*Repository` 파일에만 허용한다(JPA-01).
  - 위반 예제와 허용 예제(`java.sql.SQLException` 검사, Spring Data `@Query`)로 정규식 자체도 검증한다.
- 설계 문서
  - 디렉토리 구조에 "데이터 접근 규칙" 절을 추가했다(JPA 저장소만 사용, native SQL 위치, JPA 밖 변경 제약, version 1회 증가 규칙, 검사 테스트).
  - 공통 패키지 현황 문장과 `common/persistence` 행, `architecture/` 검사 현황을 실제 상태로 고쳤다.
  - 결정 기록의 JPA 절에는 표를 고치지 않고, 전환 완료와 native SQL로 남긴 곳의 목록을 새 문단으로 추가했다.
  - 아키텍처 검사 README를 현재 실행되는 두 검사에 맞게 고쳤다.

## 4. 구현 내용

- `localpreview/LocalPreviewConfiguration`: `JdbcTemplate` → `BranchRepository`.
- `bootstrap/OwnerBootstrap`: 주석만 보강했다. 동작은 바꾸지 않았다.
- `test/architecture/PersistenceAccessTests`(신규, 3개).
- 문서: `docs/architecture/directory-structure.md`, `docs/design-decisions/decision-register.md`, `src/test/java/com/openstudy/architecture/README.md`.
- native SQL로 남긴 곳(9개 파일, 25개 쿼리)
  - `AuthorizationRepository` 14개: 권한 상태(잠금·비잠금)·명령 잠금 순서·관리자 수
  - `EmployeeRepository` 3개: 선택 목록·지점 관련 여부 EXISTS, 세션 삭제
  - `ProductRepository` 2개: 상품 `FOR UPDATE`/`FOR SHARE`
  - 1개씩: `BranchRepository`(접근 가능 지점 EXISTS), `BranchMembershipRepository`·`PermissionPresetRepository`·`InvitationRepository`(권한 행 `FOR SHARE`), `InvitationSendJobRepository`(가드 잠금), `WorkspaceEventRepository`(전체 지점 무효화 `INSERT ... SELECT`)

## 5. 검증 결과

- `PersistenceAccessTests` 3개, `ModuleBoundaryTests` 1개, local 프로필로 시드를 실행하는 `LocalPreviewIntegrationTests` 5개가 통과했다.
- 규칙이 실제 코드에서도 동작하는지 확인했다. 모듈 파일(`SessionBranchService`)에 `JdbcTemplate` import를 잠시 넣자 테스트가 해당 파일을 지목하며 **실패**했고, 확인 후 원복했다(diff 없음).
- 결정 기록에 적은 native SQL 목록을 `grep`으로 코드와 대조했다.
- 전체 회귀: `test` **252개/38개 suite**(6-2 병합 후 249개 + 신규 3개), 실패·오류·건너뜀 0, 5분55초 BUILD SUCCESSFUL.
- 실제 서버 기동(local 프로필)·`OwnerBootstrap` 명령 실행은 수행하지 않았다. `OwnerBootstrap`은 동작을 바꾸지 않았고 기존 `OwnerBootstrapTests`가 그대로 검증한다.

## 6. 남은 작업 / 보류 사항

- JPA 전환 계획(0~7단계)은 이 단계로 끝난다. 이후 새 모듈(결제·예약 등)은 처음부터 엔티티·Spring Data로 구현하고, 위 아키텍처 테스트가 직접 JDBC 사용을 막는다.
- 디렉토리 구조 문서의 "시드와 최초 사장님 전용 초기화 명령은 아직 구현하지 않았다" 문장은 이미 사실과 다르지만(`OwnerBootstrap` 구현됨), JPA 전환과 무관해 이번 범위에서 고치지 않았다.
- 지점의 `created_by`/`updated_by`를 채울지(3단계 보류 사항)는 별도 결정이 필요하다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
