# 2026-11-08 대표 집계 실행 계획 검토와 인덱스 보강 (#89)

## 1. 배경 / 문제

- NFR-PERF-04: 주요 집계가 조회 조건에 맞는 인덱스를 쓰는지 확인하고, 전체 탐색이 있으면 인덱스로 줄인다.
- ADR-M9-001은 집계 테이블 없이 원거래에서 직접 계산한다고 정했다. "원결제일 기준 환불"과 "상품별 판매량"을 인덱스 부족 후보로 지목했다.
- 대상은 M9 매출(두 환불 기준)·지점 비교·이용권 판매량·상품 순위·추세·최소 원장이다. 모든 SQL은 `SalesSourceRepository`에 있다.
- PR #187 이후 AI 매출 분석도 이 서비스를 호출하므로 같은 결과를 따른다.

## 2. 검토한 선택지

### 측정 방법

- **SQL을 테스트에 옮겨 적고 실행**: 단순하지만, 저장소 SQL이 바뀌면 측정 대상과 실제가 어긋난다.
- **Hibernate statement inspector로 실제 SQL을 기록하고, 같은 값을 바인딩해 `EXPLAIN ANALYZE` 실행(선택)**: 측정 대상이 저장소가 보내는 문장과 항상 같다. 바인딩 순서(지점 → 시작 → 끝 → 분류, 원장은 두 조각 뒤 LIMIT/OFFSET)는 테스트가 저장소 규칙을 따라 만든다. 개수가 맞지 않으면 테스트가 실패한다.

### 대표 데이터 생성

- **업무 서비스(`PaymentService`)로 생성**: 실제와 같지만 수만 건이면 수 분이 걸린다.
- **JDBC 일괄 삽입**: 행마다 왕복이 생긴다.
- **서버 측 `INSERT ... SELECT`(재귀 CTE)로 한 번에 생성(선택)**: 8.8만 건이 수 초에 생성된다. 고객·상품·리비전·직원 부모 행은 만들지 않았다. 측정용 연결 하나에서만 `foreign_key_checks=0`으로 넣었다. 실행 계획에 쓰이는 인덱스와 CHECK 제약은 그대로 적용된다. 지점 ID 900001~900010을 이 테스트 전용으로 쓰고, 시작 전과 끝난 뒤 지운다.

### 기본 실행 시간 보호

- **데이터량을 줄여 항상 실행**: 대표 데이터량이 아니게 된다.
- **환경 변수 조건으로 분리(선택)**: `OPENSTUDY_QUERY_PLAN=true`일 때만 실행한다. 클래스 단위 조건이라 꺼져 있으면 Spring 컨텍스트도 만들지 않는다. 시간을 단언하지 않는 측정이므로 일반 실행에서 돌 이유가 없다. 로컬 AI 테스트의 `@EnabledIf`와 같은 방식이다.

### 인덱스 후보

- **A `payments (branch_id, approved_at)`(선택)**
- **B A에 `purpose, amount_krw, issued_pass_quantity`를 더한 커버링 인덱스**: 같은 시드에서 1년 지점 비교만 15% 남짓 더 빨랐다(39.7 → 33.7 ms). 1개월 집계는 차이가 없었다. 넓은 인덱스와 쿼리 열에 대한 결합을 감수할 이득이 아니라고 봤다.
- **쿼리에 `status IN ('APPROVED','REFUNDED')`를 더해 기존 `ix_payments_1` 사용**: 승인 결제의 정의(`approved_at IS NOT NULL`)를 상태 값과 다시 묶어야 해서 택하지 않았다. 측정하지 않았다.

## 3. 결정 및 이유

- V10에서 `ix_payments_branch_approved (branch_id, approved_at)`만 추가했다. V1은 수정하지 않았다.
- 원인: 기존 `ix_payments_1 (branch_id, status, approved_at)`은 가운데 `status`에 조건이 없어 승인 시각 범위로 좁히지 못했다. 그래서 승인 집계 전부가 `payments` 전체 탐색이었다. 원결제일 기준 환불은 `refunds` 전체 탐색 뒤 결제를 PK로 조회했다.
- ADR이 지목한 두 후보도 결국 이 한 인덱스로 해결됐다.
  - 원결제일 기준 환불: `payments` 범위 → `uk_refunds_1`
  - 상품별 판매량: 이용권 조인은 원래 `uk_passes_1` 단건 조회였고, 앞단의 결제 탐색이 문제였다.
- 쓰기 비용
  - 인덱스 열은 승인 때 한 번 정해지고 바뀌지 않는다.
  - 결제 빈도가 지점당 하루 수십~수백 건이라 무시할 수준으로 판단했다.
  - 운영 쓰기 지연은 측정하지 않았다.
- `ix_payments_1`은 외래 키와 결제 목록이 쓰므로 그대로 뒀다.
- 대표 데이터량: 지점 10개·2년·지점당 하루 12건(결제 87,600건)
  - 이슈 예시(지점 3~10개, 결제 수만 건, 1년치)를 따르되, 1년 조회도 이력의 일부만 읽도록 기간을 2년으로 잡았다.
  - 규모 확인으로 하루 40건(292,000건)도 측정했다.

## 4. 구현 내용

- `src/main/resources/db/migration/V10__sales_aggregation_indexes.sql`: 인덱스 1개
- `SalesQueryPlanMeasurementTests`(측정 전용, 환경 변수 조건)
  - 대표 데이터 시드
  - 12개 시나리오의 저장소 호출 시간 측정
  - 실제 SQL의 `EXPLAIN ANALYZE`
  - 인덱스 목록과 행 수를 `build/query-plans/<label>.md`로 출력
- 마이그레이션 번호를 고정한 테스트를 V10에 맞췄다.
  - `FoundationMigrationTests`: 실행 수 10, 버전 목록, V6 이후 5개
  - `OwnerBootstrapTests`, `DatabaseInitializationTests`: `"10"`
- 문서
  - `docs/db/sales-aggregation-query-plans.md`: 결과, 결정, 남은 전체 탐색의 이유
  - `docs/db/query-plans/`: 전후 원문
  - `docs/db/README.md`, `aggregation-api.md` 남은 작업 절에 링크를 추가했다.

## 5. 검증 결과

### 측정(Testcontainers MySQL 8.4.11, 로컬)

- 1개월 조회는 모두 전체 탐색에서 인덱스 범위 탐색으로 바뀌었다.
  - 매출 3지점: 19.4 → 6.0 ms
  - 원결제일 매출: 25.1 → 6.9 ms
  - 이용권 순위: 26.5 → 7.1 ms
  - 원장: 37.1 → 7.9 ms
  - 29만 건 규모에서는 약 4배 빨라졌다.
- 10지점·1년 조회는 전후 같은 전체 탐색이다. 대상이 표의 절반이라 옵티마이저가 고른 것이다.
  - 29만 건 규모의 첫 "추가 후" 측정에서 일부가 느리게 나왔다.
  - 같은 계획이었고 재측정에서 재현되지 않아 측정 잡음으로 판단했다(문서 5절).

### 테스트

- 관련 테스트 67개 통과, 측정 테스트 1개는 조건부로 건너뜀
  - B2(#87): `SalesRevenueIntegrationTests`, `SalesLedgerIntegrationTests`, `SalesConditionIntegrationTests`, `SalesSnapshotIntegrationTests`
  - B3(#88): `SalesPassIntegrationTests`, `SalesTrendIntegrationTests`
  - `AiSalesEquivalenceIntegrationTests`
  - `MigrationVersionTests`, `FoundationMigrationTests`, `OwnerBootstrapTests`, `DatabaseInitializationTests`
- B2·B3는 M9 마일스톤 이슈의 순서 표기다. #88 본문의 "B2의 공통 조건"과 #87 본문의 "B1 계약"(#86)으로 B1=#86, B2=#87, B3=#88임을 확인했다.
- 전체 `./gradlew test`: 1,030개 중 실패 0, 건너뜀 26(로컬 전용 AI 25, 조건부 측정 테스트 1). BUILD SUCCESSFUL(32분 31초, 측정 실행 직후라 평소 16분보다 길었다).

### 검증하지 않은 것

- 운영 DB의 분포·버퍼 풀에서의 실행 계획
- 이력이 2년보다 긴 경우의 1년 조회
- 운영 쓰기 지연
- 원장 깊은 페이지

## 6. 남은 작업 / 보류 사항

- 원장은 승인·환불을 합쳐 전부 정렬한 뒤 자른다. 1년·10지점 원장이 측정한 집계 중 가장 느렸다(약 150 ms / 29만 건 규모 460 ms). 페이지 방식 변경(키셋 등)은 API 계약 변경이라 이 이슈 범위 밖이다.
- `docs/db/schema-full.sql`과 README의 "현재 V6" 표기는 V7~V9부터 갱신되지 않았다. README는 마이그레이션을 추가하는 PR이 다시 추출하라고 하지만, 이 PR에서 하면 V7~V9의 변경이 함께 들어간다. 범위 밖으로 남기고 사용자에게 알린다.
- V10은 임시 번호다. 병합 직전에 main의 최신 번호 + 1로 맞추고, 번호를 고정한 테스트 3개도 함께 바꾼다.
