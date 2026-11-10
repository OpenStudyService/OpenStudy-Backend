# 지점 휴무·판매 기간/한도·고객 병합·일별 마감·고급 필터 API 계약

Backend [#111](https://github.com/OpenStudyService/OpenStudy-Backend/issues/111)의 M13 계약이다. 구현은 지점 휴무일(#112), 상품 판매 기간·한도(#113), 중복 고객 확인·병합(#114), 일별 마감·조정 이력(#115), 고급 필터·이용 지점 사용량·좌석 점유 추이(#116)에서 이 문서를 기준으로 진행한다. Frontend는 같은 [OpenAPI](openapi.json)(`0.1.14-m13-operations-extension`)로 연동한다.

2026-11-10: 이 문서의 operation은 모두 설계 계약(`design-target`)이다. 기존 operation에 더한 필드·필터도 같은 이슈에서 구현한다. 2026-11-10: 지점 휴무일 operation 4개와 룸 예약 차단·달력 `closedDates`·홈 `closures`는 #112에서 구현됐다(`implemented`, V12 `branch_closures`). 2026-11-10: 상품 판매 기간·한도(상품 필드·목록 필터·결제 판매 판정)는 #113에서 구현됐다(`implemented`, V13 `products` 칼럼). 2026-11-10: 중복 고객 확인·병합 operation 3개와 병합된 고객의 조회·판매·입실 처리는 #114에서 구현됐다(`implemented`, V14 `customers`·`customer_merges`). 2026-11-10: 일별 마감 operation 3개와 환불의 마감 조정 기록은 #115에서 구현됐다(`implemented`, V15 `daily_closings`·`daily_closing_adjustments`). 2026-11-10: 결제·집계 고급 필터, 이용 지점 사용량, 좌석 점유 추이는 #116에서 구현됐다(`implemented`). 이로써 이 문서의 operation과 `x-planned-changes`가 모두 구현됐고 OpenAPI에 남은 `x-planned-changes`는 없다.

정책 근거는 [ADR-M13-001](../../db/ADR-M13-001.md)(M13-D01~D13)이다. M13-D11~D13은 이 계약을 쓰면서 사용자에게 받은 결정(2026-11-10)이다. 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을, 원거래의 의미는 [상품 API](product-api.md)·[고객·결제 계약](customer-sales-api.md)·[공간 운영 계약](space-operations-api.md)·[좌석 이용 계약](seat-usage-api.md)을, 집계의 기간·환불 기준·메타정보·값 표현은 [정형 집계 계약](aggregation-api.md)(M9)을 따른다.

### OpenAPI 표기

- 새 operation 12개는 OpenAPI에 `x-implementation-status: design-target`과 구현 이슈(`x-implementation-issue`)로 정의했다.
- 이미 구현된 operation·schema에 더할 필드·query·오류 코드는 그 operation·schema의 `x-planned-changes`에 적었다. 항목마다 구현 이슈와 `design-target`이 붙는다. 구현 PR이 이 내용을 본 정의(`properties`, `required`, `parameters`, `x-error-codes`)로 옮기고 항목을 지운다.
- 본 정의에 바로 넣지 않은 이유: 구현된 응답이 계약 필드와 정확히 같은지 비교하는 통합 테스트가 있어서, 구현 전에 필드를 넣으면 현재 동작과 계약이 어긋난다.

## 제공 범위

### 지점 휴무일(#112, FR-BR-09, M13-D01·D05)

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /branches/{id}/closures` | OWNER 또는 지점의 유효 소속 | 휴무일 목록(`from`·`to` 날짜) |
| `POST /branches/{id}/closures` | 지점의 MANAGER 이상 | 휴무일 등록과 그날 시작하는 기존 예약 경고. `Idempotency-Key` 필수 |
| `PATCH /branch-closures/{id}` | 휴무일 지점의 MANAGER 이상 | 안내 문구 수정 |
| `DELETE /branch-closures/{id}` | 휴무일 지점의 MANAGER 이상 | 아직 오지 않은 휴무일 삭제 |

기존 operation 변경:

- `POST /room-reservations`: 시작 날짜(Asia/Seoul)가 휴무일이면 409 `BRANCH_CLOSED_DAY`다.
- `GET /room-reservations/calendar`: 응답에 `closedDates`를 더한다.
- `GET /home/summary`: 응답에 `closures`(오늘과 앞으로 7일의 휴무 안내)를 더한다.

### 상품 판매 기간·한도(#113, FR-PROD-10, M13-D02·D06)

새 operation은 없다. 기존 상품·결제 operation을 바꾼다.

| API | 변경 |
| --- | --- |
| `POST /products`, `PATCH /products/{id}` | 선택 필드 `salesStartsAt`, `salesEndsAt`, `salesLimit` |
| `GET /products`, `GET /products/{id}` | 위 세 필드와 `soldQuantity`, `remainingQuantity`, `saleAvailability`. 목록 필터 `availability` |
| `GET /products/{id}/history` | 스냅샷에 위 세 필드 |
| `POST /payments` | 409 `PRODUCT_NOT_IN_SALE_PERIOD`, 409 `PRODUCT_SALE_LIMIT_REACHED` |

### 중복 고객 확인·병합(#114, FR-MEM-08, M13-D03·D07·D13)

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /customer-duplicates` | OWNER | 같은 연락처의 고객 묶음 |
| `POST /customers/{id}/merge` | OWNER | 다른 고객을 이 고객으로 병합. 되돌리지 않는다 |
| `GET /customers/{id}/merges` | 고객을 볼 수 있는 사용자 | 이 고객이 대상이거나 흡수된 병합 이력 |

기존 operation 변경:

- `GET /customers/{id}`: `status`에 `병합됨`, `mergedInto`, `absorbedCustomerCount`를 더한다.
- `GET /customers`: 병합된 고객은 `status=병합됨`일 때만 나온다.
- `customerId`로 거르는 목록(`GET /payments`, `GET /passes`, `GET /seat-sessions`, `GET /room-reservations`)과 고객 이력(`/customers/{id}/pass-history`, `/customers/{id}/space-history`)은 흡수된 고객의 기록을 함께 담는다.

### 일별 마감·조정 이력(#115, FR-PAY-14, M13-D04·D08·D11)

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /daily-closings` | 요청한 모든 지점의 `sales:read` | 마감 목록 |
| `POST /daily-closings` | 지점의 MANAGER 이상 + `sales:read` | 지난 날짜 하나의 마감. `Idempotency-Key` 필수 |
| `GET /daily-closings/{id}` | 지점의 `sales:read` | 마감 금액, 현재 금액, 차이, 조정 이력 |

조정 이력은 따로 만드는 API가 없다. 환불(`POST /payments/{id}/refund`, `POST /room-reservations/{id}/cancel`의 환불)이 같은 트랜잭션에서 만든다.

### 고급 필터·이용 지점 사용량·좌석 점유 추이(#116, FR-PAY-13, FR-AGG-15, FR-SEAT-16, M13-D09·D10·D12)

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /sales/passes/usage` | 요청한 모든 이용 지점의 `sales:read` | 이용 지점별 이용권 사용량(입실 수·차감 분·차감액) |
| `GET /seats/occupancy-trend` | OWNER 또는 지점의 유효 소속 | 시간·일 구간별 좌석 점유율 |

기존 operation 변경:

- `GET /payments`: [고급 필터](#고급-필터-m13-d10)와 응답 `filter`·`summary`
- `GET /sales/revenue`, `/sales/revenue/branches`, `/sales/ledger`, `/sales/trend`, `/sales/passes/volume`, `/sales/passes/products`: 같은 고급 필터, `aggregation.condition`의 새 필드, `aggregation.conditionNotes`

## 공통 규칙

- **MANAGER 이상**: OWNER이거나 해당 지점의 활성 소속이 `role=MANAGER`인 경우다(M8·M12와 같은 판정).
- **범위**: 볼 수 없는 지점의 휴무일·마감은 목록·total에 없고 상세와 명령은 404다. 볼 수 있지만 처리 권한이 없으면 403이다.
- **수정 충돌**: 휴무일 수정·삭제와 고객 병합은 조회한 `version`을 받는다. 다르면 409 `VERSION_CONFLICT`와 `latestVersion`·`reloadUrl`이다.
- **입력 오류는 422, 다른 데이터와의 관계로 생기는 오류는 409다**(M8·M12와 같은 구분). M13의 409 본문은 `OperationsConflictError`이며 아래 표의 대상 정보 필드를 담는다.
- **날짜**: 휴무일과 마감일은 Asia/Seoul의 하루(`YYYY-MM-DD`)다. 하루는 그날 0시(포함)부터 다음 날 0시(제외)까지다.
- **화면 갱신**: 변경 이벤트는 보내지 않는다. 화면은 명령 응답과 다시 조회로 갱신한다([ADR-COM-001](../../db/ADR-COM-001.md)).
- `canEdit`, `canDelete` 같은 응답 필드는 화면 안내용이다. 서버는 쓰기마다 다시 검사한다.

### 추가 오류 코드

| HTTP | code | 의미 | 대상 정보 |
| --- | --- | --- | --- |
| 422 | `CLOSURE_DATE_INVALID` | 휴무일이 오늘(Asia/Seoul) 전이거나 366일 뒤를 넘음 | `fieldErrors` |
| 409 | `BRANCH_CLOSURE_EXISTS` | 같은 지점·날짜의 휴무일이 이미 있음 | `closureId` |
| 409 | `CLOSURE_ALREADY_STARTED` | 오늘 또는 지난 휴무일의 삭제 | - |
| 409 | `BRANCH_CLOSED_DAY` | 휴무일에 시작하는 스터디룸 예약(M13-D01) | `closedDate` |
| 409 | `PRODUCT_NOT_IN_SALE_PERIOD` | 판매 시작 전이거나 판매 종료 뒤의 판매(M13-D06) | `salesStartsAt`, `salesEndsAt` |
| 409 | `PRODUCT_SALE_LIMIT_REACHED` | 판매 한도를 모두 판 상품의 판매(M13-D02) | `salesLimit`, `soldQuantity` |
| 422 | `CUSTOMER_MERGE_SELF` | 자기 자신과의 병합 | - |
| 409 | `CUSTOMER_ALREADY_MERGED` | 이미 병합된 고객을 대상이나 흡수 고객으로 지정 | `mergedIntoCustomerId` |
| 422 | `CLOSING_DATE_OPEN` | 오늘 또는 미래 날짜의 마감(M13-D11) | `fieldErrors` |
| 409 | `CLOSING_EXISTS` | 같은 지점·날짜의 마감이 이미 있음 | `closingId` |

기존 코드도 쓴다.

- 409: `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `REQUEST_IN_PROGRESS`, `PRODUCT_NOT_ON_SALE`, `PRODUCT_NOT_AVAILABLE_AT_BRANCH`, `CUSTOMER_NOT_ACTIVE`, `BRANCH_NOT_ACTIVE`
- 422: `VALIDATION_FAILED`, `PERIOD_TOO_LONG`

## 지점 휴무일(FR-BR-09, M13-D01·D05)

### 효과(M13-D01)

| 업무 | 휴무일의 효과 |
| --- | --- |
| 스터디룸 신규 예약 | 시작 시각의 Asia/Seoul 날짜가 휴무일이면 409 `BRANCH_CLOSED_DAY`. 전날 시작해 휴무일로 넘어가는 예약은 막지 않는다. 검사 위치는 시간 규칙(422) 뒤, 정원·고객·시간 충돌 앞이다 |
| 이미 있는 스터디룸 예약 | 자동 취소하지 않는다. 등록 응답의 `affectedReservations`로 경고한다 |
| 판매·입실·출근·근무 등록 | 막지 않는다 |
| 화면 | 달력의 `closedDates`, 홈의 `closures`로 안내한다 |

- 정기 휴무(요일)는 기존 지점 영업시간(`weeklyHours`의 `closed`)이다. 이 API는 특정 날짜의 휴무다. 영업시간의 휴무 요일은 예약을 막지 않는 기존 동작 그대로다.

### 등록

`POST /branches/{id}/closures` 본문은 `date`(`YYYY-MM-DD`)와 선택 `note`(1~200자)다.

| 조건 | 결과 |
| --- | --- |
| 지점에 접근할 수 없음(없는 지점 포함) | 403. 지점 상세(`GET /branches/{id}`)와 같이 지점이 있는지 알리지 않는다(2026-11-10 #112 구현에서 정정) |
| 지점의 MANAGER 이상이 아님 | 403 |
| 날짜 형식 오류, `note` 길이 | 422 `VALIDATION_FAILED` |
| `date` < 오늘(Asia/Seoul) 또는 `date` > 오늘 + 366일 | 422 `CLOSURE_DATE_INVALID` |
| 같은 지점·날짜의 휴무일이 있음 | 409 `BRANCH_CLOSURE_EXISTS`(`closureId`) |

- 운영 중지 지점에도 등록할 수 있다. 휴무일은 안내 정보라 신규 거래 제한과 무관하다.
- 응답은 201 `BranchClosureCreated`다. 휴무일과 함께, 그날(Asia/Seoul) 시작하는 `확정`·`이용 중` 상태 예약의 `id`, `roomId`, `roomName`, `startAt`, `endAt`을 `affectedReservations`에 담는다(시작 시각 순, 최대 100건과 `affectedReservationCount`). 고객 정보는 담지 않는다. 화면은 예약 목록으로 이동해 연락·취소를 처리한다.
- 같은 키·같은 입력은 처음 결과(201)를 재생한다. 경고 목록도 처음 결과 그대로다.

### 수정·삭제·조회

- 수정(`PATCH /branch-closures/{id}`)은 `version`과 `note`만 받는다. 날짜는 바꿀 수 없다(422 `IMMUTABLE_FIELD`가 아니라 정의되지 않은 필드라 422 `VALIDATION_FAILED`). 날짜를 바꾸려면 삭제 후 다시 등록한다. 지난 휴무일의 문구도 고칠 수 있다.
- 삭제(`DELETE /branch-closures/{id}?version=`)는 `date` > 오늘(Asia/Seoul)인 휴무일만이다. 오늘이거나 지난 휴무일은 409 `CLOSURE_ALREADY_STARTED`다. 지난 휴무는 기록으로 남긴다. 성공은 204다. 행을 지우고 감사(`branch-closures`, `DELETED`)를 남긴다.
- 목록은 지점에 접근할 수 있는 사용자(OWNER 또는 유효 소속)가 본다. 접근할 수 없으면 403이다. 필터는 `from`·`to`(날짜, 둘 다 포함)이다. 생략하면 오늘부터 앞으로 90일이다. 기간은 최대 366일이고 넘으면 422 `PERIOD_TOO_LONG`이다. 정렬은 `date` 오름차순이고 페이지가 없다(지점·기간당 최대 367행).
- 감사: 등록·수정·삭제마다 `audit_events`에 지점·처리자를 남긴다.

### 다른 화면의 안내

- **달력**: `GET /room-reservations/calendar` 응답의 `closedDates`는 조회 기간과 겹치는 그 지점 휴무일의 날짜 목록이다(오름차순).
- **홈**: `GET /home/summary`의 `closures`는 scope 지점의 오늘부터 7일 뒤까지(8일)의 휴무일이다. 항목은 `branchId`, `branchName`, `date`, `note`, `today`(오늘인지)다. 날짜 → 지점 ID 순이다.

## 상품 판매 기간·한도(FR-PROD-10, M13-D02·D06)

### 필드

| 필드 | 형식 | 의미 |
| --- | --- | --- |
| `salesStartsAt` | 시각, 선택 | 이 시각부터 판매한다(포함). null이면 제한 없음 |
| `salesEndsAt` | 시각, 선택 | 이 시각 전까지 판매한다(제외). null이면 제한 없음 |
| `salesLimit` | 1~1,000,000, 선택 | 모든 판매 지점을 합친 판매 한도(M13-D02). null이면 제한 없음 |
| `soldQuantity` | 응답 | 한도 계산에 쓰는 판매 수 = 이 상품의 `승인` 상태 결제 수. 환불된 결제는 빠진다 |
| `remainingQuantity` | 응답 | `max(salesLimit − soldQuantity, 0)`. 한도가 없으면 null |
| `saleAvailability` | 응답 | 지금 신규 판매가 되는지: `판매 가능`, `판매 중지`, `판매 전`, `판매 종료`, `한도 소진` |

- 시각은 오프셋이 있는 ISO 8601이고 초 미만은 받지 않는다. 둘 다 있으면 `salesStartsAt < salesEndsAt`이어야 한다(422 `VALIDATION_FAILED`).
- `PATCH`에서 필드를 빼면 그대로 두고, `null`을 보내면 제한을 없앤다. 세 필드의 변경도 상품 version을 올리고 `product_revisions`에 스냅샷을 남긴다(기존 변경 규칙과 같다). 스냅샷은 `schemaVersion` 2부터 세 필드를 담고, 그 전에 저장된 버전 1 스냅샷에는 없다. 권한도 기존 `PATCH`와 같다(현재 모든 판매 지점의 `operations:write`).
- 한도를 현재 판매 수보다 낮게 바꿀 수 있다. 이미 판 결제는 그대로이고 `remainingQuantity`는 0이다.
- `saleAvailability`는 아래 판정 순서의 첫 결과다. 판매 지점 여부는 담지 않는다(지점마다 다르다).
- 목록 필터 `availability`는 `saleAvailability` 값 하나로 거른다. 기존 `status`(판매 중·판매 중지)와 함께 쓸 수 있다.

### 판매 가능 판정(M13-D06)

`POST /payments`는 기존 검사 뒤 다음 순서로 판정한다. ADR의 "판매 상태 → 판매 기간 → 남은 한도"에 기존 판매 지점 검사를 판매 상태 바로 뒤에 둔다.

| 순서 | 조건 | 결과 |
| --- | --- | --- |
| 1 | 판매 중지 | 409 `PRODUCT_NOT_ON_SALE`(기존) |
| 2 | 판매 지점이 아님 | 409 `PRODUCT_NOT_AVAILABLE_AT_BRANCH`(기존) |
| 3 | 처리 시각 < `salesStartsAt` 또는 처리 시각 ≥ `salesEndsAt` | 409 `PRODUCT_NOT_IN_SALE_PERIOD`(`salesStartsAt`, `salesEndsAt`) |
| 4 | `salesLimit`이 있고 `soldQuantity` ≥ `salesLimit` | 409 `PRODUCT_SALE_LIMIT_REACHED`(`salesLimit`, `soldQuantity`) |

- 세 판매 불가 사유(판매 중지, 기간 외, 한도 초과)는 서로 다른 코드다(#111 인수 조건).
- 처리 시각은 서버 시각이다. 기간 판정은 `[salesStartsAt, salesEndsAt)`이다.
- `mockOutcome=FAILED`인 요청도 같은 판정을 거친다. 판매 불가 상품의 실패 결제를 남기지 않는다.

### 동시 판매(M13-D02)

- 한도가 있는 상품은 `requireSellable`이 상품 행을 `FOR UPDATE`로 잠근 뒤 판매 수를 센다. 잠금 방식은 잠그기 전에 한도 유무를 읽어 정한다. 모든 명령이 공통 가드 행을 먼저 잠그므로 그 사이에 한도가 바뀌지 않는다(2026-11-10 #113 구현에서 확인). 같은 상품의 판매는 차례로 처리되어 마지막 한 개를 두 명이 동시에 사도 하나만 성공한다.
- 한도가 없는 상품은 기존대로 공유 잠금이다. 동시 판매가 서로 기다리지 않는다.
- 환불은 결제를 `환불`로 바꾸므로 판매 수가 하나 줄어 다시 팔 수 있다. 환불은 상품 행을 잠그지 않는다. 판매 수는 결제 상태로 세므로 환불과 판매가 엇갈려도 판매 수가 한도를 넘지 않는다(환불은 수를 줄이기만 한다).
- 판매 수는 결제 상태를 직접 센다. 별도 카운터 칼럼을 두지 않아 카운터와 원거래가 어긋날 일이 없다. #113은 `payments (product_id, status)` 인덱스 필요 여부를 실행 계획으로 확인한다.

## 중복 고객 확인·병합(FR-MEM-08, M13-D03·D07·D13)

### 중복 의심 묶음(M13-D13, 2026-11-10 사용자 결정)

`GET /customer-duplicates?page=&pageSize=`

- 기준은 **같은 연락처**다. 저장된 숫자 연락처(`customers.phone`)가 같은 `병합됨`이 아닌 고객이 둘 이상이면 한 묶음이다. 연락처는 필수이고 숫자만 저장하므로 하이픈·공백 표기 차이는 이미 없다.
- 묶음 항목은 `phone`과 고객 목록이다. 고객마다 `id`, `memberNo`, `name`, `status`, `registeredBranchId`, `registeredAt`, `paymentCount`(모든 상태 결제 수), `lastActivityAt`(마지막 결제·입실 시각, 없으면 null)를 담는다. 고객은 등록 시각 순이다.
- 묶음 정렬은 고객 수 내림차순, 같으면 `phone` 오름차순이다. 공통 페이지(최대 100묶음)다.
- OWNER 전용이다. 모든 지점의 고객을 지점 범위 없이 보여 주기 때문이다(M13-D03).

### 병합(M13-D03·D07)

`POST /customers/{id}/merge` 본문: `version`(대상 고객), `absorbedCustomerId`, `absorbedVersion`, `reason`(1~500자). 경로의 `{id}`가 남는 대상 고객이다.

| 순서 | 조건 | 결과 |
| --- | --- | --- |
| 1 | 형식 오류 | 422 `VALIDATION_FAILED` |
| 2 | OWNER가 아님 | 403 |
| 3 | `absorbedCustomerId` = `{id}` | 422 `CUSTOMER_MERGE_SELF` |
| 4 | 둘 중 하나가 없음 | 404 |
| 5 | 둘 중 하나가 `병합됨` | 409 `CUSTOMER_ALREADY_MERGED`(`mergedIntoCustomerId`) |
| 6 | `version` 또는 `absorbedVersion`이 다름 | 409 `VERSION_CONFLICT`(다른 쪽 고객의 `latestVersion`, `reloadUrl`) |

- 같은 연락처가 아니어도 병합할 수 있다. 중복 묶음은 후보 안내일 뿐이고 판단은 OWNER가 한다.
- 처리 결과(한 트랜잭션):
  - 흡수 고객: `status=MERGED`, `merged_into_customer_id`=대상, version 증가. 이름·연락처·이용 제한 사유는 그대로 남긴다.
  - 흡수 고객에게 이미 흡수된 고객이 있으면 그 고객들의 `merged_into_customer_id`도 새 대상으로 옮긴다. 병합 대상은 항상 `병합됨`이 아닌 고객 하나다(한 단계).
  - `customer_merges`에 대상, 흡수 고객, 처리자, 시각, 사유를 남긴다. 옮긴 고객마다 한 행을 남긴다(`via`=중간 고객).
  - 대상 고객의 version도 올린다(흡수된 기록이 바뀌었으므로 화면이 다시 읽게 한다).
  - 감사(`customers`, `MERGED`)를 대상 고객에 남긴다.
- **기존 참조는 바꾸지 않는다(M13-D07)**: 결제·이용권·좌석 이용·예약·사물함 배정·문의의 `customer_id`는 흡수 고객 그대로다.
- 흡수 고객의 진행 중 좌석 이용·예약도 그대로다. 퇴실·취소는 기존처럼 그 기록의 ID로 처리한다. 흡수 고객에게 새 결제·입실·예약·사물함 배정은 할 수 없다. 409 `CUSTOMER_NOT_ACTIVE`이고 `mergedIntoCustomerId`로 대상 고객을 알려 준다(2026-11-10 #114 구현에서 대상 정보 추가).
- **흡수 고객의 이용권(2026-11-10 사용자 결정)**: 대상 고객이 흡수 고객의 남은 이용권으로 입실할 수 있다. 이용권·결제의 고객 참조는 그대로이고, 좌석 이용 기록에는 입실한 대상 고객이 남는다. 입실 조회의 사용 가능 이용권에도 대상 고객의 후보로 함께 나온다. 그대로 두면 흡수 고객이 입실할 수 없어 남은 이용권을 아무도 쓸 수 없기 때문이다.
- 병합된 고객은 기록이다. 수정·상태 변경은 409 `CUSTOMER_ALREADY_MERGED`(`mergedIntoCustomerId`)이고, 상태 변경으로 `병합됨`을 지정할 수 없다(422).
- 되돌리는 API는 없다(M13-D03).
- 잠금 순서: 공통 가드 → 두 고객 행 `FOR UPDATE`(ID 오름차순) → 흡수 고객에게 흡수된 고객 행(ID 오름차순) → 변경 → `customer_merges` → 감사.

### 병합 뒤 조회(M13-D07)

| 대상 | 동작 |
| --- | --- |
| 흡수 고객 상세 | 200. `status=병합됨`, `mergedInto`(대상 고객 요약). 화면은 대상 고객으로 이동한다 |
| 대상 고객 상세 | `absorbedCustomerCount`. 흡수 고객의 이름·연락처는 병합 이력에서 본다 |
| 고객 목록 | 기본 결과에서 `병합됨`을 뺀다. `status=병합됨`으로 따로 본다 |
| `customerId`=대상으로 거르는 목록·이력 | 대상과 흡수 고객의 기록을 함께 담는다. 각 행의 `customer`는 원래 고객 그대로다 |
| 대상 고객의 조회 범위(M4-D05) | 대상 또는 흡수 고객의 등록 지점·결제 지점이 scope에 있으면 보인다 |
| 입실 조회(`POST /customer-lookups/check-in`) | 흡수 고객은 결과에서 빼고 대상 고객을 찾는다. 흡수 고객의 사용 가능 이용권은 대상 고객의 후보에 함께 나온다 |
| 사물함 배정 목록(`customerId`), 타 지점 요약의 보유 이용권 | 다른 `customerId` 목록처럼 흡수 고객의 기록을 함께 담는다(2026-11-10 #114 구현에서 같은 규칙을 적용) |

- 병합 전에는 두 고객 모두 OWNER가 볼 수 있어야 한다(M13-D07). OWNER는 모든 고객을 보므로 별도 검사는 없다.

### 병합 이력

`GET /customers/{id}/merges`는 이 고객이 대상이거나 흡수된 병합 이력을 최신순으로 준다. 항목은 대상·흡수 고객 요약, `via`, 처리자, 시각, 사유다. 고객을 볼 수 있으면 열람할 수 있다(고객 응대에 병합 사실이 필요하다). 페이지가 없다(고객당 병합 수는 작다).

## 일별 마감·조정 이력(FR-PAY-14, M13-D04·D08·D11)

### 마감 시점과 대상(M13-D11, 2026-11-10 사용자 결정)

- 마감은 **끝난 날만** 한다. `businessDate` < 오늘(Asia/Seoul)이어야 하고 아니면 422 `CLOSING_DATE_OPEN`이다.
- 같은 지점·날짜는 한 번만 마감한다(M13-D08). 두 번째는 409 `CLOSING_EXISTS`(`closingId`)다.
- 마감은 아무 업무도 막지 않는다(M13-D04). 마감 뒤에도 환불할 수 있다.

### 스냅샷(M13-D08)

마감 시점(`closedAt`)에 M9 공통 집계로 그날(`[businessDate 0시, 다음 날 0시)`, Asia/Seoul)·그 지점의 수치를 계산해 저장한다. 두 환불 기준을 모두 저장한다. 합계와 용도별 소계는 `GET /sales/revenue`와 같은 서비스로 계산해 같은 조건의 M9 집계와 항상 같다(#115).

| 묶음 | 내용 |
| --- | --- |
| `processedDate` | 처리일 기준 승인액·환불액·순매출·승인 결제 건수·환불 건수, 용도별 소계, 결제 수단별 소계 |
| `paymentDate` | 원결제일 기준 같은 수치 |

- 결제 수단별 소계는 승인 결제의 수단과, 환불은 원결제의 수단으로 나눈다. 현금 정산 확인에 쓴다.
- 처리일 기준 수치는 마감 뒤 바뀌지 않는다. 마감은 지난날에만 하고, 결제 승인과 환불은 항상 처리 시각(오늘)에 들어가기 때문이다.
- 원결제일 기준 수치는 바뀔 수 있다. 마감일에 승인된 결제가 나중에 환불되면 그날의 원결제일 기준 환불액이 늘어난다.

### 조정 이력(M13-D04)

- 환불의 **원결제 승인 시각이 마감된 날(원결제 지점 기준)에 속하면**, 그 환불 트랜잭션이 `daily_closing_adjustments`에 한 행을 남긴다. 금액은 −환불액, 사유는 환불 사유, 처리자는 환불 처리자다.
- 해당하는 환불: 이용권 환불(`POST /payments/{id}/refund`), 스터디룸 예약 취소의 환불(`POST /room-reservations/{id}/cancel`). 환불 API의 요청·응답은 바꾸지 않는다.
- 마감 상세의 금액 비교:

| 필드 | 의미 |
| --- | --- |
| `snapshot.processedDate`, `snapshot.paymentDate` | 마감 때 저장한 수치 |
| `current.paymentDate` | 상세를 조회한 시각에 다시 계산한 원결제일 기준 수치 |
| `difference` | `current.paymentDate − snapshot.paymentDate`(승인액·환불액·순매출·건수). 처리일 기준 차이는 항상 0이라 담지 않는다 |
| `adjustments` | 조정 이력(처리 시각 순). `Σ(adjustments.amountKrw)` = `difference.netAmountKrw` |

- 조정 이력의 사유는 자유 입력이라 개인정보가 들어갈 수 있다. 원결제 지점의 `payments:read`가 있는 사용자에게만 `reason`을 주고, 없으면 null이다(M9 원장의 환불 사유 제외와 같은 판단).
- 마감 취소·재마감 API는 없다. 마감 뒤 변경은 조정 이력으로만 추적한다.

### 동시성

- 마감 생성은 공통 가드 → 지점 행 `FOR UPDATE` → 기존 마감 확인 → 스냅샷 계산 → `daily_closings` 순서다.
- 환불은 기존 `AuthorizedCommands` 순서에서 원결제 지점 행을 잠근다. 그래서 같은 지점의 마감과 환불은 차례로 처리된다. 마감 계산 중에 그날 결제의 환불이 끼어들어 스냅샷에도 조정 이력에도 빠지는 일이 없다. 환불은 지점 행을 잠근 뒤 마감 행을 다시 읽어 조정 이력을 남길지 정한다.

### 목록·권한

- `GET /daily-closings?branchIds=&from=&to=&page=&pageSize=`: `branchIds`는 M9와 같은 규칙(생략하면 `sales:read`가 있는 모든 지점, 하나라도 없으면 403)이다. `from`·`to`는 날짜(둘 다 포함)이고 최대 366일이다. 생략하면 지난달 1일부터 오늘까지다(2026-11-10 #115 구현에서 정함). 정렬은 `businessDate` 내림차순, 같으면 `branchId` 오름차순이다. 항목은 스냅샷의 처리일 기준 수치와 `adjustmentCount`를 담는다.
- 생성은 ADR의 지점 MANAGER 이상에 `sales:read`를 더한다. 응답에 매출 금액이 담기기 때문이다. 기본 관리자 권한 묶음에는 `sales:read`가 있다.
- 운영 중지 지점도 마감할 수 있다(정리 업무).

## 고급 필터(M13-D10)

### 조건

목록(`GET /payments`)과 집계(`/sales/*`)가 **같은 이름·같은 의미**의 query를 쓴다(FR-PAY-13, FR-AGG-15).

| query | 값 | 적용 |
| --- | --- | --- |
| `methods` | `현금`·`카드`·`계좌이체`·`기타`, 반복 | 승인은 결제 수단, 환불은 원결제 수단 |
| `productKinds` | `시간권`·`기간권`·`정액권`, 반복 | 이용권 결제만. 지정하면 이용권 외 용도는 빠진다 |
| `categoryCode` | 판매 당시 분류 코드 | 이용권 결제만(기존 이용권 지표 필터를 매출·결제 목록에도 연다) |
| `operatorIds` | 직원 ID, 반복 | 승인은 결제 처리자(`payments.created_by`), 환불은 환불 처리자(`refunds.processed_by`) |
| `processingBranchIds` | 지점 ID, 반복 | 환불 항목만, 환불 처리 지점(`refunds.processing_branch_id`). 승인 항목은 그대로 둔다 |

- 판매 지점은 기존 `branchIds`(집계) 또는 `scope`(결제 목록)다. 환불 기준은 기존 `refundBasis`다. ADR M13-D10의 "환불 유형"은 환불 기준과 처리 지점으로 구분한다(V1 환불은 전액 환불 하나뿐이다).
- 같은 query 안의 여러 값은 OR, 다른 query끼리는 AND다. 중복 값은 422 `VALIDATION_FAILED`다.
- `productKinds`·`categoryCode`와 `purpose`(이용권 외)를 함께 보내면 422다. `purpose` 없이 보내면 이용권만 집계하고 `condition.purpose`를 `이용권`으로 돌려준다.
- `processingBranchIds`의 지점은 요청자가 볼 수 있는 지점이어야 한다(아니면 403). 원결제 지점 조건(`branchIds`)은 그대로라 권한 밖 지점의 거래는 여전히 들어가지 않는다.

### 적용 operation

| operation | 받는 고급 필터 |
| --- | --- |
| `GET /payments` | 다섯 가지 모두 |
| `GET /sales/revenue`, `/sales/revenue/branches`, `/sales/ledger`, `/sales/trend`(`measure=revenue`) | 다섯 가지 모두 |
| `GET /sales/passes/volume`, `/sales/passes/products`, `/sales/trend`(`measure=passes`) | `methods`, `productKinds`, `operatorIds`, `processingBranchIds`(`categoryCode`는 기존) |

### 응답의 적용 필터

- 집계: `aggregation.condition`에 `methods`, `productKinds`, `operatorIds`, `processingBranchIds`를 더한다. 지정하지 않으면 빈 배열이다.
- 집계: `aggregation.conditionNotes`는 통계 의미가 바뀌는 조건마다 안내 하나를 담는다(FR-AGG-15 "통계 의미가 바뀌는 조건을 화면에 표기"). 화면은 이 문구를 그대로 표시한다.

| `code` | 붙는 조건 | 안내 |
| --- | --- | --- |
| `PASS_ONLY` | `productKinds` 또는 `categoryCode` | 이용권 결제만 집계했다 |
| `REFUND_PROCESSING_BRANCH` | `processingBranchIds` | 환불은 처리 지점 조건에 맞는 것만, 승인은 조건과 무관하다 |
| `OPERATOR_BY_ENTRY` | `operatorIds` | 승인은 결제 처리자, 환불은 환불 처리자로 골랐다 |
| `REFUND_BY_PAYMENT_METHOD` | `methods` | 환불은 원결제 수단으로 나눴다 |

- 결제 목록의 행은 결제다. 처리자 조건은 결제 처리자(`created_by`)로 고르고, `processingBranchIds`는 `환불` 상태 행만 그 환불의 처리 지점으로 좁힌다(2026-11-10 #116 구현에서 정함).
- 결제 목록: 응답에 `filter`(적용한 조건 전체)와 `summary`를 더한다. `summary`는 페이지가 아니라 조건 전체의 상태별 건수·금액 합(`승인`·`실패`·`환불`)이다. 원천 기록(목록 행)과 합계가 같은 조건에서 계산된다(FR-PAY-13 "기준별 합계와 원천 기록 일치").

### 정합성 규칙

같은 조건(판매 지점, 기간, 환불 기준, 고급 필터)에서 다음이 성립한다. #116은 통합 테스트로 고정한다.

- 원장 항목의 합 = `/sales/revenue`의 승인액·환불액·순매출(M9 정합성 규칙이 고급 필터에도 그대로 성립)
- 결제 수단별로 나눈 `/sales/revenue`의 합 = 수단 필터 없는 합
- 결제 목록의 `summary`와 원장: 결제 목록의 기간(`from`·`to`)은 결제 생성 시각이고 원장은 승인·환불 시각이라 기준 시각이 다르다. 승인 결제만, 같은 시각 범위로 비교하면 결제 목록 `summary.approved`의 금액 합 + `summary.refunded`의 금액 합 = 원장 승인 항목 합이다(환불된 결제도 승인액에 남는다)

## 이용 지점별 사용량(FR-AGG-15, M13-D12)

`GET /sales/passes/usage?branchIds=&from=&to=&productKinds=&productId=&categoryCode=`

### 결정(M13-D12, 2026-11-10 사용자 결정)

매출은 계속 판매 지점에 귀속한다. 매출 집계에 이용 지점 필터를 두지 않고, **이용 지점별 이용권 사용량을 새 지표로** 준다. 다른 지점에서 산 이용권이 이 지점 좌석에서 얼마나 쓰였는지를 매출과 섞지 않고 보여 준다.

### 지표

| id | 이름 | 단위 | 정의 | 이용 지점 | 기준 시각 |
| --- | --- | --- | --- | --- | --- |
| `pass.usage_session_count` | 이용권 입실 수 | 건 | 좌석 이용(`seat_sessions`) 행 수. 모든 이용권 유형 | `seat_sessions.branch_id` | `effective_checked_in_at` |
| `pass.usage_minutes` | 시간권 차감 분 | 분 | 시간권 원장(`pass_balance_events`)의 `−Σdelta_minutes`. 좌석 이용에 연결된 차감(`USE`)과 이용 정정의 복원·추가 차감을 더한다 | 연결된 좌석 이용의 지점 | 원장 `created_at` |
| `pass.usage_credit_krw` | 정액권 차감액 | 원 | 정액권 원장의 `−Σdelta_krw`. 위와 같은 범위 | 연결된 좌석 이용의 지점 | 원장 `created_at` |

- 좌석 이용·이용 정정에 연결되지 않은 수동 보정(`ADJUST`, `seat_session_id`·`correction_id` 모두 없음)은 이용 지점이 없어 넣지 않는다.
- 기간권은 차감이 없어 입실 수에만 들어간다.
- 차감액은 매출이 아니다(M9 "이용권 소비 차감은 매출 지표에 넣지 않는다"). 응답에 `aggregation.conditionNotes`의 `USAGE_NOT_REVENUE`를 담는다.

### 응답

- `items`: 이용 지점마다 `branchId`, 세 지표, `bySaleBranch`(이용권을 판 지점별 같은 세 지표). 이용 지점 ID 오름차순이다.
- `bySaleBranch`에서 요청자에게 `sales:read`가 없는 판매 지점은 하나의 `visible=false` 항목으로 합친다(`saleBranchId` null, 라벨 `다른 지점`). 보이는 판매 지점의 `label`은 null이다(2026-11-10 #116 구현에서 정함). M8 근무 충돌의 숨김 규칙과 같은 방식이다.
- `totals`: 모든 이용 지점의 합.
- `aggregation`: M9 `AggregationMeta`. `condition.branchIds`는 이용 지점이고 `condition.refundBasis`는 null이다(사용량에는 환불 기준이 없다). `sourceRowCount`는 계산에 쓴 좌석 이용 행 수 + 원장 행 수다.
- 기간은 M9와 같다(기본 이번 달, 최대 366일).
- 권한은 요청한 모든 이용 지점의 `sales:read`다(M9-D07과 같은 판정).

## 좌석 점유 추이(FR-SEAT-16, M13-D09)

`GET /seats/occupancy-trend?branchId=&from=&to=&granularity=`

- `granularity`: `hour`(기본) 또는 `day`. 경계는 Asia/Seoul 정시·0시다.
- 기간은 M9 형식(오프셋 있는 시각, `[from, to)`)이다. 생략하면 오늘 0시부터 내일 0시까지다. `hour`는 최대 31일, `day`는 최대 366일이고 넘으면 422 `PERIOD_TOO_LONG`이다.
- 구간마다:

| 필드 | 의미 |
| --- | --- |
| `bucketStart`, `bucketEnd`, `start`, `end`, `partial`, `label` | M9 추세와 같은 구간 표현. `end`는 `min(bucketEnd, to, asOf)`. `label`은 시간 단위가 `YYYY-MM-DD HH:00`, 일 단위가 `YYYY-MM-DD`(Asia/Seoul) |
| `seatCount` | `end` 이전에 등록된 그 지점 좌석 수 |
| `occupiedSeatMinutes` | 좌석 이용의 `[effective_checked_in_at, effective_checked_out_at 또는 asOf)`과 구간 `[start, end)`가 겹친 분의 합(초 단위로 더한 뒤 분으로 내림) |
| `capacitySeatMinutes` | `seatCount × (end − start)`(분) |
| `occupancy` | `Ratio`(`occupiedSeatMinutes ÷ capacitySeatMinutes`). 분모 0이면 `notApplicable` |

- 모든 구간 값은 `seat_sessions`로 다시 계산할 수 있다(M13-D09).
- `asOf` 이후만 있는 구간은 `capacitySeatMinutes=0`, `occupancy.state=notApplicable`이다.
- 응답에 `branchId`, `granularity`, `from`, `to`, `asOf`, `timeZone`과 기간 전체 합(`totals`)을 담는다.
- 권한은 OWNER 또는 지점의 유효 소속이다. 기존 좌석 현황(`GET /seats/occupancy`)과 같다. 매출 정보가 아니라 `sales:read`는 필요 없다.

## 스키마 영향

다음 미사용 번호의 마이그레이션으로 추가한다(ADR-M13-001 스키마 영향). V1은 수정하지 않는다. 이슈마다 필요한 부분만 각 구현 PR에서 추가한다.

| 이슈 | 추가 |
| --- | --- |
| #112 | `branch_closures`(지점, 날짜, 안내, 처리자, version, 유일 키 지점+날짜) |
| #113 | `products`의 `sales_starts_at`, `sales_ends_at`, `sales_limit`. 변경 이력은 `product_revisions.snapshot_json`(스냅샷 `schemaVersion` 2)에 담겨 칼럼을 더하지 않는다(2026-11-10 #113 구현에서 정정) |
| #114 | `customers.status`의 `MERGED`, `customers.merged_into_customer_id`, `customer_merges`(대상, 흡수 고객, 중간 고객, 처리자, 시각, 사유) |
| #115 | `daily_closings`(지점, 날짜, 두 환불 기준 스냅샷, 처리자, 시각, 유일 키 지점+날짜), `daily_closing_adjustments`(마감, 환불, 결제, 금액, 처리자, 시각, 유일 키 환불) |
| #116 | 없음. 원거래에서 직접 계산한다. 인덱스가 부족하면 실행 계획을 확인한 뒤 인덱스만 추가한다 |

조정 이력은 사유를 복사하지 않고 `refunds.reason`을 참조한다.

## 범위 밖

- 시간 단위 휴무(일부 시간만 휴무), 휴무일에 겹치는 기존 예약의 일괄 취소
- 지점별 판매 한도, 판매 기간 예약 공개(예정 상품 노출)
- 병합 취소, 자동 병합, 병합 때 기존 기록의 고객 참조 변경
- 마감 취소·재마감, 마감으로 인한 거래 제한, 실제 결제사 정산 대사
- 부분 환불(V1 전액 환불뿐), 환불 유형의 추가 분류
- 매출의 이용 지점 귀속, 이용 지점 기준 매출 배분
- 좌석 차단 이력에 따른 점유율 분모 조정(차단 이력이 없다)

## 정책이 정하지 않아 이 계약에서 정한 것

ADR-M13-001에 사용자 결정 세 가지(M13-D11~D13)를 더했다. 사용자 결정 표시가 없는 항목은 리뷰에서 조정할 수 있다.

| 항목 | 정한 내용 | 이유 |
| --- | --- | --- |
| 마감 시점 | 끝난 날만, 처리일·원결제일 기준 모두 저장, 원결제일 영향 환불만 조정 이력 | **2026-11-10 사용자 결정**(M13-D11) |
| 이용 지점 | 매출 필터가 아니라 이용 지점별 사용량 지표 | **2026-11-10 사용자 결정**(M13-D12) |
| 중복 의심 기준 | 같은 숫자 연락처 | **2026-11-10 사용자 결정**(M13-D13) |
| 휴무일 등록 범위 | 오늘 ~ 366일 뒤 | 지난 날짜 등록은 안내 의미가 없고, 먼 미래는 입력 실수로 본다 |
| 휴무일 삭제 | 미래 휴무일만, 지난 휴무는 기록 | 이미 안내한 휴무 기록을 남긴다 |
| 휴무일 날짜 변경 | 없음, 삭제 후 재등록 | 등록 때 경고한 예약 목록과 날짜가 어긋나지 않게 한다 |
| 홈 휴무 안내 범위 | 오늘부터 7일 뒤까지 | 미리 알아야 할 휴무를 홈에서 본다 |
| 예약을 막는 기준 | 예약 시작 날짜 | ADR의 "그날 시작하는 신규 예약" |
| 판매 지점 검사 위치 | 판매 상태 바로 뒤 | 기존 순서(판매 중지 → 판매 지점)를 유지하고 ADR 순서를 그 뒤에 붙인다 |
| 판매 수 | `승인` 상태 결제 수, 카운터 칼럼 없음 | 환불하면 다시 팔 수 있다(M13-D02). 원거래와 어긋나지 않게 한다 |
| 한도 상품 잠금 | 상품 행 `FOR UPDATE`(한도 없는 상품은 공유 잠금 유지) | 동시 판매에서도 한도를 넘지 않는다(M13-D02) |
| 실패 결제의 판매 판정 | 같은 판정 | 판매 불가 상품의 실패 결제를 남기지 않는다 |
| 연락처가 다른 고객 병합 | 허용 | 중복 묶음은 후보 안내다 |
| 연쇄 병합 | 이전에 흡수된 고객도 새 대상으로 옮김 | 병합 대상을 한 단계로 유지해 조회를 단순하게 한다 |
| 흡수 고객의 진행 중 이용 | 그대로 두고 기존 ID로 처리 | 기존 참조를 바꾸지 않는다(M13-D07) |
| 흡수 고객의 남은 이용권 | 대상 고객이 사용 | **2026-11-10 사용자 결정**(#114). 그대로 두면 아무도 쓸 수 없다 |
| 병합 이력 열람 | 고객을 볼 수 있는 사용자 | 응대 중 병합 사실을 확인해야 한다 |
| 마감 생성 권한 | MANAGER 이상 + `sales:read` | 응답에 매출 금액이 있다 |
| 조정 이력 사유 | `payments:read`가 있을 때만 | 자유 입력 사유의 노출을 결제 열람 권한에 맞춘다 |
| 결제 수단별 소계 | 마감 스냅샷에 포함 | 일별 정산의 현금 확인 |
| 처리자 필터 의미 | 승인은 결제 처리자, 환불은 환불 처리자 | V1에 두 처리자 칼럼이 따로 있다 |
| 환불 유형 | 환불 기준 + 처리 지점 | ADR M13-D10 |
| 점유 추이 기본 구간 | `hour`, 오늘 하루 | 시간대별 혼잡 확인이 주 용도다 |
| 점유 추이 상한 | 시간 31일, 일 366일 | 응답 행 수를 744행·367행 이하로 둔다 |
| 점유율 분모의 좌석 | 구간 끝 이전에 등록된 좌석 전체 | 차단 이력이 없어 차단 좌석을 구간별로 뺄 수 없다 |
