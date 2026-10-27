# 2026-10-27 JPA 전환 0단계: 결정 항목 정리와 매핑 검증

관련: [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

M1-04는 "모듈 내부 JPA 엔티티가 업무 규칙과 DB 저장을 함께 담당"으로 확정되어 있고 [디렉토리 구조](../architecture/directory-structure.md)도 `repository/`를 Spring Data JPA, `entity/`를 JPA 엔티티 위치로 정한다. 그러나 `main`(89f52e6)의 업무 저장소 13개와 `LocalPreviewConfiguration`은 모두 `JdbcTemplate`과 직접 작성한 SQL을 사용하고 `@Entity`는 0개다. JPA에서 JDBC로 바꾼다는 결정 기록은 찾지 못했다. 이대로면 JPA 엔티티·메타모델을 전제로 한 DomainLens 연동(FR-NL-27, FR-NL-30)에 등록할 엔티티가 없다.

전환 전에 Flyway가 소유한 현재 스키마를 `ddl-auto: validate`로 JPA가 그대로 받아들이는지 확인해야 했다. MySQL `ENUM` 54개, `BIGINT UNSIGNED`, `TINYINT/SMALLINT UNSIGNED`, DB가 관리하는 `created_at`/`updated_at`, 생성 컬럼(`owner_slot` 등), 수동 증가 `version` 컬럼이 매핑 위험 요소였다.

## 2. 검토한 선택지

- 저장소 하나를 바로 JPA로 바꾸는 방식: 매핑 문제와 동작 변경(잠금·version)이 한 번에 섞여 실패 원인을 구분하기 어렵다.
- 엔티티만 추가하고 기존 저장소는 그대로 두는 매핑 검증: 운영 동작을 바꾸지 않고, 애플리케이션 기동 시 `validate`와 실제 MySQL 왕복으로 매핑 가능성만 확인할 수 있다.

## 3. 결정 및 이유

엔티티만 추가하는 매핑 검증을 선택했다. 대상은 모든 모듈이 참조하는 `employees`와 부모·자식 구조를 가진 `branches`·`branch_weekly_hours`다. 기존 JDBC 저장소와 HTTP 동작은 변경하지 않았다.

native query 허용 범위, `@Version` 적용 범위, 소속·권한 테이블 소유 모듈, 전환 시점은 업무 동작과 일정에 영향을 주므로 권장안만 [JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택)에 적고 사용자 선택을 기다린다. 이 검증에서 `@Version`을 매핑한 것은 매핑 가능성 확인용이며 JPA-02 결정을 대신하지 않는다.

## 4. 구현 내용

- `modules/employee/entity`: `Employee`, `GlobalRole`, `EmployeeStatus`, `Theme`.
- `modules/branch/entity`: `Branch`, `BranchWeeklyHour`, `BranchStatus`. 운영시간은 `Branch`가 `cascade`·`orphanRemoval`로 소유한다.
- 매핑 규칙:
  - `ENUM` → `@Enumerated(EnumType.STRING)`.
  - `BIGINT UNSIGNED` → `Long`. 별도 타입 지정 없이 통과했다.
  - `TINYINT/SMALLINT UNSIGNED` → `int`/`Integer`에 `@JdbcTypeCode(SqlTypes.TINYINT/SMALLINT)` 지정.
  - `created_at`/`updated_at` → `insertable/updatable=false` + Hibernate `@Generated`(INSERT, INSERT·UPDATE). DB 기본값을 그대로 쓰고 저장 후 값을 다시 읽는다.
  - `owner_slot` 생성 컬럼은 매핑하지 않는다. 단일 소유자 UNIQUE 키는 DB가 계속 보장한다.
  - `created_by`/`updated_by` → 연관관계 대신 `Long` 식별자. 모듈 간 엔티티 참조 금지 원칙에 맞춘다.
- 엔티티 대상 패키지에 있던 `.gitkeep` 2개를 제거했다.
- `integration/database/JpaMappingTests`(3개): JPA 저장·조회 왕복, 기존 JDBC가 쓴 행을 JPA로 조회, 지점·운영시간 함께 저장과 version 증가.

## 5. 검증 결과

- 기준선: 변경 전 `main`(89f52e6) 전체 `test` **208개/27개 suite**, 실패·오류·건너뜀 0, 6분25초.
- 매핑 검증 `JpaMappingTests` **3개 통과**. Hibernate ORM 7.4.5.Final, MySQLDialect, MySQL 8.4.11 Testcontainers.
  - `ENUM`·`UNSIGNED`·`BOOLEAN` 컬럼을 `validate`가 받아들였다.
  - DB 기본값 `created_at`/`updated_at`이 저장 직후 채워졌다.
  - `@Version`이 DB 기본값 0에서 시작해 수정 시 1로 증가했다. 기존 JDBC 코드가 증가시킨 version도 그대로 읽혔다.
- `validate`가 실제로 불일치를 거절하는지 매핑을 일부러 틀려 확인했다. 세 경우 모두 컨텍스트 기동이 실패했고 확인 후 원복했다.
  - 없는 컬럼명(`opens_minutes`): 실패.
  - `weekday`의 TINYINT 지정 제거: `found [tinyint unsigned (Types#TINYINT)], but expecting [integer (Types#INTEGER)]`.
  - `status`를 `EnumType.ORDINAL`로 변경: `found [enum (Types#CHAR)], but expecting [tinyint (Types#TINYINT)]`.
- 전체 회귀: 엔티티 추가 후 `test` **211개/28개 suite**(기준선 208개 + 신규 3개), 실패·오류·건너뜀 0, 8분2초 BUILD SUCCESSFUL. 기존 테스트는 수정하지 않았다.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다. 엔티티를 사용하는 코드가 없어 HTTP 동작 변화는 없을 것으로 보지만, 기동 시 `validate` 통과는 통합 테스트 컨텍스트 기준으로만 확인했다.

## 6. 남은 작업 / 보류 사항

- JPA-01~04 사용자 선택. 선택 전에는 1단계(감사·멱등·outbox 공통 컴포넌트) 이후 전환을 시작하지 않는다.
- 이번 엔티티는 아직 어떤 저장소·서비스에서도 사용하지 않는다. 기존 JDBC 저장소 전환, 비관적 잠금(`@Lock`) 순서, `@Modifying` 일괄 갱신 후 영속성 컨텍스트 정합성, N+1 조회는 검증하지 않았다.
- `json`, `binary(32)` 컬럼과 복합 PK 연결 테이블(`*_grants`, `product_branches`) 매핑은 해당 단계에서 별도로 확인한다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.

## 7. 결정 반영 (2026-10-27 후속)

0단계 검증 결과를 확인한 뒤 사용자가 JPA-01~04를 모두 권장안 A로 확정했다. 위 3·6절의 "사용자 선택 대기"는 이 시점에 해소되었고, [설계 선택 기록](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택)에 결정일·결정자를 기록했다.

- JPA-01 A: 엔티티 저장·수정은 JPA. 다중 행 잠금 순서·EXISTS 권한 판정·집계처럼 JPQL로 같은 SQL을 보장하기 어려운 곳만 저장소 안의 native query를 허용한다.
- JPA-02 A: version을 노출하고 409로 충돌을 거절하는 엔티티에만 `@Version`을 둔다. `Employee`(M1-09 프로필)와 `Branch`(`BranchService.checkVersion`의 409)는 이미 이 조건에 해당하므로 0단계 매핑의 `@Version`을 유지한다.
- JPA-03 A: `branch_memberships`·`membership_permission_grants` 엔티티는 authorization 모듈이 소유하고 employee 모듈은 공개 service로 요청한다.
- JPA-04 A: 진행 중인 백엔드 작업 브랜치를 병합한 뒤 1단계(감사·멱등·outbox 공통 컴포넌트)부터 전환한다.

이번 반영은 문서 변경만 포함하며 코드와 테스트 결과는 5절과 같다.
