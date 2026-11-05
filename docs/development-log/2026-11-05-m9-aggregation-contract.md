# 2026-11-05 M9 정형 집계·최소 원장·홈 요약 API 계약

관련: Issue [#86](https://github.com/OpenStudyService/OpenStudy-Backend/issues/86), [계약 문서](../design/foundation/aggregation-api.md), [ADR-M9-001](../db/ADR-M9-001.md), [M9 정책 확정 기록](2026-10-28-m9-aggregation-policy.md), [M10 정책 확정 기록](2026-10-28-m10-nl-analysis-policy.md).

## 1. 배경 / 문제

M9 정책(M9-D01~D09)은 확정됐지만 매출·판매량·추세·최소 원장·홈 요약 API 설계가 없었다. 공통 집계 기반·매출·원장(#87), 판매량·순위·추세(#88), 실행 계획(#89), 홈(#90) 구현과 Frontend 연동, M10 자연어 분석이 같은 지표 정의를 쓰려면 operation, 지표 정의, 공통 메타정보, 값 표현, 기간·권한·오류 규칙을 먼저 고정해야 했다. 이슈 범위는 계약(문서·OpenAPI·계약 테스트)이고 서버 구현과 스키마 변경은 제외한다.

지표 정의가 실제 저장 값과 맞는지도 확인해야 했다. `payments`, `refunds`, `passes`, `product_revisions`, `room_reservations`, `locker_assignment_charges`, `work_tasks`, `shifts`, `seat_sessions`의 V1~V6 열과 `PaymentService`의 승인·실패·환불 처리, `BranchAction`·`AuthorizationService`의 권한 판정을 읽었다.

확인한 사실:

- 결제는 요청 안에서 `APPROVED`(+`approved_at`) 또는 `FAILED`(+`failed_at`)로 확정된다. `PENDING` 행은 만들지 않는다. 환불은 상태만 `REFUNDED`로 바꾸고 금액·승인 시각을 유지한다.
- 환불은 결제당 한 번, 전액이다(`uk_refunds_1`). 이용권 환불은 `POST /payments/{id}/refund`, 룸 환불은 예약 취소에서 생기고, 사물함은 환불하지 않는다.
- `refunds.processing_branch_id`는 현재 항상 원결제 지점이다(`Refund.full`, M4-D08). 스키마는 다른 지점을 허용한다.
- `passes.created_at`은 DB 기본값으로 채워지고 `payments.approved_at`은 애플리케이션 시계 값이다.
- `sales:read`·`payments:read`는 V1 권한 정의에 있고 `BranchAction.READ_SALES`·`READ_PAYMENTS`가 이미 있다. 새 권한은 필요 없다.
- `LocalPreviewController`에 local 전용 `GET /api/home` 샘플이 있다. `/api/sales/*` 샘플은 없다. `modules/home`·`modules/sales`는 `package-info.java`만 있다.

## 2. 검토한 선택지

- **366일 초과의 상태 코드**
  - ADR-M9-001 M9-D01과 #87 인수 조건의 400: 기존 공통 계약에 400 정의가 없다. 같은 "범위 초과"가 룸 달력(62일 초과 422)과 다른 코드가 되고 Frontend가 집계만 따로 처리해야 한다.
  - 공통 계약의 422: 기존 입력 오류 처리와 같다. 이쪽을 택했다. 전용 code `PERIOD_TOO_LONG`을 두어 화면이 "기간 줄이기"를 안내할 수 있게 했다.
- **지점 지정 방식**
  - 공통 `scope`(전체 또는 한 지점): 지점 비교에 여러 지점을 넣을 수 없다.
  - 반복 `branchIds`: 여러 지점을 받고, 생략하면 `sales:read`가 있는 모든 지점이다. 이쪽을 택했다. 홈은 원본 기능처럼 `scope`를 쓴다.
- **`branchIds` 생략 시 권한 없는 지점**: 403과 "`sales:read`가 있는 지점만"을 비교했다. 생략은 사용자가 지점을 지정하지 않은 것이고 공통 `scope=all`이 "허용 지점의 합집합"이므로 후자를 택했다. 적용 지점을 메타로 돌려준다. 지정한 지점은 M9-D07대로 하나라도 권한이 없으면 403이다.
- **데이터 없음의 표현**
  - 합계 지표를 null로 둔다: 지점 비교의 "거래 없는 지점은 0"(#87)과 충돌한다.
  - 합계는 0, 별도 `dataStatus`로 구분한다: 두 요구를 함께 만족한다. 이쪽을 택했다.
- **비율 표현**
  - M5 `SeatCounts.occupancyRate`처럼 숫자 또는 null: null이 "해당 없음"인지 "누락"인지 schema만으로 구분되지 않는다.
  - `Ratio` 객체(`state`, `percent`, 분자, 분모): 해당 없음이 schema로 고정된다. 이쪽을 택했다. 기존 M5 operation은 바꾸지 않고 홈에서만 바꿔 담는다.
- **공통 메타의 위치**: 응답 `meta`와 별도 `aggregation`을 비교했다. 페이지 응답의 `meta`는 공통 규칙상 `PageMeta`이므로 `aggregation`을 택했다.
- **추세 응답**: 한 구간에 매출과 이용권 수량을 함께 담는 안과 `measure`로 나누는 안을 비교했다. 함께 담으면 `purpose` 필터가 이용권 수량에 적용되는지와 `sourceRowCount`가 모호해져 `measure`를 택했다.
- **이용권 환불 수량의 기준 시각**
  - 항상 원결제일: SRS FR-PASS-05·AGG-06의 "결제 성공일 기준 판매에서 해당 환불 제외" 문구를 그대로 따른다. 다만 같은 화면의 매출(처리일 기본)과 기준이 달라진다.
  - 요청의 `refundBasis`를 따른다: M9-D02의 "카드·표·차트가 같은 기본값"과 맞고, `paymentDate`를 고르면 SRS 문구와 같다. 이쪽을 택했다.
- **발급 시각**: `passes.created_at`과 원결제 `approved_at`을 비교했다. 앞의 것은 DB 기본값이라 자정 직전 결제에서 결제 건수와 발급 건수가 다른 날로 갈릴 수 있어 뒤의 것을 택했다.
- **상품 순위의 행 기준**: 상품 ID만으로 묶는 안과 판매 당시 스냅샷(상품 ID·상품명·분류)으로 묶는 안을 비교했다. #88 인수 조건("바꾸기 전 판매가 판매 당시 이름·분류로 집계")을 따라 뒤의 것을 택했다.
- **지표 정의의 M10 참조 위치**: 문서 표만 두는 안과 OpenAPI 최상위 `x-metric-definitions`(기계가 읽는 정본) + 응답 필드의 `x-metric-id` + 문서 표를 두는 안을 비교했다. M10 의미 카탈로그가 `id`로 1:1 연결할 수 있게(M10-D05) 뒤의 것을 택하고 계약 테스트가 문서 표와 대조하게 했다.
- **홈 경로**: `GET /home`과 `GET /home/summary`를 비교했다. local 샘플 `GET /api/home`과 겹치지 않게 뒤의 것을 택했다.

## 3. 결정 및 이유

- operation 7개를 정의했다. 매출·지점 비교·최소 원장(#87), 이용권 판매량·상품 순위·추세(#88), 홈 요약(#90)이다. 모두 `design-target`이다.
- 지표 13개(매출 5, 이용권 5, 홈 업무 3)를 포함·제외·귀속 지점·기준 시각·단위·환불 기준 영향·차원과 함께 정의했다. 매출은 세 용도 모두, 이용권 지표는 이용권만이다(M9-D03). 환불은 원결제 지점에서 차감한다(M9-D05). 결제 건수·순판매량·발급 건수를 단위와 함께 따로 둔다(M9-D06).
- 모든 매출·이용권 집계에 같은 `AggregationMeta`(적용 조건, 기본 기간 여부, 시간대, 통화, 기준 시각, 행 수, 원거래 항목 수, 데이터 상태, 부분 구간, 지표 목록)를 둔다.
- 최소 원장 합계가 매출 집계와 같아야 한다는 규칙을 항목 정의(승인 +금액, 환불 −금액, `basisAt`)로 고정했다. 원장 `total`은 `sourceRowCount`와 같다.
- 홈 카드마다 원본 operation과 조건(`source`)을 담고, 근무 카드는 M8-D02를 넘지 않게 했다. 일반 직원에게 FR-DASH-05의 "근무 동료"는 보이지 않는다.
- 계산 예시는 두 환불 기준, 기간 경계를 넘는 환불(10월 판매·11월 환불, 11월 판매·12월 환불), 다른 지점에서 처리한 환불, 자정 전후 시간대, 값이 있는 0과 데이터 없음, 음수 순매출, 주 추세의 부분 구간을 포함한다.
- 정책이 정하지 않은 판단은 계약의 "정책이 정하지 않아 이 계약에서 정한 것" 표에 모았다.
- ADR-M9-001은 바꾸지 않았다. 바꿔야 할 수 있는 항목은 M9-D01의 "400"뿐이며 사용자 확인 사항으로 남겼다.

## 4. 구현 내용

- `docs/design/foundation/aggregation-api.md` 신규
- `docs/design/foundation/openapi.json`:
  - 버전 `0.1.11-m9-aggregation`, `info.description`에 M9 문장 추가
  - operation 7개, 태그 2개(`sales`, `home`)
  - 공통 parameter 7개(`AggregationBranchIds`, `AggregationFrom`, `AggregationTo`, `RefundBasis`, `SalesPurpose`, `PassProductId`, `PassCategoryCode`)
  - schema 39개(`AggregationMeta`, `AggregationCondition`, `Ratio`, `DataStatus`, `RevenueSummary`, `SalesLedgerPage`, `SalesTrend`, `HomeSummary` 등)
  - 최상위 `x-metric-definitions`
- `src/test/java/com/openstudy/architecture/AggregationContractTests.java` 신규, 계약 테스트 18개
- `src/test/java/com/openstudy/architecture/WorkforceContractTests.java`: OpenAPI 버전을 `0.1.10-m8-workforce`와 정확히 비교하던 단언을 "0.1.10 이상"으로 바꿨다. M8 계약이 실린 뒤 버전이 오르는 것은 정상이고, 바꾸지 않으면 새 계약마다 M8 테스트가 실패한다. M8 operation·설명 단언은 그대로다.
- `docs/design/foundation/README.md` 안내 추가

서버 코드, 마이그레이션, `LocalPreviewController`, ADR은 바꾸지 않았다. 기존 operation과 schema도 바꾸지 않았다.

**스키마 대조**: 계약의 모든 지표·원장 필드를 V1~V6 열로 표현할 수 있다. 판매 당시 상품명·분류는 `passes.snapshot_*`, 원장 이용권 상품명은 `payments.product_name_snapshot`에서 읽는다. 룸·사물함 결제에는 이름 스냅샷이 없어 원장 상품명을 null로 두었다. 홈 업무의 "오늘 완료"는 `work_tasks.completed_at`으로 계산한다.

## 5. 검증 결과

- `npx --no-install @apidevtools/swagger-cli validate docs/design/foundation/openapi.json`: valid
- 스크립트 확인: `$ref` 2402개가 모두 해석됨, operationId 159개 중복 없음
- `git diff --diff-algorithm=histogram --numstat`: openapi.json은 2988줄 추가, 2줄 삭제다. 삭제된 두 줄은 `info.version`과 `info.description`이다. 기존 operation과 schema는 바뀌지 않았다. 파일 형식(2칸 들여쓰기, LF, BOM 없는 UTF-8, 한글 그대로, 끝 줄바꿈)은 기존과 같다.
- 계산 예시(두 환불 기준의 10·11·12월 매출, 용도별 소계, 이용권 지표, 주 추세 6구간, 원장 합계), 기간 예시(366일·윤년·기본 기간의 UTC 변환), 비율 반올림 예시는 Python으로 다시 계산해 문서 값과 일치함을 확인했다.
- `./gradlew cleanTest test --tests 'com.openstudy.architecture.*'`: 통과. 74개(신규 `AggregationContractTests` 18개 포함), 실패 0

**실행하지 않은 것**

- 전체 테스트는 실행하지 않았다. 이번 변경은 문서·OpenAPI·아키텍처 테스트뿐이다.
- 지표 계산이 실제 DB에서 이 정의대로 나오는지는 구현 이슈(#87·#88)의 HTTP·DB 통합 테스트에서 검증해야 한다. 이 계약은 정의와 예시만 고정한다.
- 홈 카드가 원본 API와 같은 값을 내는지는 #90에서 검증한다.
- 실행 계획과 응답 시간은 측정하지 않았다(#89).
- Frontend 측 계약 검토와 API 엑셀(`OpenStudy-Foundation-API.xlsx`) 동기화는 하지 않았다.

## 6. 남은 작업 / 보류 사항

- **사용자 확인**: ADR-M9-001 M9-D01과 #87 인수 조건은 366일 초과를 400으로 적었다. 계약은 422 `PERIOD_TOO_LONG`이다. 400을 고르면 계약의 해당 응답만 바꾸면 되고, 422를 확정하면 ADR 변경 이력과 #87 인수 조건 문구를 맞춰야 한다.
- **#90 업무**: "오늘 완료한 업무"를 원본 목록으로 재현할 조건이 `GET /tasks`·`GET /tasks/summary`에 없다(완료 시각 필터 없음, `status`는 한 값). `completedFrom`·`completedTo` 같은 필터를 기존 M7 operation에 더할지 #90에서 정해야 한다.
- **#90 근무**: `GET /shifts`는 시작 날짜(`workDate`)로만 거른다. 홈의 "오늘과 겹치는 근무"는 `from=어제` 조회에 겹침 조건을 더해야 재현된다. 원본에 겹침 필터를 더할지 #90에서 정한다.
- **#90 근무 동료**: M8-D02 때문에 일반 직원은 홈에서 다른 직원의 근무를 보지 못한다. FR-DASH-05의 "근무 동료"를 일반 직원에게 보여야 한다면 M8-D02 조정이 필요하다.
- **#90 로컬 샘플**: `GET /api/home` 샘플은 운영 API 전환 때 정리한다.
- **#87**: 처리 지점이 원결제 지점과 다른 환불은 현재 API로 만들 수 없다. 귀속 규칙 테스트는 fixture로 `processing_branch_id`를 바꿔야 한다. 공통 조건 객체를 M10이 호출할 서비스 경계로 분리한다.
- **#89**: 원결제일 기준 환불(원결제 `approved_at` 범위 + `refunds.payment_id` 조인)과 상품별 판매량(`passes.purchase_payment_id` 조인 후 스냅샷 열 묶음)이 ADR이 지목한 인덱스 후보다.
- FR-AGG-02의 "권한이 있는 요청 지점만 결과에 포함한다"는 M9-D07(하나라도 없으면 403)과 문구가 다르다. 계약은 ADR을 따랐다. SRS 문구 정리가 필요하면 별도로 한다.
