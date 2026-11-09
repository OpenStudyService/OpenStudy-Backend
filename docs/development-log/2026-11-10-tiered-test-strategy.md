# 2026-11-10 테스트 실행 범위 단계화와 병렬 실행

## 1. 배경 / 문제

- 기능을 개발할 때마다 백엔드 풀테스트를 실행해 개발 시간이 크게 늘었다.
- 2026-11-06 실행 결과(89개 테스트 클래스)를 클래스별 실행 시간으로 합산하면 약 2,589초(약 43분)였다. 테스트는 단일 JVM에서 직렬로 실행되었다.
- 대부분 Testcontainers MySQL을 사용하는 통합 테스트이다. 특히 아래 두 클래스는 테스트마다 Flyway로 스키마를 지우고 다시 만든다.
  - `FoundationMigrationTests` 179초
  - `OwnerBootstrapTests` 96초
- `AGENTS.md`에 테스트 실행 범위 규칙이 없어 에이전트가 매 수정마다 안전하게 전체 테스트를 실행했다.

## 2. 검토한 선택지

- **개발 중 수정 모듈만 실행(`--tests`)**: Gradle 기본 기능이라 추가 설정 없이 효과가 가장 크다. 다만 규칙으로 정하지 않으면 활용되지 않는다.
- **느린 테스트 분리(`@Tag("slow")`)**: 스키마를 다시 만드는 테스트를 평소 실행 범위에서 제외한다. 마이그레이션 변경 시 실행을 놓칠 수 있어 별도 규칙이 필요하다.
- **병렬 fork(`maxParallelForks`)**: `MySqlTestConfiguration`이 JVM당 MySQL 컨테이너 하나를 공유하므로 fork끼리 데이터가 섞이지 않는다. 대신 fork마다 힙 1 GiB와 MySQL 하나가 필요하다.

## 3. 결정 및 이유

- 세 가지를 모두 적용하고, 시점별 실행 범위를 `AGENTS.md`에 규칙으로 명시했다.
  - 구현 중에는 수정한 모듈만 실행한다.
  - 커밋 전에는 영향받는 공통 영역까지 실행한다.
  - PR 전에는 전체 테스트를 1회 실행한다.
- 풀테스트 자체는 그대로 유지한다. `gradlew test`는 slow 테스트를 포함한 전체를 실행한다.
- 병렬 fork 수는 기본값을 `CPU 코어 수 / 3`(1~4)로 하고, `-PtestForks=N`으로 덮어쓸 수 있게 했다. 메모리가 적은 팀원 PC를 고려했다.

## 4. 구현 내용

- `build.gradle`
  - 기존 `test` 설정을 `tasks.withType(Test)` 공통 설정으로 옮겼다. 새 태스크에도 같은 안전 설정(fail-closed datasource, 메일 비활성화 등)이 적용된다.
  - `maxParallelForks`를 추가했다.
  - `fastTest`(slow 제외)와 `slowTest`(slow만) 태스크를 추가했다.
- `FoundationMigrationTests`, `OwnerBootstrapTests`에 `@Tag("slow")`를 붙였다.
- `AGENTS.md`에 "테스트 실행 범위" 섹션을 추가했다(백엔드 저장소와 워크스페이스 공통 `AGENTS.md`).

## 5. 검증 결과

- 측정 환경: 12코어 / 32 GB, Docker 29.9.2, 기본 4 fork. 작업 트리에 이 작업과 무관한 미커밋 변경(`ApiRequestFilter`, `application-prod.yaml`)이 있는 상태에서 실행했다.

| 명령 | 결과 | 소요 시간 |
|---|---|---|
| `gradlew test --rerun-tasks` | 102개 클래스, 1,070개 테스트, 실패 0 | 1,017초(약 17분) |
| `gradlew fastTest` | 100개 클래스, 1,042개 테스트, 실패 0, slow 2개 클래스 제외 확인 | 691초(약 11.5분) |
| `gradlew slowTest` | 2개 클래스, 실패 0 | 379초(약 6분) |
| `gradlew test --tests "com.openstudy.modules.locker.*"` | 2개 클래스, 74개 테스트, 실패 0 | 86초 |

- 이전 기준(약 43분)은 2026-11-06 결과의 클래스별 시간 합산값이고 측정한 wall-clock이 아니다. 그 이후 테스트 클래스가 89개에서 102개로 늘었다. 따라서 직렬 대비 단축 폭은 추정치이다.
- 동일 조건의 직렬(`-PtestForks=1`) 재측정은 하지 않았다.

## 6. 남은 작업 / 보류 사항

- 프론트엔드 저장소에는 별도 `AGENTS.md`가 없다. 워크스페이스 공통 `AGENTS.md`에만 프론트 규칙을 추가했다.
- slow 두 클래스는 테스트마다 `flyway.clean()/migrate()`를 실행한다. 클래스당 한 번만 스키마를 만들고 테스트마다 데이터만 정리하도록 바꾸면 더 줄일 수 있지만 이번 범위에서는 제외했다.
- CI(GitHub Actions)에서의 병렬 실행 여부와 러너 메모리는 확인하지 않았다.
