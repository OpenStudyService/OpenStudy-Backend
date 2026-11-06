# 2026-11-06 이용권 판매량·상품 순위·기간 추세 API

관련: Issue [#88](https://github.com/OpenStudyService/OpenStudy-Backend/issues/88), [계약 문서](../design/foundation/aggregation-api.md), [ADR-M9-001](../db/ADR-M9-001.md) M9-D01~D07·D09, [#87 개발 기록](2026-11-05-sales-revenue-ledger.md).

## 1. 배경 / 문제

#86 계약 중 #88 몫인 `GET /sales/passes/volume`, `GET /sales/passes/products`, `GET /sales/trend`를 구현해야 했다. #87이 공통 조건(`AggregationConditions`)·메타(`AggregationMetas`)·원거래 WHERE 조각(`SalesSourceRepository`)을 만들어 두었으므로, 이번 작업의 요구는 그 위에서 다음을 맞추는 것이었다.

- 결제 건수(건)·순판매량(개)·발급 건수(개)를 따로 돌려준다. 환불된 판매는 순판매량에서 빠지고 발급 건수에 남는다(M9-D06).
- 상품명·분류를 바꾼 뒤에도 바꾸기 전 판매는 판매 당시 스냅샷 행으로 집계한다(FR-AGG-06).
- 순위는 같은 요청에 항상 같은 순서로 나온다(FR-AGG-07).
- 추세는 월요일 시작 주, 빈 구간 0, 경계 부분 구간 표시, 구간 합 = 전체 합(M9-D04).

## 2. 검토한 선택지

- **상품 행의 정렬·순위·페이지 위치**
  - SQL에서 `RANK()`·`ORDER BY`·`LIMIT/OFFSET`: 페이지만 읽지만, `totals`와 `meta.total`을 위한 별도 질의가 필요하고 행·합계가 다른 읽기에서 나온다.
  - SQL은 스냅샷별 `GROUP BY`까지만 하고 정렬·순위·페이지는 Java: 행 수는 상품 수 × 이름 변경 수 정도라 작다. 행·순위·합계가 같은 그룹 결과 하나에서 나와 서로 어긋날 수 없다. 이쪽을 택했다. 원거래 행은 읽지 않는다.
- **추세 구간을 나누는 위치**
  - 구간마다 질의: 일 단위 최대 367번이다.
  - SQL에서 주·월 구간식까지 계산: 주 시작(월요일)·월 경계 규칙이 SQL과 Java 양쪽에 생긴다.
  - SQL은 Asia/Seoul 날짜별 `GROUP BY`, 구간 접기는 Java(`TrendBuckets`): 구간 경계가 모두 서울 자정이라 날짜 하나는 정확히 한 구간에 속한다. 구간 규칙이 Java 한 곳에만 있다. 이쪽을 택했다.
- **서울 날짜 계산**: MySQL `CONVERT_TZ`는 서버 시간대 테이블이 있어야 한다. Asia/Seoul은 1988년 이후 일광 절약 시간이 없는 고정 +09:00이고 `AggregationConditions`도 같은 가정(366일 = 31,622,400초)을 쓰므로 `DATE_ADD(t, INTERVAL 9 HOUR)`를 택했다.

## 3. 결정 및 이유

- **구조**
  - `PassSalesService`(판매량·상품 순위)와 `SalesTrendService`(추세)를 `SalesRevenueService`처럼 M10이 `AggregationCondition`으로 호출하는 서비스 경계로 두었다. 각 메서드는 #87과 같은 `readOnly` `REPEATABLE_READ` 트랜잭션이다.
  - `SalesSourceRepository`: `dayTotals`(매출 그룹의 날짜별 분할)와 `passTotals(filter, byDay)`(이용권 스냅샷 그룹)를 더했다. 둘 다 #87의 승인·환불 WHERE 조각을 재사용한다. 기존 `totals`는 `dayTotals`와 같은 내부 질의(날짜 없음)를 쓴다.
  - `PassTally`(이용권 지표 합·스냅샷 키·고정 정렬), `TrendBuckets`(구간 경계·라벨·부분 구간).
- **이용권 지표의 원천**: 승인 항목은 `purpose = 'PASS'` 승인 결제 ⟕ `passes`(`purchase_payment_id`). 결제 건수 = 행 수, 판매 수량 = `Σ issued_pass_quantity`, 발급 건수 = `COUNT(passes.id)`. 환불 항목은 같은 조인에서 환불 수량 = 원결제의 `Σ issued_pass_quantity`. 발급 시각은 계약대로 원결제 `approved_at`이다. `sourceRowCount` = 이용권 승인 항목 수 + 이용권 환불 항목 수이며, 같은 조건의 `purpose=이용권` 원장 `meta.total`과 같다.
- **스냅샷 키와 collation**: 테이블 기본 collation `utf8mb4_0900_ai_ci`는 대소문자·악센트를 구분하지 않는다. 그대로 묶으면 "Pass" → "PASS"처럼 대소문자만 바꾼 이름이 옛 행에 합쳐지고, `categoryCode`가 정확 일치가 아니게 된다. 스냅샷 열의 묶음과 `categoryCode` 비교에 `COLLATE utf8mb4_0900_bin`을 썼다.
- **고정 순서**: `netSoldQuantity` 내림차순 → `productId` → `productName` → `categoryCode` → `categoryName` 오름차순(문자열은 유니코드 코드 포인트, `String.compareTo`의 UTF-16 단위 비교가 아님) → `kind`(상품마다 고정이라 순서를 끝까지 정하는 용도). 순위는 정렬 전체에 경쟁 순위를 매긴 뒤 페이지를 자르므로 어느 페이지에서도 같은 순위다.
- **이용권 operation의 메타 `purpose`**: 계약대로 항상 `이용권`이다. `AggregationConditions.resolve`가 `Target.PASSES`일 때 scope의 용도를 `PASS`로 채운다. 이 값은 승인·환불 WHERE에 `p.purpose = 'PASS'`로도 들어가 이용권 조각의 조건과 겹칠 뿐 결과를 바꾸지 않는다.
- **추세 기본값**: `measure` 생략은 `revenue`, `granularity` 생략은 `day`이며 적용한 값을 응답의 `measure`·`granularity`·`aggregation.condition.granularity`로 돌려준다. 서비스가 기본값을 넣으므로 M10 호출도 같다.

계약이 정하지 않아 판단한 점:

- **승인 이용권 결제에 발급 이용권이 없을 때**: `PaymentService`가 승인과 같은 트랜잭션에서 이용권을 발급하므로 깨진 불변식으로 보고 `IllegalStateException`(500)으로 실패한다. 상품 없는 행을 순위에 넣지 않기 위해서다.
- **이용권 operation의 `productId`가 없는 상품**: 다른 필터처럼 빈 결과(0, `noData`)다. 상품이 있는지 알리지 않는다.
- **`granularity`·`measure`의 잘못된 값과 빈 값**: 422 `VALIDATION_FAILED`.
- **응답의 `aggregation.metrics`**: 추세는 `measure`에 해당하는 지표 5개만 담는다(openapi의 `x-metric-ids`는 두 측정의 합집합).

## 4. 구현 내용

- `modules/sales`
  - controller: `SalesController`에 세 endpoint 추가. 상품 순위는 `sort`·`direction`을 받지 않는다.
  - 요청: `TrendMeasure`, `AggregationCondition.passes(...)`·`withGranularity(...)`.
  - 응답: `PassVolume`, `PassVolumeSummary`, `PassProductSales`, `PassProductSalesPage`, `SalesTrend`, `SalesTrendBucket`, `SalesTrendTotals`.
  - 서비스: `PassSalesService`, `SalesTrendService`, `PassTally`, `TrendBuckets`, `AggregationMetas.PASS_METRICS`.
  - repository: `SourceFilter`에 `productId`·`categoryCode`, `dayTotals`, `passTotals`.
- 계약 문서: `openapi.json`의 세 operation을 `implemented`로 바꾸고 `info.description`에 "#88에서 구현됐다"를 더했다. `aggregation-api.md`의 구현 상태 문장과 `modules/sales` 설명을 고쳤다. `AggregationContractTests`의 구현 상태 시험을 #87·#88 구현, #90 설계 계약으로 바꿨다.
- 시험
  - `SalesIntegrationSupport`: `volume(...)` 단언과 `rename(...)`(상품 서비스로 이름·분류 변경)을 더했다.
  - `SalesPassIntegrationTests`(7개): 계산 예시 이용권 표 5행과 두 기준, 지점 2의 귀속, 매출(`purpose=이용권`)·원장 `passQuantity`·상품 `totals`·`sourceRowCount` 대조(3기간 × 2기준), 계산 예시 상품 행과 동률 순위(1, 1, 3), 이름 변경(대소문자만 바꾼 변경 포함)·분류 이동 뒤 스냅샷 행·필터, 2·1·1·0 경쟁 순위(1, 2, 2, 4)와 페이지 간 순위·반복 요청 순서, 다른 필터 422, 권한 403.
  - `SalesTrendIntegrationTests`(7개): 계산 예시 주 추세 표(매출·이용권), 4기간 × 2기준 × 3단위 × 2측정에서 구간 합 = 합계 operation, 서울 자정·월요일 주·월 경계·부분 구간·빈 구간, 366·367 일 구간과 13개월, 기본값, 측정별 필터 422·권한 403, M10 서비스 호출 = HTTP 응답, 응답 필드 집합 = 계약 schema.

## 5. 검증 결과

- 관련 시험: `./gradlew test --tests 'com.openstudy.modules.sales.*' --tests 'com.openstudy.architecture.*'` 통과. sales 6개 suite 34개(신규 `SalesPassIntegrationTests` 7, `SalesTrendIntegrationTests` 7 포함), `AggregationContractTests` 21개, 실패 0.
- 전체 시험 `./gradlew test bootJar`: **BUILD SUCCESSFUL, 53분 57초. 81개 suite, 969개 테스트, 실패 0·오류 0·건너뜀 0.** Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 이 실행 뒤에는 이 기록만 바꿨다.
- 계약 계산 예시: 이용권 지표 표 5행(11월 처리일·원결제일 2/2/1/1/2, 10월 처리일 1/1/0/1/1, 10월 원결제일 1/1/1/0/1, 12월 처리일 0/0/1/−1/0)과 주 추세 표 6구간(6,000 · 20,000/16,000/4,000 · 12,000 · 0 `noData` · 0 `noData` · 15,000, 첫·마지막 구간 부분 구간)을 API로 그대로 재현했다. #87 기록에서 "#88 몫이라 API로 검증하지 않았다"고 남긴 두 표다.
- 주의: 시험 실행 중 작업 트리에 이 작업과 무관한 변경(여러 모듈의 `.gitkeep` 삭제가 스테이징됨, 추적되지 않은 `poc.env`)이 있었다. 둘 다 컴파일 결과에 영향이 없는 파일이고, 건드리거나 커밋하지 않았다.
- 미검증: 수정 전 코드로 대소문자만 바꾼 이름이 실제로 한 행에 합쳐지는지(`COLLATE` 제거 시 시험 실패)를 돌려 보지 않았다. MySQL `_ai_ci` 비교 규칙에 근거한 판단이다. 운영 규모 실행 계획, Frontend 연동도 확인하지 않았다.

## 6. 남은 작업 / 보류 사항

- **#89 실행 계획 확인 대상 추가**
  - 이용권 질의: `payments` 범위 후 `uk_passes_1`(`purchase_payment_id`) 조인, 스냅샷 열의 `COLLATE` 표현식으로 `GROUP BY`(인덱스로 묶지 못하고 임시 테이블이 생긴다. 그룹 수가 작아 문제가 될지는 실행 계획으로 확인).
  - 추세: 같은 범위 조건에 `DATE_FORMAT(DATE_ADD(...))` 날짜 표현식 `GROUP BY`가 더해진다.
  - `categoryCode` 필터는 `passes`에 분류 코드 인덱스가 없어 조인 후 거른다.
- 이용권 operation과 추세는 `issued_pass_quantity`를 그대로 더한다. V1은 결제당 1이라 결제 건수 = 판매 수량 = 발급 건수인 데이터만으로 시험했다. 수량이 1이 아닌 결제가 생기면 그 경로의 시험이 필요하다.
- 무료·수동 발급(결제 없는 이용권)은 V1에 없어 `issuedCount`가 판매 수량과 다른 경우를 시험하지 못했다. 생기면 발급 시각 기준도 그때 정한다(계약 "발급 시각").
- `openapi.json`을 바꿨지만 `OpenStudy-Foundation-API.xlsx`는 이전 구현 PR들처럼 다시 만들지 않았다.
