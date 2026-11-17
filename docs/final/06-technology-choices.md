# 6. DB 기술 선택과 근거

| 영역 | 선택 | 핵심 이유 |
|---|---|---|
| DBMS | MySQL 8.4 / InnoDB | 운영 환경 확정 조건. 부분 인덱스 부재는 설계로 보완 |
| 문자셋·시간 | `utf8mb4_0900_ai_ci`, UTC `DATETIME(6)` | 한글·이모지, 지점 시간대 계산을 애플리케이션에서 명확히 |
| 스키마 관리 | Flyway (버전 SQL) + Hibernate `validate` | 스키마의 정본을 SQL 하나로 |
| 데이터 접근 | Spring Data JPA + 제한된 native SQL | 업무 규칙은 엔티티에, 잠금·집계는 SQL로 |
| 테스트 DB | Testcontainers MySQL 8.4.11 | 운영과 같은 엔진으로 제약·잠금까지 검증 |
| 세션 | Spring Session JDBC | 별도 저장소 없이 같은 DB에 세션 저장 |

## 1. MySQL 8.4 / InnoDB

**배경.** 백엔드 착수 분석에서 PostgreSQL을 제안했지만, 운영 환경이 MySQL 8.4로 정해져 있어 2026-10-18에 MySQL로 정정했다.

**설계에 준 영향.** PostgreSQL에 있는 기능 두 가지를 쓸 수 없어 대체 설계를 했다.

| PostgreSQL 기능 | MySQL 대체 |
|---|---|
| 부분 유일 인덱스 (`WHERE status = 'ACTIVE'`) | 활성일 때만 값을 갖는 `STORED` 생성 컬럼 + UNIQUE ([3. D2](03-key-design-decisions.md#d2-활성-상태는-하나만을-생성-컬럼--unique로-강제한다)) |
| 범위 배제 제약 (시간 구간 겹침 금지) | 1시간 슬롯 행 + UNIQUE, 또는 부모 행 `SELECT ... FOR UPDATE` ([3. D3](03-key-design-decisions.md#d3-스터디룸-이중-예약을-시간-슬롯-행으로-막는다)) |

MySQL 8.0.16부터 CHECK 제약이 실제로 강제되므로 업무 규칙 87개를 CHECK로 옮길 수 있었다.

## 2. 문자셋과 시간

- 문자셋은 `utf8mb4`, 기본 정렬은 `utf8mb4_0900_ai_ci`다.
- 로그인 이메일은 trim·소문자로 정규화해 저장하고, 강세 기호가 다른 주소가 같은 ID로 합쳐지지 않도록 `utf8mb4_0900_as_ci`를 따로 적용했다.
- 시각은 모두 UTC `DATETIME(6)`로 저장한다. 영업일·요일·마감일처럼 지점 시간이 필요한 계산은 애플리케이션에서 Asia/Seoul로 바꿔 한다.
- 원화는 양의 정수(`BIGINT UNSIGNED`)로 저장하고 정수 연산만 한다. 부동소수점 오차가 없다.

## 3. Flyway + Hibernate `validate`

**선택.** 스키마는 Flyway의 버전 SQL 파일로만 바꾼다. Hibernate는 `ddl-auto: validate`로 엔티티와 실제 스키마가 맞는지 확인만 하고 DDL을 만들지 않는다.

**이유.**

- CHECK·생성 컬럼·복합 FK·컬럼별 정렬 규칙은 JPA 애노테이션으로 표현하기 어렵다. SQL이 정본이어야 이 제약이 빠지지 않는다.
- 엔티티 매핑이 스키마와 다르면 앱이 기동하지 않는다. JPA 전환 때 매핑을 일부러 틀려 기동이 실패하는 것을 확인했다.
- 운영에서는 앱의 Flyway를 끄고 별도 마이그레이션 계정이 적용한다. 앱 계정에는 DDL 권한이 없다.

운영 규칙(적용된 파일 불변, 병합 직전 번호 결정)은 [2. 스키마 변천](02-schema-evolution.md#마이그레이션-운영-규칙)에 있다.

## 4. JdbcTemplate → JPA 전환

**배경.** 아키텍처 결정은 "모듈 내부 JPA 엔티티가 업무 규칙과 저장을 함께 담당"이었지만, 초기 구현의 저장소 13개는 모두 `JdbcTemplate`과 직접 작성한 SQL이었다. AI 분석 엔진이 JPA 엔티티 메타모델을 전제로 해서 전환이 필요했다.

**진행 방식.**

- 0단계: 엔티티만 추가하고 기존 저장소는 그대로 둔 채 매핑만 검증했다. MySQL `ENUM` 54개, `UNSIGNED` 정수, DB가 채우는 시각, 생성 컬럼이 위험 요소였다.
- 1~7단계(10-27~10-28): 모듈 단위로 전환했다. 업무 모듈에서 `JdbcTemplate`을 없앴다.

**native SQL을 남긴 기준.** JPQL로 같은 SQL을 보장하기 어려운 곳만 저장소 안의 native query로 허용했다.

- 여러 행을 정해진 순서로 잠그는 곳 (`FOR UPDATE`, `FOR SHARE`)
- 권한 판정용 `EXISTS`
- 매출 집계
- 전체 지점 무효화 `INSERT ... SELECT`

**매핑에서 정한 규칙.**

| 스키마 | 매핑 |
|---|---|
| `ENUM` | `@Enumerated(EnumType.STRING)` |
| `TINYINT/SMALLINT UNSIGNED` | `@JdbcTypeCode` 지정 |
| DB 기본값 `created_at`/`updated_at` | `insertable/updatable=false` + `@Generated` |
| 생성 컬럼(`owner_slot` 등) | 매핑하지 않음. 유일성은 DB가 보장 |
| `created_by` 등 다른 모듈 참조 | 연관관계 대신 `Long` ID (모듈 간 엔티티 참조 금지) |

## 5. Testcontainers MySQL

**선택.** 자동 테스트는 Testcontainers로 MySQL 8.4.11 컨테이너를 띄워 실행한다. 처음 계획한 "같은 서버의 전용 테스트 DB"에서 2026-10-19에 바꿨다.

**이유.**

- H2 같은 내장 DB는 CHECK·생성 컬럼·잠금 동작이 MySQL과 다르다. 이 프로젝트의 정합성 장치 대부분을 검증할 수 없다.
- 컨테이너는 테스트마다 깨끗한 DB를 주고, 개발 DB를 실수로 건드릴 위험이 없다.
- 안전장치로 테스트의 기본 접속 주소를 열리지 않는 포트(`127.0.0.1:1`)로 지정해, 컨테이너 설정이 빠지면 개발 DB에 붙지 않고 바로 실패하게 했다.

**비용과 대응.** 전체 테스트가 4개 JVM 병렬로 약 17분 걸린다. 스키마를 매번 새로 만드는 마이그레이션 테스트에 `@Tag("slow")`를 붙여 평소에는 빼고 실행할 수 있게 했다(`./gradlew fastTest`).

## 6. Spring Session JDBC

세션을 같은 MySQL의 표준 테이블(`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`)에 저장한다. Redis 같은 별도 저장소를 운영하지 않고, 직원 비활성화 시 세션을 DB에서 바로 지울 수 있다. 세션에는 직원 ID, 인증·활동 시각, 세션 모드 같은 최소 정보만 두고 권한 스냅샷을 인가 근거로 쓰지 않는다. 권한은 요청할 때 DB에서 다시 확인한다([ADR-COM-001](../db/ADR-COM-001.md)).

## 근거 자료

- [JPA 전환 0단계 기록](../development-log/2026-10-27-jpa-migration-phase0.md), [7단계 정리](../development-log/2026-10-28-jpa-migration-phase7-cleanup.md)
- [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md)
- [Docker 테스트 DB 정책](../development-log/2026-10-19-docker-test-db-policy.md)
- [초기 DB 설정 검토](../development-log/2026-10-19-flyway-database-review.md)
- [기반 DB 설계: 저장 규칙](../design/foundation/database.md#저장-규칙)
