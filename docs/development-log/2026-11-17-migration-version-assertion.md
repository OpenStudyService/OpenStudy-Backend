# 2026-11-17 DB 초기화 시험의 마이그레이션 버전·테이블 수 갱신

## 1. 배경 / 문제

- 발표 전 점검으로 main(`c3a45a9`)에서 전체 테스트(`./gradlew test`)를 실행했다. 1,270개 중 2개가 실패했고 28개는 건너뛰었다.
- `DatabaseInitializationTests.migratesAllTablesAndDoesNotReapplyV1OrRemoveData`가 최신 마이그레이션 버전을 `19`로 기대했지만 실제 값은 `22`였다.
  - 11/16에 V20(`seat_occupancy_samples` 뷰), V21(같은 뷰 재정의), V22(`ai_plan_cache` 테이블)가 추가됐는데, 이 시험의 기대값은 고치지 않았다.
  - 각 PR은 AI 모듈 범위 테스트만 실행해서 이 시험이 실행되지 않았다.
- `FoundationMigrationTests.v14AddsTheMergedStatusAndTheMergeHistory`는 `V8__...sql` 자원을 읽지 못해 실패했다.

## 2. 검토한 선택지

- 기대값을 고정 숫자로 갱신한다: 기존 시험 방식을 유지한다. 마이그레이션을 추가할 때마다 고쳐야 한다.
- 기대값을 마이그레이션 폴더에서 계산한다: 갱신이 필요 없지만, 시험이 "의도한 버전까지 적용됐는지"를 고정해서 확인하는 의미가 약해진다.

## 3. 결정 및 이유

- 고정 숫자 갱신을 택했다. 발표 직전이라 시험 의도를 바꾸지 않는 가장 작은 변경으로 정했다.
- `FoundationMigrationTests` 실패는 그 클래스만 다시 실행하자 26개 모두 통과했다. 병렬 fork 실행 중 자원 읽기가 일시적으로 실패한 것으로 보고 이번에는 코드를 바꾸지 않았다. 원인은 확인하지 못했다.

## 4. 구현 내용

- `DatabaseInitializationTests`: 기대 버전 `19` → `22`, 테이블 수 `79` → `81`(V20 뷰 1개, V22 테이블 1개. `information_schema.tables`는 뷰도 센다).

## 5. 검증 결과

- `DatabaseInitializationTests` 단독 실행: 4개 통과.
- `./gradlew slowTest`: 36개 통과(`FoundationMigrationTests`, `OwnerBootstrapTests`).
- 수정 후 전체 테스트(`./gradlew test`)는 다시 실행하지 않았다. 수정 전 전체 실행에서 나머지 1,266개는 통과했다.

## 6. 남은 작업 / 보류 사항

- `FoundationMigrationTests`의 일시적 자원 읽기 실패가 반복되면 원인(병렬 fork, 파일 잠금 등)을 조사한다.
- 마이그레이션을 추가할 때는 이 시험의 기대값도 함께 갱신해야 한다. 이 시험은 `@Tag("slow")`가 아니어서 `slowTest`에 포함되지 않는다.
