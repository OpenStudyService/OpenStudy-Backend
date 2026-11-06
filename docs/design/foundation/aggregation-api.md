# 정형 집계·최소 원장·홈 요약 API 계약

Backend [#86](https://github.com/OpenStudyService/OpenStudy-Backend/issues/86)의 M9 계약이다. 구현은 공통 집계 기반·매출·지점 비교·최소 원장(#87), 이용권 판매량·상품 순위·추세(#88), 운영 홈 요약(#90)에서 이 문서를 기준으로 진행한다. 실행 계획·인덱스 검토는 #89다. Frontend는 같은 [OpenAPI](openapi.json)(`0.1.11-m9-aggregation`)로 연동한다.

정책 근거는 [ADR-M9-001](../../db/ADR-M9-001.md)(M9-D01~D09)이다. 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을 따른다. 원거래 의미는 [M4 결제·환불 계약](customer-sales-api.md)과 [M6 공간 운영 계약](space-operations-api.md)을, 홈 카드의 원본은 [M5 좌석](seat-usage-api.md)·[M7 협업](collaboration-api.md)·[M8 근무](workforce-api.md) 계약을 따른다. 자연어 질의·동적 시각화는 [ADR-M10-001](../../db/ADR-M10-001.md)의 M10 범위다.

2026-11-05: 이 문서의 operation은 모두 설계 계약(`design-target`)으로 정했다. 2026-11-05: 매출·지점 비교·최소 원장 operation 3개(`GET /sales/revenue`, `GET /sales/revenue/branches`, `GET /sales/ledger`)는 #87에서 구현됐다(`implemented`). 2026-11-06: 이용권 판매량·상품 순위·추세 operation 3개(`GET /sales/passes/volume`, `GET /sales/passes/products`, `GET /sales/trend`)는 #88에서 구현됐다(`implemented`). 나머지 홈 요약(#90) operation은 설계 계약이며 아직 서버에 구현되어 있지 않다. 스키마는 바꾸지 않는다. 원거래(`payments`, `refunds`, `passes`, `product_revisions`)와 M5·M7·M8 원본 테이블에서 직접 계산한다. 인덱스가 부족하면 #89가 실행 계획을 확인한 뒤 새 버전 마이그레이션으로 인덱스만 추가한다.

## 제공 범위

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /sales/revenue` | 요청한 모든 지점의 `sales:read` | 승인액·환불액·순매출, 용도별 소계·필터 | #87 |
| `GET /sales/revenue/branches` | 요청한 모든 지점의 `sales:read` | 지점별 매출 비교 | #87 |
| `GET /sales/ledger` | 요청한 모든 지점의 `sales:read`. 결제 상세는 `payments:read` 별도 | 매출 최소 원장 | #87 |
| `GET /sales/passes/volume` | 요청한 모든 지점의 `sales:read` | 이용권 결제 건수·판매 수량·순판매량·발급 건수 | #88 |
| `GET /sales/passes/products` | 요청한 모든 지점의 `sales:read` | 이용권 상품별 판매량·순위 | #88 |
| `GET /sales/trend` | 요청한 모든 지점의 `sales:read` | 매출·이용권 판매량의 일·주·월 추세 | #88 |
| `GET /home/summary` | OWNER 또는 scope 지점의 유효 소속. 카드마다 원본 권한 | 운영 홈 요약과 이동 대상 | #90 |

모든 operation은 조회(`GET`)다. CSRF·`Idempotency-Key`·`version`이 없다. 응답은 공통 규칙대로 `Cache-Control: no-store`와 `X-Request-ID`를 준다.

### 추가 오류 코드

| HTTP | code | 의미 |
| --- | --- | --- |
| 422 | `PERIOD_TOO_LONG` | `to − from`이 366일을 넘음(M9-D01) |

기존 코드도 쓴다.

| HTTP | code | 경우 |
| --- | --- | --- |
| 403 | `FORBIDDEN` | 요청한 지점 중 하나라도 `sales:read`가 없음, 없는 지점 ID, `branchIds`를 생략했는데 `sales:read`가 있는 지점이 없음, 홈의 scope 지점에 유효 소속이 없음 |
| 422 | `VALIDATION_FAILED` | 형식 오류, 오프셋 없는 시각, `from`·`to` 중 하나만 보냄, `from ≥ to`, 중복 `branchIds`, 허용하지 않는 값, 함께 쓸 수 없는 필터, 홈의 `mine=true`와 `assigneeId` 동시 지정 |

조회 operation이라 404·409는 실제로 쓰지 않지만 다른 계약과 같이 응답 정의는 둔다. 빈 결과는 오류가 아니다(FR-AGG-10). 200과 [값 표현](#값-표현m9-d09)으로 돌려준다.

### 입력 오류의 상태 코드: 422(사용자 확정)

**사용자 확정: 422.** 2026-11-05 사용자가 366일 초과를 422 `PERIOD_TOO_LONG`으로 확정했고 ADR-M9-001 M9-D01 문구도 422로 고쳤다(ADR 변경 이력). 아래는 확정 전에 비교한 내용이다.

확정 전 ADR-M9-001 M9-D01과 #87 인수 조건은 366일 초과를 **400**으로 적었다. 기존 공통 계약은 입력 필드·형식·범위 오류를 모두 **422**로 쓴다([API 계약](api.md)의 오류 표, OpenAPI `Error422` "입력 필드·형식·범위 오류"). 같은 성격의 선례로 룸 예약 달력의 "기간은 최대 62일이고 넘으면 422"가 있다. OpenAPI 전체에 400 응답 정의가 없다.

| 안 | Frontend 처리 | 일관성 |
| --- | --- | --- |
| 400(ADR 문구) | 집계 화면만 400을 따로 처리해야 한다. 공통 오류 처리기의 `fieldErrors` 표시를 400에도 연결해야 한다 | 같은 "범위 초과"가 달력은 422, 집계는 400이 된다. `components/responses`에 400을 새로 만들어야 한다 |
| 422 `PERIOD_TOO_LONG`(이 계약) | 기존 422 처리(입력 유지·`fieldErrors` 표시)를 그대로 쓰고 code로 "기간 줄이기" 안내를 고른다 | 공통 규칙과 달력 선례와 같다 |

계약 초안은 공통 계약을 따르는 422를 제안했고 사용자가 그대로 확정했다. #87 이슈 본문의 인수 조건은 아직 "400"이라 422로 맞춰야 한다. 구현(#87)은 422 `PERIOD_TOO_LONG`을 기준으로 테스트한다.

## 공통 조건

### 지점(M9-D07)

- `branchIds`는 반복 query다(`branchIds=1&branchIds=2`). 생략하면 **요청자에게 `sales:read`가 있는 모든 지점**이다. 공통 `scope=all`이 "현재 허용된 지점의 합집합"인 것과 같은 해석이며, 실제 집계한 지점을 `aggregation.condition.branchIds`와 `allBranches=true`로 돌려준다. 그런 지점이 하나도 없으면 403이다.
- 지정한 지점 중 **하나라도** `sales:read`가 없으면 403이다. 권한 있는 지점만 골라 집계하지 않는다. 사용자가 고른 범위와 다른 합계를 보여 주지 않기 위해서다(FR-AGG-11). 없는 지점 ID도 403이다. 공통 `Scope`의 "접근 불가 ID는 403"과 같고, 지점이 있는지 알려 주지 않는다.
- 판정은 `AuthorizationService.require(state, branchIds, BranchAction.READ_SALES)`다. OWNER는 모든 지점에서 통과하고, 그 외는 그 지점의 유효 소속에 `sales:read` 권한이 있어야 한다. `READ_SALES`는 운영 중지 지점도 통과한다. 운영 중지 지점의 과거 매출도 볼 수 있다(공통 규칙의 "신규 운영 거래 가능 여부와 데이터 열람 가능 여부를 분리").
- 권한 밖 지점의 거래는 합계·건수·원장 어디에도 들어가지 않는다. 지점 조건은 **원결제 지점**(`payments.branch_id`)에 건다. 환불 처리 지점이 요청 지점이어도 원결제 지점이 권한 밖이면 그 환불은 보이지 않는다.
- 매출·이용권 집계에는 `scope` 파라미터가 없다. 화면의 지점 선택은 `branchIds`로 보낸다. 지점 비교에 여러 지점이 필요하기 때문이다. 홈은 공통 `scope`를 쓴다.

### 기간(M9-D01)

- `from`(포함)·`to`(제외)는 시간대 오프셋이 있는 ISO 8601 시각이다(`Z` 또는 `+09:00`). 오프셋이 없으면 422 `VALIDATION_FAILED`다. 응답은 UTC로 돌려준다.
- 둘 다 생략하면 **이번 달 1일 0시부터 내일 0시 미만(Asia/Seoul)**이고 `aggregation.defaultPeriod=true`다. 하나만 보내면 422 `VALIDATION_FAILED`다. 한쪽 기본값을 추측하지 않는다.
- `from ≥ to`이면 422 `VALIDATION_FAILED`(`to`)다.
- `to − from`이 **366일(31,622,400초)**을 넘으면 422 `PERIOD_TOO_LONG`이다. Asia/Seoul은 일광 절약 시간이 없어 366일은 항상 이 초 수다.
- 기간 판정은 원거래 시각 `t`가 `from ≤ t < to`인지로 한다. 날짜가 아니라 시각을 비교하므로 UTC 날짜와 Asia/Seoul 날짜가 다른 자정 전후 거래도 Asia/Seoul 기준 구간에 들어간다.
- 미래 시각을 `to`로 줄 수 있다. 기본 기간도 내일 0시까지다. `to > asOf`이면 `aggregation.partial.periodOpen=true`다.

| 요청(2026-11-05 Asia/Seoul) | `from` | `to` | 결과 |
| --- | --- | --- | --- |
| 기간 없음 | — | — | `2026-11-01T00:00+09:00`(=`2026-10-31T15:00:00Z`) ~ `2026-11-06T00:00+09:00`(=`2026-11-05T15:00:00Z`), `defaultPeriod=true` |
| 2026년 전체 + 하루 | `2026-01-01T00:00+09:00` | `2027-01-02T00:00+09:00` | 366일, 200 |
| 366일 + 1마이크로초 | `2026-01-01T00:00+09:00` | `2027-01-02T00:00:00.000001+09:00` | 422 `PERIOD_TOO_LONG` |
| 윤년 1년 | `2028-01-01T00:00+09:00` | `2029-01-01T00:00+09:00` | 366일, 200 |
| 같은 시각 | `2026-11-05T00:00+09:00` | `2026-11-05T00:00+09:00` | 422 `VALIDATION_FAILED` |
| 오프셋 없음 | `2026-11-01T00:00` | `2026-11-06T00:00+09:00` | 422 `VALIDATION_FAILED` |
| `from`만 | `2026-11-01T00:00+09:00` | — | 422 `VALIDATION_FAILED` |

매월 1일에 기본 기간은 그날 하루(`[1일 0시, 2일 0시)`)다.

### 환불 기준(M9-D02, FR-AGG-17)

| `refundBasis` | 환불이 속하는 시각 | 의미 |
| --- | --- | --- |
| `processedDate`(기본) | `refunds.refunded_at` | 환불이 확정된 구간에서 차감한다. 이미 지난 기간의 수치가 나중에 바뀌지 않는다 |
| `paymentDate` | 원결제의 `payments.approved_at` | 원결제가 승인된 구간에서 차감한다. `asOf`까지 처리된 환불을 모두 반영하므로 지난 기간의 수치가 이후 환불로 바뀔 수 있다 |

카드·표·차트·최소 원장이 같은 기본값을 쓴다. 같은 요청의 모든 지표에 같은 기준을 적용하며, 환불액·환불 건수·이용권 환불 수량이 모두 이 기준을 따른다. 승인액·결제 건수·판매 수량·발급 건수는 기준과 무관하게 승인 시각으로 셈한다.

### 용도·상품 필터

- `purpose`(`이용권`·`스터디룸`·`사물함`)는 매출 operation(`/sales/revenue`, `/sales/revenue/branches`, `/sales/ledger`, `measure=revenue` 추세)에서 받는다. 생략하면 세 용도 모두다(M9-D03).
- 이용권 지표 operation(`/sales/passes/*`, `measure=passes` 추세)은 항상 이용권만 대상이다. `purpose`를 보내면 422 `VALIDATION_FAILED`다. 대신 `productId`(원본 상품 ID)와 `categoryCode`(판매 당시 분류 코드)를 받는다. 매출 operation에 `productId`·`categoryCode`를 보내면 422다.
- 판매 당시 이름·분류가 무엇이었든 `productId`는 같은 상품의 모든 판매를 고른다.

## 지표 정의

지표의 정본은 OpenAPI 최상위 `x-metric-definitions`다. 아래 표와 같은 내용이며 계약 테스트가 둘을 대조한다. 응답 schema의 지표 필드에는 `x-metric-id`가 붙어 있다. M10 의미 카탈로그는 이 `id`로 지표를 1:1 연결한다([M10 연결](#m10-연결)).

공통 사항:

- **승인 결제**는 `payments.approved_at IS NOT NULL`인 결제다. 상태는 `APPROVED`이거나, 승인 뒤 환불되어 `REFUNDED`다. 환불은 결제의 금액과 승인 시각을 바꾸지 않는다(FR-PAY-10). `FAILED`는 `approved_at`이 없어 어느 지표에도 들어가지 않는다. Mock 결제는 요청 안에서 승인·실패가 확정되므로 `PENDING` 행은 없다.
- **이용권 소비 차감**(`pass_balance_events`의 `USE`·`ADJUST`·`RESTORE`, `seat_sessions`의 차감액)은 어떤 매출 지표에도 넣지 않는다. 결제 시점에 이미 매출이 된 금액의 사용이기 때문이다.
- **귀속 지점**은 원결제 지점 `payments.branch_id`다. 환불도 `refunds.processing_branch_id`가 아니라 원결제 지점에서 차감한다(M9-D05). 이용 지점(`seat_sessions.branch_id`)과도 구분한다.
- V1 환불은 결제당 한 번의 전액 환불이다(`uk_refunds_1`). 따라서 환불액 = 원결제 금액, 이용권 환불 수량 = 원결제 발급 수량이다. 계산은 `refunds.amount_krw`를 그대로 더한다.
- 단위: **원**은 원화 정수, **건**은 결제·환불·업무 행 수, **개**는 이용권 수량이다. 금액에 소수가 없다.

| id | 이름 | 단위 | 포함 | 제외 | 귀속 지점 | 기준 시각 | 환불 기준 영향 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `sales.approved_amount` | 승인액 | 원 | 세 용도의 승인 결제 `amount_krw` 합. 나중에 환불된 결제 포함 | 실패 결제, 이용권 소비 차감 | `payments.branch_id` | `payments.approved_at` | 없음 |
| `sales.refund_amount` | 환불액 | 원 | `refunds.amount_krw` 합(이용권 환불, 룸 예약 취소 환불) | 이용권 소비 차감 | 원결제의 `payments.branch_id` | 처리일: `refunds.refunded_at`, 원결제일: `payments.approved_at` | 있음 |
| `sales.net_amount` | 순매출 | 원 | 승인액 − 환불액 | 실패 결제, 이용권 소비 차감 | `payments.branch_id` | 구성 지표를 따름 | 있음 |
| `sales.approved_payment_count` | 승인 결제 건수 | 건 | 승인 결제 행 수. 나중에 환불된 결제 포함 | 실패 결제 | `payments.branch_id` | `payments.approved_at` | 없음 |
| `sales.refund_count` | 환불 건수 | 건 | `refunds` 행 수 | — | 원결제의 `payments.branch_id` | 처리일: `refunds.refunded_at`, 원결제일: `payments.approved_at` | 있음 |
| `pass.payment_count` | 이용권 결제 건수 | 건 | `purpose=PASS` 승인 결제 행 수 | 실패 결제, 룸·사물함 결제 | `payments.branch_id` | `payments.approved_at` | 없음 |
| `pass.sold_quantity` | 이용권 판매 수량 | 개 | `purpose=PASS` 승인 결제의 `issued_pass_quantity` 합(V1은 결제당 1) | 실패 결제 | `payments.branch_id` | `payments.approved_at` | 없음 |
| `pass.refunded_quantity` | 이용권 환불 수량 | 개 | 이용권 결제 환불의 원결제 `issued_pass_quantity` 합 | 룸 환불 | 원결제의 `payments.branch_id` | 처리일: `refunds.refunded_at`, 원결제일: `payments.approved_at` | 있음 |
| `pass.net_sold_quantity` | 이용권 순판매량 | 개 | 판매 수량 − 환불 수량 | 실패 결제, 무료·수동 발급 | `payments.branch_id` | 구성 지표를 따름 | 있음 |
| `pass.issued_count` | 이용권 발급 건수 | 개 | 생성된 `passes` 행 수. 이후 환불된 이용권 포함 | 없음(V1은 모든 이용권이 승인 결제와 연결) | `passes.purchase_branch_id`(= 원결제 지점) | 원결제 `payments.approved_at` | 없음 |
| `home.task.today_count` | 오늘의 업무 | 건 | (완료 아님 ∧ `due_at` < 내일 0시) ∨ (완료 ∧ `completed_at` ∈ 오늘) | 기한 없는 미완료, 내일 이후 기한의 미완료 | `work_tasks.branch_id` | Asia/Seoul 오늘, `asOf` 상태 | 없음 |
| `home.task.completed_today_count` | 오늘 완료한 업무 | 건 | 완료 ∧ `completed_at` ∈ 오늘. 기한과 무관 | — | `work_tasks.branch_id` | `work_tasks.completed_at` | 없음 |
| `home.task.completion_rate` | 오늘의 업무 완료율 | % | 오늘 완료 ÷ 오늘의 업무 × 100 | 분모 0이면 해당 없음 | `work_tasks.branch_id` | Asia/Seoul 오늘 | 없음 |

세 이용권 수치의 구분(M9-D06, FR-PASS-05, FR-AGG-03):

- **결제 건수**(건)는 승인된 이용권 결제 수다. 환불되어도 줄지 않는다.
- **순판매량**(개)은 판매 수량에서 환불 수량을 뺀 값이다. 환불된 판매는 여기서 빠진다.
- **발급 건수**(개)는 생성된 이용권 수다. 환불된 이용권도 남는다. V1에는 결제 없는 발급이 없어(`passes.purchase_payment_id` 필수) 지금은 판매 수량과 같은 값이 된다. 무료·수동 발급이 생기면 이 지표에만 더한다.
- 발급 시각은 원결제 `approved_at`으로 본다. `passes.created_at`은 DB가 기본값으로 채우는 시각이라 같은 트랜잭션의 `approved_at`(애플리케이션 시계)과 마이크로초 단위로 다를 수 있고, 자정 직전 결제에서 결제 건수와 발급 건수가 다른 날로 갈릴 수 있기 때문이다. 결제 없는 발급이 생기면 그 발급의 시각 기준을 그때 정한다.

순매출과 순판매량은 환불 처리일 기준에서 **음수**일 수 있다. 이전 기간 판매의 환불이 이번 기간에 처리되면 이번 기간의 승인보다 클 수 있기 때문이다. 0이나 null로 바꾸지 않는다.

## 공통 응답 메타정보(FR-AGG-09)

매출·이용권 집계 6개 operation의 응답은 모두 `aggregation` 필드에 같은 `AggregationMeta`를 담는다. 페이지 응답은 공통 규칙대로 `meta`가 `PageMeta`이므로 이름을 `aggregation`으로 정했다. 카드·표·차트·원장은 이 값을 그대로 표시한다.

| 필드 | 의미 |
| --- | --- |
| `condition.branchIds` | 실제 집계한 지점(적용 지점), ID 오름차순 |
| `condition.allBranches` | `branchIds`를 생략해 `sales:read`가 있는 모든 지점을 집계했는지 |
| `condition.from`, `condition.to` | 적용한 시작(포함)·종료(제외), UTC |
| `condition.refundBasis` | 적용한 환불 기준 |
| `condition.purpose`, `condition.productId`, `condition.categoryCode` | 적용한 필터. 없으면 null. 이용권 지표 operation의 `purpose`는 항상 `이용권` |
| `condition.granularity` | 추세의 구간 단위. 다른 operation은 null |
| `defaultPeriod` | 기본 기간(이번 달)을 적용했는지 |
| `timeZone` | 항상 `Asia/Seoul` |
| `currency` | 항상 `KRW` |
| `asOf` | 결과 기준 시각(UTC). 이 시각까지 커밋된 원거래로 계산했다 |
| `rowCount` | 결과 행 수(아래 표) |
| `sourceRowCount` | 계산에 쓴 원거래 항목 수 = 승인 항목 수 + 환불 항목 수. 같은 조건의 최소 원장 `meta.total`과 같다 |
| `dataStatus` | `sourceRowCount = 0`이면 `noData`, 아니면 `hasData` |
| `partial.periodOpen` | `to > asOf`. 기간이 끝나지 않아 값이 늘 수 있다 |
| `partial.firstBucketPartial`, `partial.lastBucketPartial` | 추세의 첫·마지막 구간이 기간 경계에서 잘렸는지. 추세가 아니면 null |
| `metrics` | 응답에 담긴 지표의 `id`와 단위 |

`condition`은 서버가 요청을 해석한 결과다. M10이 같은 모양의 조건 객체로 공통 집계 기반을 호출한다(M10-D05). #87은 이 조건 객체를 받는 서비스 경계를 controller와 분리한다.

| operation | `rowCount` | `sourceRowCount`의 항목 |
| --- | --- | --- |
| `GET /sales/revenue` | `byPurpose` 행 수(3, `purpose` 필터면 1) | 조건의 승인·환불 항목 |
| `GET /sales/revenue/branches` | `items` 행 수(= 적용 지점 수) | 조건의 승인·환불 항목 |
| `GET /sales/ledger` | 전체 항목 수(= `meta.total`) | 같다 |
| `GET /sales/passes/volume` | 1 | 이용권 승인·환불 항목 |
| `GET /sales/passes/products` | 전체 상품 행 수(= `meta.total`) | 이용권 승인·환불 항목 |
| `GET /sales/trend` | 구간 수 | `measure`에 맞는 승인·환불 항목 |

`asOf`는 한 요청의 모든 수치가 같은 기준 시점에서 나왔음을 뜻한다. 구현은 한 읽기 트랜잭션(일관된 읽기)에서 합계·행·원장 합계를 계산한다. 다른 요청 사이의 고정 snapshot은 보장하지 않는다(공통 규칙과 같다).

## 값 표현(M9-D09)

0, 데이터 없음, 해당 없음을 서로 다른 표현으로 고정한다(FR-AGG-10, FR-UI-08).

| 상황 | 표현 | 예 |
| --- | --- | --- |
| 값이 있는 0 | 숫자 `0`과 `dataStatus: "hasData"` | 승인 6,000원·환불 6,000원 → `netAmountKrw: 0`, `hasData` |
| 데이터 없음 | 합계·건수는 `0`, `dataStatus: "noData"`. 목록은 `items: []`, `total: 0` | 거래 없는 지점 → 모든 금액 0, `noData` |
| 해당 없음(분모 0) | `Ratio`의 `state: "notApplicable"`, `percent: null` | 오늘의 업무 0건의 완료율 |
| 비율 값 | `Ratio`의 `state: "value"`, `percent`는 소수 첫째 자리 | 1 ÷ 3 → `33.3` |

- `dataStatus`는 집계 전체(`aggregation.dataStatus`)와 각 합계 묶음(`totals`, `byPurpose[]`, 지점 행, 추세 구간)에 있다. 각 묶음은 그 묶음의 원거래 항목이 없을 때 `noData`다.
- 합계 지표는 원거래가 없을 때 null이 아니라 0이다. 화면이 "0원"과 "거래 없음"을 함께 보여 줄 수 있게 `dataStatus`로 구분한다. 지점 비교에서 거래 없는 지점이 0으로 나오는 것(#87)과 "조회 대상 없음과 0의 구분"을 함께 만족한다.
- 목록 결과(상품별 판매량, 원장)에서 조건에 맞는 행이 없으면 `items: []`와 `aggregation.dataStatus: "noData"`다. 오류가 아니다.
- 금액은 원 단위 정수다. 순매출·순판매량만 음수가 될 수 있고 나머지는 0 이상이다.
- **비율**: `percent = numerator ÷ denominator × 100`을 정확한 십진 연산으로 계산한 뒤 소수 첫째 자리에서 HALF_UP으로 반올림한다. 부동소수 연산 뒤 반올림하지 않는다. `state = value ⇔ denominator > 0 ⇔ percent ≠ null`이다. 분자·분모를 함께 돌려줘 화면이 "2/3건"을 표시할 수 있다.

| 분자 | 분모 | `state` | `percent` |
| --- | --- | --- | --- |
| 0 | 0 | `notApplicable` | null |
| 0 | 4 | `value` | 0.0 |
| 1 | 3 | `value` | 33.3 |
| 2 | 3 | `value` | 66.7 |
| 1 | 8 | `value` | 12.5 |
| 1 | 16 | `value` | 6.3 |
| 7 | 7 | `value` | 100.0 |

1 ÷ 16 = 6.25는 HALF_UP으로 6.3이다(HALF_EVEN이면 6.2).

M5의 `SeatCounts.occupancyRate`는 좌석이 없으면 null인 기존 표현이다. 기존 operation은 바꾸지 않고, 홈의 좌석 카드는 같은 값을 `Ratio`로 바꿔 담는다.

## 매출(FR-AGG-01·17, M9-D03·D05)

`GET /sales/revenue?branchIds=&from=&to=&refundBasis=&purpose=`

- `totals`: 승인액·환불액·순매출(원), 승인 결제 건수·환불 건수(건), `dataStatus`.
- `byPurpose`: `이용권`·`스터디룸`·`사물함` 순서로 **항상 세 행**이다(`purpose` 필터면 그 한 행). 거래가 없는 용도도 0과 `noData`로 담는다. `totals`는 `byPurpose`의 합이다.
- 용도는 `payments.purpose`(`PASS`·`ROOM`·`LOCKER`)다. 룸 결제는 예약 생성 때, 사물함 결제는 최초 배정·연장 때 만들어진다(M6). 사물함은 환불하지 않으므로 사물함 환불액은 V1에서 항상 0이다. 룸 환불은 예약 취소에서 생긴다.

## 지점 비교(FR-AGG-02)

`GET /sales/revenue/branches?branchIds=&from=&to=&refundBasis=&purpose=&sort=&direction=`

- 적용 지점마다 한 행(`BranchRevenue`)이다. 지점 ID·이름, 매출 지표, 용도별 소계를 담는다. 거래가 없는 지점도 0과 `noData`로 반환한다.
- `sort`: `netAmount`(기본), `approvedAmount`, `refundAmount`, `branchId`, `requested`. `direction` 기본 `desc`.
  - 금액 정렬에서 같은 값이면 `branchId`를 같은 방향으로 정렬한다(공통 목록 규칙).
  - `requested`는 `branchIds`를 보낸 순서다. `direction`을 무시한다. `branchIds`를 생략했으면 ID 오름차순이다.
- 페이지가 없다. 행 수는 요청자가 고를 수 있는 지점 수로 제한된다.
- `totals`는 `items`의 합이며 같은 조건의 `GET /sales/revenue`의 `totals`와 같다.
- FR-AGG-02의 "권한이 있는 요청 지점만 결과에 포함한다"는 M9-D07에 따라 "요청 지점은 모두 권한이 있어야 하고 하나라도 없으면 403"으로 적용한다. ADR이 SRS 문구보다 구체적이고 확정된 결정이다.

## 최소 원장(FR-AGG-18)

`GET /sales/ledger?branchIds=&from=&to=&refundBasis=&purpose=&entryType=&page=&pageSize=&direction=`

### 항목

원거래 한 행이 원장 항목 하나다.

| 항목 | 원거래 | 기간에 넣는 시각(`basisAt`) | 금액 |
| --- | --- | --- | --- |
| 승인(`payment-{id}`) | 승인 결제 `payments` 행 | `payments.approved_at` | +`payments.amount_krw` |
| 환불(`refund-{id}`) | `refunds` 행 | 처리일: `refunds.refunded_at`, 원결제일: 원결제 `payments.approved_at` | −`refunds.amount_krw` |

| 필드 | 원본 |
| --- | --- |
| `paymentId`, `refundId` | `payments.id`, `refunds.id`(승인 항목은 null) |
| `branchId` | `payments.branch_id`(판매 지점, 귀속 지점) |
| `processingBranchId` | `refunds.processing_branch_id`(승인 항목은 null) |
| `purpose` | `payments.purpose` |
| `productId`, `productName` | `payments.product_id`, `payments.product_name_snapshot`(이용권만, 나머지 null) |
| `categoryCode`, `categoryName` | `passes.snapshot_category_code`, `snapshot_category_name`(`purchase_payment_id`로 연결, 이용권만) |
| `passQuantity` | 이용권 승인 +`issued_pass_quantity`(V1은 +1), 이용권 환불 −같은 값, 그 외 0 |
| `approvedAt`, `refundedAt` | `payments.approved_at`, `refunds.refunded_at` |
| `canViewPayment` | 요청자에게 `branchId`의 `payments:read`가 있는지. 화면 안내용 |

- **담지 않는 것**: 고객 이름·연락처·회원 번호·고객 ID, 결제 수단, 처리자, 환불 사유. 환불 사유는 자유 입력이라 개인정보가 들어갈 수 있다. 고객 ID는 비식별 원장의 목적(집계 근거 확인)에 필요 없다(M9-D07).
- 실패 결제는 매출 근거가 아니므로 원장에 없다.
- 스터디룸·사물함 항목의 상품은 `purpose`로 표시하고 `productName`은 null이다. `payments`에 룸·사물함 이름 스냅샷이 없고, 현재 룸·사물함 이름은 판매 당시 이름이 아닐 수 있기 때문이다.
- 결제 상세(`GET /payments/{id}`)는 그 결제 지점의 `payments:read`를 따로 검사한다. `sales:read`만 있는 사용자는 원장을 보지만 상세로 이동하면 403이다. `canViewPayment=false`이면 화면이 이동 버튼을 감춘다.

### 정렬·페이지

- `basisAt` 기준, 같으면 승인 → 환불, 같으면 원천 ID(`paymentId` 또는 `refundId`) 순이다. 모두 `direction`(기본 `desc`)을 따른다. `desc`에서는 환불 → 승인 순서가 된다. 같은 조건에서 순서가 항상 같다.
- 페이지는 공통 규칙이다(`page` 1부터, `pageSize` 기본 20·최대 100). 범위를 넘는 `page`는 빈 `items`와 실제 `total`이다.
- `entryType`(`승인`·`환불`)으로 한 종류만 볼 수 있다. 이때 `totals`는 그 종류만의 합이다.

### 정합성 규칙

같은 지점·기간·환불 기준·용도에서 다음이 항상 성립한다. #87은 이를 통합 테스트로 고정한다.

- `Σ(승인 항목 amountKrw)` = `GET /sales/revenue`의 `approvedAmountKrw`
- `−Σ(환불 항목 amountKrw)` = `refundAmountKrw`
- `Σ(전체 항목 amountKrw)` = `netAmountKrw`
- 승인 항목 수 = `approvedPaymentCount`, 환불 항목 수 = `refundCount`
- 원장 `meta.total` = `aggregation.sourceRowCount`
- 원장 응답의 `totals`는 페이지가 아니라 조건 전체의 합이며 위 값과 같다.

이용권 지표도 같은 항목으로 재현된다. `purpose=이용권` 원장의 승인 항목 수 = `pass.payment_count`, `Σ(승인 항목 passQuantity)` = `pass.sold_quantity`, `−Σ(환불 항목 passQuantity)` = `pass.refunded_quantity`다.

## 이용권 판매량(FR-PASS-05, FR-AGG-03, M9-D06)

`GET /sales/passes/volume?branchIds=&from=&to=&refundBasis=&productId=&categoryCode=`

`totals`(`PassVolume`)에 결제 건수(건), 판매 수량·환불 수량·순판매량(개), 발급 건수(개)를 따로 담는다. 단위가 다른 세 지표를 하나로 합치지 않는다. 매출 금액은 `GET /sales/revenue?purpose=이용권`으로 본다.

## 상품별 판매량·순위(FR-AGG-06·07)

`GET /sales/passes/products?branchIds=&from=&to=&refundBasis=&productId=&categoryCode=&page=&pageSize=`

- **행의 기준**은 판매 당시 상품 스냅샷 `(passes.product_id, passes.snapshot_product_name, passes.snapshot_category_code, passes.snapshot_category_name)`이다. 상품명이나 분류를 바꾼 뒤에도 바꾸기 전 판매는 판매 당시 이름·분류 행에 남는다. 같은 상품이 이름을 바꿨다면 행이 둘이 되고 `productId`는 같다.
- 환불은 환불된 이용권의 스냅샷 행에서 뺀다. 환불 처리일 기준에서 이전 기간 판매의 환불은 판매 수량 0, 환불 수량 1, 순판매량 −1인 행이 될 수 있다.
- 행은 기간에 그 스냅샷의 승인 또는 환불 항목이 하나 이상 있을 때만 나온다. 판매가 없는 상품은 행이 없다.
- **순서**: `netSoldQuantity` 내림차순, 같으면 `productId` 오름차순, `productName` 오름차순(유니코드 코드 포인트), `categoryCode` 오름차순, `categoryName` 오름차순이다. 이 순서는 고정이며 `sort`·`direction`을 받지 않는다. 같은 요청에 항상 같은 순서가 나온다.
- **순위**(`rank`)는 `netSoldQuantity` 기준 경쟁 순위다. 같은 값이면 같은 순위, 다음 순위는 그 수만큼 건너뛴다(10, 7, 7, 3 → 1, 2, 2, 4). 보조 기준은 순서만 정하고 순위를 나누지 않는다.
- 페이지는 공통 규칙이다. `totals`는 전체 행의 합이고 같은 조건의 `GET /sales/passes/volume`의 `totals`와 같다.
- `kind`는 `passes.kind`(`TIME`·`PERIOD`·`STORED_VALUE`)를 `시간권`·`기간권`·`정액권`으로 담는다. 유형은 상품 등록 후 바뀌지 않는다(Q9).
- 상품 스냅샷은 발급 때 판매 revision(`passes.purchase_product_revision_id`)에서 복사된 값이다. 집계는 `product_revisions`를 다시 읽지 않고 `passes`의 스냅샷 열을 쓴다.

## 추세(FR-AGG-14, M9-D04)

`GET /sales/trend?branchIds=&from=&to=&refundBasis=&granularity=&measure=&purpose=&productId=&categoryCode=`

- `granularity`: `day`(기본), `week`, `month`. 경계는 Asia/Seoul 0시다. 주는 **월요일 0시**에 시작하고, 월은 1일 0시에 시작한다.
- `measure`: `revenue`(기본)이면 구간마다 `revenue`(`RevenueAmounts`)를, `passes`면 `passes`(`PassVolume`)를 담고 다른 쪽은 null이다. `revenue`는 `purpose`를, `passes`는 `productId`·`categoryCode`를 받는다. 다른 쪽 필터는 422다. 한 응답이 매출과 이용권 수량을 섞으면 용도 필터가 어느 수치에 적용되는지와 `sourceRowCount`가 모호해지기 때문이다.
- 기간과 겹치는 **모든 구간**을 시간 오름차순으로 반환한다. 거래가 없는 구간도 0과 `noData`로 채운다.
- 각 구간은 `bucketStart`·`bucketEnd`(잘리기 전)와 `start = max(bucketStart, from)`, `end = min(bucketEnd, to)`(실제 집계)를 담는다. 둘이 다르면 `partial=true`다. 경계에 걸친 첫·마지막 구간은 기간 안의 부분만 집계하고 `aggregation.partial.firstBucketPartial`·`lastBucketPartial`로 알린다.
- `label`: `day`는 `YYYY-MM-DD`, `week`는 그 주 월요일 `YYYY-MM-DD`, `month`는 `YYYY-MM`(모두 Asia/Seoul)이다.
- 구간 합은 `totals`와 같고, `totals`는 같은 조건의 합계 operation(`/sales/revenue` 또는 `/sales/passes/volume`)과 같다. 원거래 하나는 한 구간에만 들어간다.
- **구간 수 상한**: 별도 상한을 두지 않는다. 366일 기간 상한에서 구간 수는 일 최대 367개(자정이 아닌 시각에 시작하면 양 끝이 부분 구간), 주 최대 54개, 월 최대 13개다. 이 크기는 한 응답으로 충분히 작고, 차트가 끊기지 않으려면 모든 구간이 필요하다(M9-D04).

## 계산 예시

지점 1·2, 기준 시각(`asOf`) 2026-12-05. 시각은 Asia/Seoul이다.

| 결제 | 지점 | 용도 | 금액 | 결제 | 환불 |
| --- | --- | --- | --- | --- | --- |
| P1 | 1 | 이용권 | 10,000 | 승인 10-31 23:30 | 11-02 10:00, 처리 지점 1 |
| P2 | 1 | 스터디룸 | 6,000 | 승인 11-01 00:30(UTC 10-31 15:30) | 11-03 12:00(예약 취소), 처리 지점 1 |
| P3 | 1 | 이용권 | 15,000 | 승인 11-30 23:59:59 | 없음 |
| P4 | 1 | 이용권 | 15,000 | 실패 11-04 15:00 | — |
| P5 | 1 | 사물함 | 20,000 | 승인 11-04 15:00 | 없음 |
| P6 | 1 | 이용권 | 12,000 | 승인 11-10 10:00 | 12-02 10:00, **처리 지점 2** |
| P7 | 2 | 이용권 | 9,000 | 승인 12-01 00:00:00 | 없음 |

11월 = `[2026-11-01T00:00+09:00, 2026-12-01T00:00+09:00)`. P2는 UTC 날짜가 10-31이지만 11월에, P7은 종료와 같은 시각이라 12월에 속한다. P4(실패)는 어디에도 없다. 현재 API는 환불 처리 지점을 원결제 지점으로 고정하지만(M4-D08) 스키마는 다른 처리 지점을 허용하므로 P6으로 귀속 규칙을 고정한다.

### 매출

| 조건 | 지점 | 승인액 | 환불액 | 순매출 | 승인 건수 | 환불 건수 | `dataStatus` | 설명 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 11월, 처리일 | 1 | 53,000 | 16,000 | 37,000 | 4 | 2 | `hasData` | 환불 P1(10월 판매, 11-02 처리)·P2. P6 환불은 12월 |
| 11월, 원결제일 | 1 | 53,000 | 18,000 | 35,000 | 4 | 2 | `hasData` | 환불 P2·P6(11월 판매). P1은 10월 판매라 제외 |
| 11월, 두 기준 | 2 | 0 | 0 | 0 | 0 | 0 | `noData` | P6 환불을 처리했지만 원결제 지점이 1이라 차감하지 않음 |
| 12월, 처리일 | 1 | 0 | 12,000 | −12,000 | 0 | 1 | `hasData` | 다른 지점에서 처리한 P6 환불도 원결제 지점 1에서 차감 |
| 12월, 원결제일 | 1 | 0 | 0 | 0 | 0 | 0 | `noData` | P6 환불은 11월에 귀속 |
| 12월, 두 기준 | 2 | 9,000 | 0 | 9,000 | 1 | 0 | `hasData` | P7. 지점 2의 환불 처리(P6)는 지점 2 매출과 무관 |
| 10월, 처리일 | 1 | 10,000 | 0 | 10,000 | 1 | 0 | `hasData` | P1 환불은 11월에 처리 |
| 10월, 원결제일 | 1 | 10,000 | 10,000 | 0 | 1 | 1 | `hasData` | 값이 있는 0. 11월의 환불로 10월 수치가 바뀜 |

11월·처리일·지점 1의 용도별 소계:

| 용도 | 승인액 | 환불액 | 순매출 | `dataStatus` |
| --- | --- | --- | --- | --- |
| 이용권 | 27,000 | 10,000 | 17,000 | `hasData` |
| 스터디룸 | 6,000 | 6,000 | 0 | `hasData` |
| 사물함 | 20,000 | 0 | 20,000 | `hasData` |

11월·원결제일·지점 1의 이용권 소계는 27,000 / 12,000 / 15,000이고 나머지 용도는 같다.

11월·처리일·`branchIds=1&branchIds=2`·`sort=netAmount` 지점 비교는 지점 1(37,000, `hasData`), 지점 2(0, `noData`) 순서다. `totals`는 53,000 / 16,000 / 37,000이다.

### 이용권 지표(지점 1)

| 조건 | 결제 건수 | 판매 수량 | 환불 수량 | 순판매량 | 발급 건수 | 설명 |
| --- | --- | --- | --- | --- | --- | --- |
| 11월, 처리일 | 2 | 2 | 1 | 1 | 2 | 판매 P3·P6, 환불 P1. 실패 P4 제외 |
| 11월, 원결제일 | 2 | 2 | 1 | 1 | 2 | 환불 P6 |
| 10월, 처리일 | 1 | 1 | 0 | 1 | 1 | |
| 10월, 원결제일 | 1 | 1 | 1 | 0 | 1 | 환불된 판매는 순판매량에서 빠지고 발급 건수에 남음 |
| 12월, 처리일 | 0 | 0 | 1 | −1 | 0 | 이전 기간 판매의 환불 |

### 주 추세(11월, 처리일, 지점 1, `measure=revenue`)

2026-11-01은 일요일이고 11-02·11-30이 월요일이다.

| `label` | `start` ~ `end` | `partial` | 승인액 | 환불액 | 순매출 | `dataStatus` |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-10-26 | 11-01 ~ 11-02 | true | 6,000 | 0 | 6,000 | `hasData` |
| 2026-11-02 | 11-02 ~ 11-09 | false | 20,000 | 16,000 | 4,000 | `hasData` |
| 2026-11-09 | 11-09 ~ 11-16 | false | 12,000 | 0 | 12,000 | `hasData` |
| 2026-11-16 | 11-16 ~ 11-23 | false | 0 | 0 | 0 | `noData` |
| 2026-11-23 | 11-23 ~ 11-30 | false | 0 | 0 | 0 | `noData` |
| 2026-11-30 | 11-30 ~ 12-01 | true | 15,000 | 0 | 15,000 | `hasData` |

구간 합 53,000 / 16,000 / 37,000은 같은 조건의 매출과 같다. `aggregation.partial`은 `firstBucketPartial=true`, `lastBucketPartial=true`, `periodOpen=false`다.

### 원장(11월, 지점 1)

처리일 기준은 승인 P2·P5·P6·P3(+53,000)과 환불 P1(`basisAt` 11-02)·P2(11-03)(−16,000)의 6항목이고 합은 37,000이다. 원결제일 기준은 같은 승인 4항목과 환불 P2(`basisAt` = P2 승인 11-01 00:30)·P6(11-10 10:00, `processingBranchId=2`)의 6항목이고 합은 35,000이다. 두 경우 모두 `meta.total = aggregation.sourceRowCount = 6`이다.

## 운영 홈 요약(FR-DASH-02·03·05·06, M9-D08)

`GET /home/summary?scope=&mine=&assigneeId=`

2026-11-06: **메시지 카드를 뺀다**([ADR-M10-002](../../db/ADR-M10-002.md) M10-D11, SRS v1.14 FR-DASH-05). 팀 채팅이 범위에서 빠져 그 자리는 Frontend의 데이터 없는 AI 채팅 바로가기로 바뀐다. 아래 표와 설명의 메시지 행, `messages`·`HomeMessageCard`·`HomeChannelItem`·`link.screen=channel`은 당시 계약으로 남기고, `openapi.json`과 계약 테스트에서 지우는 일은 #90 구현에서 함께 한다.

홈은 원본 기능의 같은 조건 집계를 묶는다. 집계 로직을 다시 만들지 않고 M5·M7·M8의 서비스 진입점을 호출한다(#90 범위 제외 항목). 매출 정보는 담지 않으므로 `sales:read`가 필요 없다.

### 범위와 권한

- `scope`는 공통 규칙이다(`all`은 유효 소속 지점의 합집합, OWNER는 전체). scope 지점에 유효 소속이 없으면 403이다. 적용 지점을 `meta.branchIds`로 돌려준다.
- 각 카드는 원본 기능의 권한·범위를 **넘지 않는다**. 홈이 원본 목록에서 볼 수 없는 행을 세거나 보여 주지 않는다. 예외는 [오늘 근무의 같은 지점 동료](#같은-지점-동료의-오늘-근무2026-11-05-사용자-결정) 하나다.

| 카드 | 원본 권한·범위 |
| --- | --- |
| 좌석 | M5 `GET /seats/occupancy`: scope 지점의 유효 소속 |
| 업무 | M7 `GET /tasks`: scope 지점의 유효 소속(지점 업무 전체를 볼 수 있음) |
| 문의 | M7 `GET /inquiries`: scope 지점의 유효 소속. 고객 정보는 홈에 담지 않음 |
| 점검 필요 | M7 `GET /facilities`: scope 지점의 유효 소속 |
| 근무 | M8 `GET /shifts`: **M8-D02 조회 범위**. OWNER는 전체, 기록 지점의 MANAGER는 그 지점 전체, 그 외는 본인 근무만(`count`·`items`). 같은 지점 동료의 이름·근무 시각만 `colleagues`로 따로 담는다 |
| 공지 | M7 `GET /notices`: 공지 공개 범위(게시 중이고 대상 지점이 scope와 겹치는 공지, 전체 공지) |
| 메시지 | M7 `GET /channels`: 본인이 참여한 채널만 |

- 근무 카드의 `count`·`items`는 M8-D02 조회 범위 그대로라 일반 직원에게는 **본인 근무만** 들어간다. 2026-11-05 이전 초안은 FR-DASH-05의 "근무 동료"를 OWNER와 그 지점 MANAGER에게만 채웠다. 사용자 결정으로 같은 지점 동료의 이름·근무 시각을 `colleagues`에 따로 담는다(아래 절).

#### 같은 지점 동료의 오늘 근무(2026-11-05 사용자 결정)

FR-DASH-05의 "근무 동료"를 일반 직원에게도 보여 주기 위한 **홈 카드만의 예외**다. ADR-M8-001 변경 이력과 ADR-M9-001 M9-D08에 같은 내용을 덧붙였다.

- **대상 지점**: scope 안에서 요청자가 **유효(활성) 소속**인 지점. 역할(STAFF·MANAGER)과 무관하다. 소속이 회수된 지점과 소속이 없는 지점은 제외한다. 소속 행이 없는 OWNER의 `colleagues`는 빈 배열이다(OWNER는 `items`에서 전체를 본다).
- **대상 근무**: 그 지점에 기록된 취소되지 않은 예정 근무 중 오늘과 겹치는 것(`startsAt < dayEnd ∧ endsAt > dayStart`). 요청자 본인 근무는 `items`에 있으므로 제외한다. 그 지점의 활성 소속인 직원의 근무만 담는다. 다른 지점의 근무는 같은 직원이라도 담지 않는다.
- **담는 값**: `branchId`, `employeeName`, `startsAt`, `endsAt`만이다(`HomeShiftColleague`). 근무 ID, 직원 ID, 예정 휴게, 근태(출퇴근 여부·실근무 분), 메모, 변경 이력, 상세 이동(`link`)은 담지 않는다. 상세로 이동할 수 없으므로 원본 상세 API의 조회 범위도 그대로다.
- **순서·개수**: `startsAt` 오름차순, 같으면 `endsAt`, `employeeName`, `branchId` 오름차순, 그래도 같으면 서버 내부의 근무 ID 오름차순(응답에는 담지 않음). 5개까지 담고 전체 수는 `colleagueCount`다.
- **원본 재현 불가**: 일반 직원의 `colleagues`는 `GET /shifts`로 재현되지 않는다. 원본 목록은 M8-D02대로 본인 근무만 주기 때문이다. `count`·`items`는 모든 사용자에게 원본과 같고, MANAGER·OWNER는 `colleagues`의 근무도 기존처럼 원본 목록에 있다. #90의 원본 일치 테스트는 `count`·`items`로 하고, `colleagues`는 위 대상 조건과 금지 필드를 따로 검증한다.
- **바꾸지 않는 것**: `GET /shifts`, `GET /shifts/weekly`, `GET /shifts/{id}`, 근태 API(`/attendance-records`, `/me/attendance/*`)의 조회 범위(M8-D02)와 M8 schema는 그대로다.
- **workspace 이벤트**: `shifts` 이벤트의 전달 대상(기록을 볼 수 있는 OWNER·기록 지점 MANAGER·직원 본인)은 바꾸지 않는다. 동료에게 보내면 다른 직원의 근무 ID(`entityId`)가 전달되어 이 예외가 막은 정보가 새고, 홈 요약에는 실시간 갱신 계약이 없다. 홈은 진입·지점 변경·화면 복귀 때 다시 조회해 갱신한다(공통 목록 규칙의 재조회). 동료 근무 변경이 즉시 보이지 않는 것은 허용한다.

### 오늘

"오늘"은 요청 시각(`asOf`)의 Asia/Seoul 날짜다. `meta.dayStart`는 오늘 0시, `meta.dayEnd`는 내일 0시(제외)의 UTC다. 예: 2026-11-05 10:00(Asia/Seoul) 요청은 `date=2026-11-05`, `dayStart=2026-11-04T15:00:00Z`, `dayEnd=2026-11-05T15:00:00Z`다.

### 업무 조건(FR-DASH-03)

| 요청 | OWNER | 그 외 직원(MANAGER 포함) |
| --- | --- | --- |
| `mine`·`assigneeId` 없음 | 선택 범위 전체 업무(`mine=false`) | 본인 담당 업무(`mine=true`) |
| `mine=true` | 본인 담당 | 본인 담당 |
| `mine=false` | 선택 범위 전체 | 선택 범위 전체(M7상 지점 업무를 볼 수 있음) |
| `assigneeId=X` | 그 담당자 | 그 담당자 |
| `mine=true`와 `assigneeId` | 422 | 422 |

M9-D08의 "직원"은 OWNER가 아닌 모든 사용자로 해석했다. MANAGER도 기본은 본인 업무이고 `mine=false`로 지점 전체를 본다. 적용한 조건은 `meta.taskFilter`(`defaulted`는 기본값을 적용했는지)로 돌려준다. 조건이나 지점이 바뀌면 Frontend는 이전 요청을 취소하고 다시 조회한다(공통 목록 규칙).

### 오늘의 업무와 완료율

- **오늘의 업무** = (완료가 아니고 `dueAt < dayEnd`인 업무) + (완료이고 `completedAt ∈ [dayStart, dayEnd)`인 업무). 기한이 지난 미완료 업무도 포함한다. 기한 없는 미완료 업무와 내일 이후 기한의 미완료 업무는 제외한다. 오늘 완료한 업무는 기한과 무관하게 포함한다.
- **완료율** = 오늘 완료 ÷ 오늘의 업무 × 100(`Ratio`). 오늘의 업무가 0건이면 `notApplicable`이다.
- `tasks.today`는 `meta.taskFilter` 조건, `tasks.mine`은 항상 요청자 본인 담당 업무다. 두 값을 함께 줘 FR-DASH-05의 "오늘의 업무와 내 업무 구분"을 만족한다.
- `overdue`는 오늘의 업무 중 `dueAt < asOf`이고 완료가 아닌 것이다(M7의 `overdue` 정의와 같다).
- `items`는 오늘의 업무 중 완료가 아닌 것을 `dueAt` 오름차순, 같으면 `id` 오름차순으로 5개까지 담는다.

| 업무 | 상태 | 기한 | 완료 | 오늘의 업무 |
| --- | --- | --- | --- | --- |
| T1 | 할 일 | 11-04 18:00 | — | 포함(기한 지남, `overdue`) |
| T2 | 진행 중 | 11-05 20:00 | — | 포함 |
| T3 | 할 일 | 11-06 09:00 | — | 제외 |
| T4 | 할 일 | 없음 | — | 제외 |
| T5 | 완료 | 11-07 | 11-05 09:00 | 포함(오늘 완료) |
| T6 | 완료 | 11-04 | 11-04 17:00 | 제외 |

2026-11-05 10:00 기준 `total=3`, `open=2`, `overdue=1`, `completedToday=1`, 완료율 `33.3`이다.

### 카드 정의와 원본 목록 재현

각 카드는 `source`(또는 업무의 `sources`)에 같은 수치를 재현하는 원본 operation과 조건을 담는다. 같은 요청자·같은 시점이면 원본 결과와 홈 수치가 같다(#90 인수 조건). `link`는 이동 대상이다.

| 카드 | 홈 수치 | 원본 operation과 조건 | 이동 대상(`link.screen`) |
| --- | --- | --- | --- |
| 좌석 | `inUse`·`total`·`available`·`blocked` = 지점별 합, `occupancy` = Σ`inUse` ÷ Σ`total` | `getSeatOccupancy` `scope` | `seat-board` |
| 업무 | `today.open` | `listTasks` `scope`, `mine`/`assigneeId`, `dueTo=dayEnd`, `status=할 일`의 total + `status=진행 중`의 total | `task-list`, 항목은 `task-detail` |
| 업무 | `today.overdue` | `getTaskSummary` 같은 조건 + `dueTo=dayEnd`의 `overdue` | `task-list`(`overdue=true`) |
| 업무 | `today.completedToday` | 현재 `listTasks`에 완료 시각 필터가 없다. [보류](#남은-작업) 참조 | `task-list` |
| 문의 | `unresolved`(= 접수 + 처리 중) | `getInquirySummary` `scope`의 `totals.unresolved` | `inquiry-list`, 항목은 `inquiry-detail` |
| 점검 필요 | `inspectionDue` | `getFacilitySummary` `scope`의 `totals.inspectionDue` | `facility-list`(`inspectionDue=true`), 항목은 `facility-detail` |
| 근무 | `count` = 오늘과 겹치는 취소되지 않은 예정 근무 | `listShifts` `scope`, `status=예정`, `from=어제`, `to=오늘` 결과 중 `startsAt < dayEnd ∧ endsAt > dayStart` | `shift-list`, 항목은 `shift-detail` |
| 근무 | `colleagues`·`colleagueCount` | 원본 목록으로 재현되지 않음([동료 근무](#같은-지점-동료의-오늘-근무2026-11-05-사용자-결정)) | 없음 |
| 공지 | 앞 5개 | `listNotices` `scope`, `status=게시 중`, page 1, pageSize 5(고정 → 게시 시각 → id 내림차순) | `notice-list`, 항목은 `notice-detail` |
| 메시지 | `unreadCount` = Σ 채널 `unreadCount` | `listMyChannels` `scope` | `channel` |

- **근무**: 오늘의 근무 계획은 "오늘과 겹치는" 근무다(M9-D08). `GET /shifts`의 `from`·`to`는 근무 시작 날짜(`workDate`)로 거르므로, 어제 시작해 오늘 끝나는 근무를 포함하려면 `from=어제`로 조회하고 겹침을 더 걸러야 한다. 근무 길이가 24시간 이하(M8-D03)라 겹치는 근무의 `workDate`는 어제나 오늘뿐이다. 이 추가 조건을 `source.clientFilter`에 적는다.

| 근무(Asia/Seoul) | 상태 | 2026-11-05 근무 카드 |
| --- | --- | --- |
| 11-04 22:00 ~ 11-05 06:00 | 예정 | 포함(`workDate`는 11-04) |
| 11-05 09:00 ~ 18:00 | 예정 | 포함 |
| 11-05 22:00 ~ 11-06 06:00 | 예정 | 포함 |
| 11-05 09:00 ~ 18:00 | 취소 | 제외 |
| 11-04 14:00 ~ 22:00 | 예정 | 제외 |

- 근무 `items`는 `startsAt` 오름차순·`id` 오름차순 5개다. 직원은 `ActorSummary`(ID·이름)만 담는다.
- 문의 `items`는 미처리 문의를 `createdAt` 내림차순·`id` 내림차순 5개 담는다. 제목(`subject`)·상태·접수 시각만 담고 고객 정보는 담지 않는다.
- 점검 `items`는 `nextInspectionOn` 오름차순·`id` 오름차순 5개다. 점검 대상 정의는 M7-D06(다음 점검일 ≤ 오늘, 사용 중지 아님)과 같다.
- 메시지 `channels`는 읽지 않은 메시지가 있는 채널을 `lastMessageAt` 내림차순·`id` 내림차순 5개 담는다. DM은 없다(M14).
- 좌석의 `branches`는 지점 ID 오름차순이며 M5 `SeatCounts`와 같은 값이다. 점유율만 `Ratio`로 바꿔 담는다.

### 이동 대상(FR-DASH-06)

`HomeLink`는 `screen`(화면 종류), `branchId`(지점, 범위 전체·전체 공지·전체 채널이면 null), `targetId`(상세 대상 ID, 목록이면 null), `query`(목록 주소에 복원할 조건)다. 화면 주소의 모양은 Frontend가 정한다. 서버는 원본 목록과 같은 조건 이름(`status`, `mine`, `overdue`, `inspectionDue` 등)을 `query`에 담는다. 상세 이동의 권한은 원본 상세 API가 다시 검사한다.

## 성능과 응답 크기

- **기간 상한**: 모든 매출·이용권 집계는 366일 이하다(M9-D01). 무제한 전체 기간 조회가 없다.
- **행 수**:
  - 매출 요약·이용권 판매량은 고정 크기다.
  - 지점 비교는 적용 지점 수만큼이다.
  - 추세는 [구간 수 상한](#추세fr-agg-14-m9-d04)대로 최대 367행이다.
  - 상품별 판매량과 최소 원장은 공통 페이지(최대 100행)다.
  - 홈의 목록 카드는 각 5행이다.
- 원장·상품 페이지의 `totals`와 `total`은 페이지와 별도의 집계 질의로 계산한다. 한 응답에 원거래 전체를 담지 않는다.
- 집계는 원거래에서 직접 계산한다(ADR 스키마 영향). 집계 테이블·캐시는 이번 범위가 아니다(#89 제외 항목).
- **#89 검토 대상 질의**:
  - 승인 기준: `payments (branch_id, status, approved_at)` = `ix_payments_1`. 승인 결제는 `status IN ('APPROVED','REFUNDED')`로 거른다.
  - 환불 처리일 기준: `refunds.refunded_at`(`ix_refunds_1`) 범위에 원결제 지점 조인.
  - 환불 원결제일 기준: 원결제 `approved_at` 범위에서 `refunds.payment_id`(`uk_refunds_1`) 조인.
  - 상품별 판매량: `passes.purchase_payment_id`(`uk_passes_1`) 조인 후 스냅샷 열로 묶음.
  - 홈: 원본 기능의 기존 질의.
- M10은 같은 기반 위에서 자기 상한(표 50행 페이지, 결과 최대 1,000행, 차트 상위 20개, M10-D03)을 따로 적용한다.

## M10 연결

- **위치·형식**: 지표 정의는 OpenAPI 최상위 `x-metric-definitions` 배열이다. 항목마다 `id`, `name`, `unit`, `definition`, `includes`, `excludes`, `branchAttribution`, `timeBasis`, `refundBasisApplies`, `dimensions`, `source`, `operations`, `adr`가 있다. 이 문서의 [지표 정의](#지표-정의) 표가 사람이 읽는 판이다.
- **필터 가능 차원**: 매출 지표는 `branch`(원결제 지점)·`purpose`(용도)·`period`(기간·일·주·월), 이용권 지표는 `branch`·`product`(원본 상품 ID)·`category`(판매 당시 분류 코드)·`period`, 홈 업무 지표는 `branch`·`assignee`다.
- **조건 객체**: `AggregationCondition`(지점, 기간, 환불 기준, 용도, 상품, 분류, 구간 단위)이다. M10-D05대로 M10은 계산을 따로 구현하지 않고 이 조건 객체로 M9 공통 집계 기반을 호출한다. 응답의 `aggregation`을 그대로 결과 메타정보로 쓸 수 있다.
- **권한**: M10-D06의 "대상 지점 모두의 `sales:read`"는 M9-D07과 같은 판정(`READ_SALES`, 하나라도 없으면 거부)이다. M10은 거부를 403이 아니라 재입력 안내로 표현한다.
- **기간**: M10-D03의 366일 상한은 M9와 같다.
- 지표를 추가하거나 정의를 바꾸면 `x-metric-definitions`, 이 문서, 계약 테스트를 함께 바꾼다. M10 의미 카탈로그는 `id`로만 연결하고 정의 문구를 복사하지 않는다.

## 로컬 샘플과의 관계

`LocalPreviewController`(local 프로필 전용)에 `GET /api/home` 샘플이 있다. 이 계약의 홈은 `GET /home/summary`라 경로가 겹치지 않는다. 샘플은 지금 그대로 두고, #90이 운영 API를 구현하면서 Frontend 전환에 맞춰 정리한다. 샘플 목록 경로 `/api/payments`는 M4에서 이미 운영 경로와 겹쳐 있으며 이 계약과 무관하다. `/api/sales/*` 샘플 경로는 없다. `modules/home`은 구조 설명(`package-info.java`)만 있고 구현이 없다. `modules/sales`에는 #87의 공통 집계 기반과 매출·지점 비교·최소 원장, #88의 이용권 판매량·상품 순위·추세가 있다.

## 범위 밖(M10·M13)

- 자연어 질의, 동적 시각화, 확인 질문(M10)
- 결제 수단·처리자별 매출, 고급 필터(M13). 기존 `GET /payments`의 고급 필터도 M13이다
- 좌석 점유 추이(M13, #88 제외 항목)
- 집계 테이블·캐시(#89 제외 항목)

## 정책이 정하지 않아 이 계약에서 정한 것

| 항목 | 이 계약의 결정 | 이유 |
| --- | --- | --- |
| 366일 초과의 상태 코드 | 422 `PERIOD_TOO_LONG`(ADR·#87 문구는 400) | 공통 계약이 입력 범위 오류를 422로 쓰고 400 정의가 없다. **사용자 확정: 422**(2026-11-05, ADR 수정) |
| `branchIds` 생략 | `sales:read`가 있는 모든 지점. 하나도 없으면 403 | 공통 `scope=all`의 "허용 지점 합집합"과 같다. 적용 지점을 메타로 돌려줘 숨은 제외가 없다 |
| 없는 지점 ID | 403 | 공통 `Scope`의 "접근 불가 ID는 403". 존재 여부를 알리지 않는다 |
| 기간 입력 | 오프셋 있는 시각, `from`·`to`는 함께 보내거나 함께 생략, `from < to` | M8 근무 시각과 같은 형식. 한쪽 기본값을 추측하지 않는다 |
| 366일의 측정 | `to − from` ≤ 31,622,400초 | 시각 입력이라 날짜 수가 아니라 길이로 잰다. Asia/Seoul에 일광 절약 시간이 없다 |
| 이용권 환불 수량의 환불 기준 | 매출과 같은 `refundBasis`를 따른다 | M9-D02의 "카드·표·차트가 같은 기준". `paymentDate`를 고르면 SRS의 "결제 성공일 기준 판매에서 해당 판매의 환불 제외"와 같다 |
| 결제 건수 | 나중에 환불된 승인 결제도 센다. 환불 건수는 따로 준다 | M9-D06 "결제 건수 = 승인 결제 수" |
| 발급 시각 | 원결제 `approved_at` | `passes.created_at`은 DB 기본값이라 결제 시각과 어긋날 수 있다 |
| 상품 행 기준 | 상품 ID + 판매 당시 상품명·분류 코드·분류 이름. 환불은 판매 스냅샷 행에서 뺀다 | FR-AGG-06의 "판매 당시 상품·분류 스냅샷" |
| 순위 | 순판매량 경쟁 순위. 동률 순서는 상품 ID → 상품명 → 분류 코드 → 분류 이름 | FR-AGG-07의 "일관된 정렬 기준" |
| 판매 없는 상품 | 행 없음 | 데이터 없음은 빈 결과(FR-AGG-10) |
| 음수 | 순매출·순판매량은 음수를 그대로 반환 | 처리일 기준에서 실제로 생긴다. 0으로 바꾸면 합계가 맞지 않는다 |
| 데이터 없음의 표현 | 합계는 0 + `dataStatus=noData`, 목록은 빈 `items` | 지점 비교의 "거래 없는 지점은 0"과 "0과 조회 대상 없음 구분"을 함께 만족 |
| 공통 메타의 위치 | 응답의 `aggregation` 필드 | 페이지 응답의 `meta`(`PageMeta`)와 충돌하지 않게 |
| 홈 메타 | `HomeMeta`를 따로 둔다 | 홈에는 기간·환불 기준·통화가 없다. 공통 집계 메타는 매출·이용권 집계 6개에 적용 |
| 추세의 `measure` | 매출과 이용권 수량을 한 응답에 섞지 않는다 | 필터 적용 대상과 `sourceRowCount`를 하나로 정하기 위해서 |
| 추세 구간 수 상한 | 따로 두지 않음(일 최대 367) | 366일 상한에서 이미 작다 |
| 원장 필드 | 고객 ID·결제 수단·처리자·환불 사유 제외, `processingBranchId`·`canViewPayment` 포함 | 비식별·최소 원장. 처리 지점은 귀속 확인에 필요하다 |
| 룸·사물함 원장 상품명 | null, 용도로 표시 | 판매 당시 이름 스냅샷이 없다 |
| 원장 정렬 | `basisAt` → 승인/환불 → 원천 ID, `direction` 공통 적용 | 같은 조건에서 순서 고정 |
| 지점 비교 정렬 | `netAmount` 기본, `requested`는 보낸 순서. 동률은 `branchId` 같은 방향 | FR-AGG-02의 "지정한 순서", 공통 목록의 id 동률 규칙 |
| 기술용 파라미터 값 | `refundBasis`·`granularity`·`measure`·`sort`·`dataStatus`·`Ratio.state`는 영문 토큰, 용도·상태는 기존처럼 한국어 | 기존 `sort` 값은 영문, 저장 상태 표시는 한국어인 관례 |
| 홈 경로 | `GET /home/summary` | 로컬 샘플 `GET /api/home`과 겹치지 않게 |
| 홈의 "직원" | OWNER가 아닌 모든 사용자(MANAGER 포함) | M9-D08이 OWNER와 직원만 구분한다 |
| 오늘 완료한 업무 | 기한과 무관하게 포함 | M9-D08 문구가 기한 조건을 미완료 업무에만 붙였다 |
| `mine=true`와 `assigneeId` | 422 | 두 조건이 다르면 어느 쪽인지 모호하다 |
| 홈 목록 카드 | 카드마다 5개 | 홈은 요약이고 전체는 원본 목록으로 이동한다 |
| 근무 동료 | 일반 직원에게는 본인 근무만(초안). **2026-11-05 사용자 결정으로 대체**: 같은 지점 동료의 이름·근무 시각을 `colleagues`로 공개 | FR-DASH-05. 근무표·근태 API의 M8-D02 범위는 그대로 |
| 동료 근무 대상 | 요청자가 활성 소속인 지점의 활성 소속 동료, 본인 제외, 역할 무관 | 사용자 결정의 "모든 유효 소속"을 따르고, 퇴사·소속 회수자를 보이지 않게 |
| 동료 근무 개수 | 5개 + `colleagueCount` | 홈 목록 카드의 5개 규칙 |
| 동료 근무의 workspace 이벤트 | 전달 대상 변경 없음, 홈은 재조회로 갱신 | 근무 ID 노출 방지, 홈에 실시간 계약 없음 |

## 남은 작업

- **400/422**: 2026-11-05 사용자가 422로 확정했고 ADR-M9-001을 고쳤다. **#87 이슈 본문의 인수 조건 "400" 문구를 422로 맞춰야 한다**(이 커밋에서는 이슈를 고치지 않았다).
- **#90 업무 원본 필터**: "오늘 완료한 업무"를 원본 목록으로 재현할 조건이 `GET /tasks`·`GET /tasks/summary`에 없다(완료 시각 필터 없음, `status`는 한 값). 기존 operation은 바꾸지 않았다. #90에서 `completedFrom`·`completedTo` 같은 필터를 더할지(기존 M7 operation 변경), 홈 전용 수치로 두고 원본 대조를 서비스 테스트로 할지 정해야 한다.
- **#90 근무 겹침 조건**: `GET /shifts`가 시작 날짜로만 거르므로 홈 근무 수치는 원본 목록 + 겹침 조건으로 재현된다. 원본에 겹침 필터를 더할지 #90에서 정한다.
- **#87 처리 지점이 다른 환불**: 현재 API는 처리 지점을 원결제 지점으로 고정한다(M4-D08). 귀속 규칙 테스트는 `refunds.processing_branch_id`를 다른 지점으로 둔 fixture로 해야 한다.
- **#89**: 위 대표 질의의 실행 계획을 확인한다. 원결제일 기준 환불과 상품별 판매량이 ADR이 지목한 후보다.
