# 4. 인덱스 튜닝 사례: 매출 집계

추측으로 인덱스를 늘리지 않고, **측정 → 원인 분석 → 후보 비교 → 하나만 추가 → 다시 측정** 순서로 진행한 사례다. 결과는 V10 마이그레이션의 인덱스 1개다.

## 요약

| 항목 | 내용 |
|---|---|
| 대상 | 매출·이용권 판매량·추세·원장 집계 (M9, AI 매출 분석도 같은 쿼리 사용) |
| 문제 | 1개월 조회에도 `payments` 전체 탐색 |
| 원인 | 기존 인덱스 `(branch_id, status, approved_at)`의 가운데 열 `status`에 조건이 없어 `approved_at` 범위를 쓸 수 없었다 |
| 조치 | `ix_payments_branch_approved (branch_id, approved_at)` 1개 추가 ([V10](../../src/main/resources/db/migration/V10__sales_aggregation_indexes.sql)) |
| 효과 | 3지점·1개월 매출 **19.4 ms → 6.0 ms**, 원장 **37.1 ms → 7.9 ms** (결제 8.76만 건) |
| 규모 확인 | 결제 29.2만 건에서 3지점·1개월 매출 **55.9 ms → 13.3 ms** |

## 1. 측정 환경

- MySQL 8.4.11 (테스트와 같은 Docker 이미지)
- 지점 10개 × 2년 × 지점당 하루 결제 12건 = **결제 87,600건**, 환불 4,419건, 이용권 59,475건
- 측정한 SQL은 애플리케이션이 실제로 보내는 문장을 기록한 것이다. 사람이 옮겨 적은 SQL이 아니다.
- 시간은 예열 2회 뒤 5회 호출의 중앙값이고, 실행 계획은 `EXPLAIN ANALYZE`로 따로 확인했다.

## 2. 원인

집계는 승인 결제를 `status`가 아니라 `approved_at IS NOT NULL`과 승인 시각 범위로 고른다.

```sql
SELECT p.branch_id, p.purpose, COUNT(*), COALESCE(SUM(p.amount_krw), 0)
FROM payments p
WHERE p.branch_id IN (?) AND p.approved_at IS NOT NULL
  AND p.approved_at >= ? AND p.approved_at < ?
GROUP BY p.branch_id, p.purpose
```

기존 인덱스 `ix_payments_1 (branch_id, status, approved_at)`은 가운데 열 `status`에 조건이 없어서, 복합 인덱스의 왼쪽 접두 규칙상 `approved_at` 범위로 좁힐 수 없었다. 옵티마이저는 전체 탐색을 골랐다.

원결제일 기준 환불 집계는 더 나빴다. `refunds`에는 결제 시각이 없어서 환불을 모두 읽은 뒤 결제마다 PK로 다시 조회했다.

## 3. 후보 비교

| 후보 | 3지점·1개월 매출 | 1개월 원장 | 10지점·1년 비교 | 판단 |
|---|---|---|---|---|
| 추가 전 | 19.4 ms | 37.1 ms | 40.5 ms | |
| **A: `(branch_id, approved_at)`** | 6.0 ms | 7.9 ms | 39.7 ms | **채택** |
| B: A + 금액·수량 열(커버링 인덱스) | 5.1 ms | 8.9 ms | 33.7 ms | 미채택 |

B를 고르지 않은 이유:

- 1개월 조회는 A와 차이가 없고, 1년 조회에서만 15% 남짓 빨랐다.
- 인덱스 항목이 금액·수량 열만큼 넓어지고, 집계 쿼리가 쓰는 열 목록에 인덱스가 묶인다.
- 원장은 다른 열도 읽어서 B로도 커버되지 않는다.

**바꾸지 않은 것.** 쿼리에 `status IN (...)` 조건을 붙여 기존 인덱스를 타게 하는 방법도 있었다. 그러면 "승인 결제 = `approved_at`이 있는 결제"라는 집계 정의를 상태 값과 다시 묶어야 해서 택하지 않았다. 기존 인덱스도 다른 조회가 쓰고 있어 그대로 뒀다.

## 4. 실행 계획 변화

추가 전 (3지점·1개월 매출, 승인 SQL):

```text
-> Table scan on p   (87,600행 읽고 1,074행 사용)
```

추가 후:

```text
-> Index range scan on p using ix_payments_branch_approved
   over (branch_id = ? AND ? <= approved_at < ?)
```

원결제일 기준 환불도 `refunds` 전체 탐색에서 `payments` 인덱스 범위 → `uk_refunds_1` 단건 조회로 바뀌었다.

## 5. 결과

| 집계 (결제 8.76만 건) | 추가 전 | 추가 후 |
|---|---|---|
| 매출 · 처리일 · 3지점 · 1개월 | 19.4 ms | 6.0 ms |
| 매출 · 원결제일 · 3지점 · 1개월 | 25.1 ms | 6.9 ms |
| 이용권 판매량·순위 · 3지점 · 1개월 | 26.5 ms | 7.1 ms |
| 최소 원장 · 3지점 · 1개월 · 20건 | 37.1 ms | 7.9 ms |
| 최소 원장 · 원결제일 · 3지점 · 1개월 | 31.0 ms | 9.9 ms |
| 지점 비교 · 10지점 · 1년 | 40.5 ms | 39.7 ms (계획 동일) |

규모를 3.3배(29.2만 건)로 늘리면, 추가 전에는 1개월 조회도 표 크기에 비례해 느려졌다(55.9 ms). 추가 후에는 조회 범위의 행 수에만 비례했다(13.3 ms).

## 6. 남은 전체 탐색과 이유

10지점·1년 조회는 추가 후에도 전체 탐색이다. 이 시드에서 그 범위가 결제의 절반이라, 인덱스로 찾아 행마다 PK로 다시 읽는 것보다 표를 한 번 읽는 편이 싸다. 옵티마이저 판단이 맞는 경우라 그대로 뒀다. 이력이 길어져 1년이 표의 작은 일부가 되면 같은 인덱스로 범위 탐색을 고를 수 있지만 측정하지는 않았다.

## 7. 쓰기 비용

- 새 인덱스 열 `branch_id`·`approved_at`은 승인 때 한 번 정해지고 바뀌지 않는다. 환불로 `status`가 바뀌어도 이 인덱스는 갱신되지 않는다.
- 결제당 보조 인덱스 항목 1개(약 30바이트)가 추가된다. 지점의 결제 빈도(하루 수십~수백 건)에서는 무시할 수준으로 판단했다.

## 검증하지 않은 것

- 운영 DB의 실제 데이터 분포·버퍼 풀 크기에서의 실행 계획
- 운영 환경에서 인덱스 추가에 따른 결제 쓰기 지연
- 원장의 깊은 페이지(`OFFSET`이 큰 경우)의 시간

## 재현과 원문

```bash
OPENSTUDY_QUERY_PLAN=true OPENSTUDY_QUERY_PLAN_LABEL=after ./gradlew test --tests '*SalesQueryPlanMeasurementTests'
```

- [측정 보고서 전문](../db/sales-aggregation-query-plans.md)
- 실행 계획 원문: [추가 전](../db/query-plans/2026-11-08-m9-before.md), [추가 후](../db/query-plans/2026-11-08-m9-after.md)
- [ADR-M9-001](../db/ADR-M9-001.md)
