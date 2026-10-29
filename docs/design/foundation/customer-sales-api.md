# 고객·Mock 결제·발급 이용권 API 계약

Backend [#55](https://github.com/OpenStudyService/OpenStudy-Backend/issues/55)의 M4 계약이다. 구현은 고객(#56), 결제·발급(#57), 환불(#58)에서 이 문서를 기준으로 진행하고, Frontend는 같은 [OpenAPI](openapi.json)(`0.1.5-m4-customer-sales`)로 연동한다. 정책 근거는 [ADR-M4-002](../../db/ADR-M4-002.md)(M4-D01~D08)와 [ADR-M3M4-001](../../db/ADR-M3M4-001.md)의 유지 결정(D01 결제 1건당 이용권 1장, D02 구매 조건 스냅샷)이다. 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을 따른다.

고객 operation 5개는 #56, 결제 생성·목록·상세와 이용권 목록·상세는 #57, 환불은 #58에서 구현했다(`implemented`).

## 제공 범위

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /customers` | 대상 지점의 유효 소속 | 고객 목록·검색(q·status, 정렬 registeredAt/name) |
| `POST /customers` | `registeredBranchId` 지점의 `members:write` | 고객 등록. `Idempotency-Key` 필수 |
| `GET /customers/{id}` | 대상 지점의 유효 소속 | 고객 상세 |
| `PATCH /customers/{id}` | 조회 범위를 만드는 지점 중 하나의 `members:write` | 이름·연락처·이메일·메모 수정 |
| `POST /customers/{id}/status` | 같음 | 정상·이용 제한·비활성 전환과 사유 기록 |
| `GET /payments` | scope 지점의 `payments:read` | 결제 목록(status·from·to·customerId, 정렬 createdAt/amountKrw) |
| `POST /payments` | `branchId` 지점의 `payments:write` | Mock 결제 생성과 이용권 발급. `Idempotency-Key` 필수 |
| `GET /payments/{id}` | 결제 지점의 `payments:read` | 결제 상세와 환불 사건 |
| `POST /payments/{id}/refund` | 원결제 지점의 `payments:write` | 미사용·미만료 이용권 결제 전액 환불. `Idempotency-Key` 필수 |
| `GET /passes` | 구매 지점의 유효 소속 | 발급 이용권 목록(customerId·status·kind, 정렬 createdAt/expiresAt) |
| `GET /passes/{id}` | 구매 지점의 유효 소속 | 발급 이용권 상세 |

OWNER는 모든 권한 조건을 만족한다. 고객·결제·이용권의 삭제 API는 없다(FR-MEM-06, FR-PAY-10).

## 지점 범위

- **고객 조회 범위(M4-D05)**: 고객의 `registered_branch_id`가 요청 scope 안에 있거나, scope 안의 지점에서 결제(승인·실패·환불 상태 무관)한 기록이 있는 고객만 보인다. 범위 밖 고객은 목록·total에 포함하지 않고 상세는 404다.
- `registeredBranchId`가 null인 고객(V2 이전에 결제 없이 저장된 행)은 OWNER만 본다. 아래 [V2 마이그레이션](#v2-마이그레이션) 참고.
- **이용권 조회 범위**: 구매 지점이 scope 안에 있는 이용권만 보인다. 고객이 보이더라도 다른 지점에서 산 이용권은 이 API로 보이지 않는다. 다른 지점 최소 요약은 M6(FR-MEM-03, POL-01)이다.
- **결제 조회 범위**: 결제 지점의 `payments:read`가 있어야 보인다. 어느 지점에도 `payments:read`가 없으면 결제 조회 API 전체가 403이다(공통 경로 가드). 다른 지점에서만 권한이 있으면 해당 결제 상세는 404다. 결제 생성(POST)은 이 가드에서 `payments:write`로 판정하므로, 결제 장부를 읽을 권한 없이 판매만 할 수 있다. 고객 상세·이용권 응답에는 결제 금액을 담지 않고 `purchasePaymentId`만 준다(NFR-SEC-06).
- 고객·이용권 읽기는 역할·행위 권한과 무관하게 해당 지점의 유효 소속이면 허용한다. 현장 응대(M5 입실, M7 문의)가 지점 직원 누구나의 업무이기 때문이다. 쓰기는 행위 권한을 따로 검사한다.
- 고객 쓰기(수정·상태 변경)는 그 고객을 조회 범위에 넣는 지점, 즉 등록 지점 또는 결제 지점 중 하나 이상에서 `members:write`를 가진 사용자에게 허용한다. 등록 지점은 변경하지 않는다(422 `IMMUTABLE_FIELD`).
- 결제 생성 시 고객은 요청자의 고객 조회 범위 안이어야 한다(아니면 404). 범위 밖 고객에게 파는 경로는 M5 정확 일치 조회(POL-08)와 함께 다룬다.

## 고객

- 필수 입력은 등록 지점·이름(1~100자)·연락처다. 연락처는 하이픈·공백을 제거한 숫자 9~11자리로 검증하고 숫자만 저장한다. 이메일(254자)과 응대 메모(1000자, M4-D06)는 선택이며 빈 문자열은 null로 저장한다.
- 회원번호는 서버가 만드는 불변 문자열(최대 40자)이다. 형식은 계약이 아니며 클라이언트가 보낼 수 없다.
- 연락처 중복은 막지 않는다. 중복 고객 병합은 M13(FR-MEM-08)이다.
- 목록 항목은 회원번호·이름·연락처·상태·등록 지점·등록 시각만 담는다. 이메일·메모·상태 사유는 상세에서만 준다(FR-MEM-05).
- 목록 q는 이름 부분 일치, 회원번호 정확 일치, 숫자 4자리 이상이면 연락처 부분 일치다. pageSize 상한 100으로 전체 추출을 막는다.
- 운영 중지 지점에는 고객을 등록할 수 없다(409 `BRANCH_NOT_ACTIVE`).
- 상태 변경은 `customer_status_events`에 이전·새 상태·사유·처리자를 남기고 `customers.status_reason`을 갱신한다. 같은 상태 요청은 현재 값을 돌려주며 이력·감사를 만들지 않는다. 상태와 관계없이 결제·이용권·이용 기록은 바뀌지 않는다(FR-MEM-06).
- 수정·상태 변경은 고객 version으로 충돌을 막는다(409 `VERSION_CONFLICT`, `reloadUrl=/api/customers/{id}`). 변경자와 시각은 감사로 남긴다(FR-MEM-04).

## Mock 결제와 발급

요청 본문은 `customerId`, `branchId`, `productId`, `method`(현금·카드·계좌이체·기타), 선택 `mockOutcome`(`APPROVED` 기본, `FAILED`)뿐이다. **금액·수량·상품명·발급 조건을 받는 필드는 없다.** 정의되지 않은 필드(`amountKrw` 등)는 422다(FR-PAY-02).

서버 검증 순서와 결과:

| 조건 | 결과 |
| --- | --- |
| `branchId`의 `payments:write` 없음 | 403 `FORBIDDEN` |
| 지점이 운영 중지 | 409 `BRANCH_NOT_ACTIVE` |
| 고객이 조회 범위 밖이거나 없음 | 404 `NOT_FOUND` |
| 고객 상태가 정상이 아님 | 409 `CUSTOMER_NOT_ACTIVE` |
| 상품이 보이지 않음 | 404 `NOT_FOUND` |
| 상품 판매 중지 | 409 `PRODUCT_NOT_ON_SALE` |
| `branchId`가 상품 판매 지점이 아님 | 409 `PRODUCT_NOT_AVAILABLE_AT_BRANCH` |

- 금액은 `ProductService.requireSellable(productId, branchId)`가 공유 잠금 아래 돌려준 스냅샷의 가격이다. 이 스냅샷으로 `payments.product_name_snapshot`과 `passes.snapshot_*`, `purchase_product_revision_id`를 채운다(FR-PASS-03).
- `mockOutcome=APPROVED`: 결제를 `APPROVED`(`approved_at`=처리 시각, `issued_pass_quantity=1`)로 저장하고 이용권 1장을 발급한다. `FAILED`: 결제를 `FAILED`(`failed_at`, `issued_pass_quantity=0`)로 저장하고 이용권을 만들지 않는다(FR-PAY-04). 두 경우 모두 201이며 응답 `status`로 구분한다.
- Mock 결제는 요청 안에서 결과가 확정되므로 `PENDING` 상태를 저장하거나 응답하지 않는다.
- 응답 `mock`은 항상 true다. 화면은 실제 금전 이동이 없음을 표시한다(FR-PAY-01).
- 실패 결제는 매출·판매량에 포함하지 않는다. 집계는 M9에서 이 상태값으로 판정한다.

이용권 발급 값(M4-D01~D03):

| 유형 | `status` | `started_at` | `expires_at` | 잔량 |
| --- | --- | --- | --- | --- |
| 시간권 | ACTIVE | 승인 시각 | `valid_days`가 있으면 started_at + valid_days × 24시간, 없으면 NULL | `remaining_minutes` = 제공 분 |
| 정액권 | ACTIVE | 승인 시각 | 시간권과 같음 | `remaining_credit_krw` = 제공 금액 |
| 기간권 | ACTIVE | 승인 시각 | started_at + period_days × 24시간 | 없음 |

`NOT_STARTED`는 만들지 않는다. 시각은 UTC로 저장하고 응답은 ISO 8601 UTC다. 경계는 `[startedAt, expiresAt)`이다(NFR-DATA-05).

## 환불

요청 본문은 `reason`(1~500자)뿐이며 **금액 필드는 없다.** 환불 금액은 원결제 `amount_krw` 전액이다(부분 환불 없음).

| 조건(잠금 후 재확인) | 결과 |
| --- | --- |
| 원결제 지점의 `payments:write` 없음 | 403 `FORBIDDEN`. 원결제 지점에 유효 소속이 없으면 결제가 보이지 않으므로 404 |
| 결제가 실패 상태 | 409 `PAYMENT_NOT_REFUNDABLE` |
| 이미 환불됨(다른 키) | 409 `ALREADY_REFUNDED` |
| 이용권에 `seat_sessions` 또는 `pass_balance_events`의 USE 행이 있음 | 409 `PASS_ALREADY_USED` |
| `expires_at`이 있고 처리 시각 ≥ `expires_at` | 409 `PASS_EXPIRED` (저장 상태가 아직 ACTIVE여도 시각으로 판정) |

- 사용 여부는 이용권 상태가 아니라 이력으로 판정한다(ADR-M4-002 미사용·만료 판정).
- 처리 지점(`refunds.processing_branch_id`)은 원결제 지점으로 고정한다(M4-D08). 타 지점 환불은 후속이다.
- 성공하면 `refunds` 행, 결제 `REFUNDED`, 이용권 `REFUNDED`를 함께 남긴다. 이용권의 `started_at`·`expires_at`과 결제의 `amount_krw`·`approved_at`은 바꾸지 않는다(FR-PAY-10).
- 응답은 갱신된 `Payment`(200)이며 `refund`에 환불 사건이 담긴다.
- 스터디룸·사물함 결제 환불은 M6다. M4 API로 만들 수 있는 결제는 이용권 결제뿐이다.

## 발급 이용권 조회

- 응답 `status`는 요청 처리 시각의 실효 상태다. 환불 > 만료(`expiresAt` ≤ 현재) > 소진 > 활성 순으로 판정한다. `status` 검색 조건도 같은 규칙으로 계산하므로 검색 결과와 각 이용권의 표시 상태가 일치한다(FR-PASS-11).
- `expiresAt`이 null이면 화면은 임의 날짜 대신 "만료 없음"을 표시한다(FR-PASS-01). 시간권은 분, 정액권은 원, 기간권은 시작·종료 일시를 보여 준다.
- `terms`는 발급 당시 조건 스냅샷으로, 상품 API의 `ProductTerms`와 같은 모양이다. 현재 상품 수정은 반영하지 않는다.
- `used`는 실제 사용 이력 유무, `canRefund`는 환불 버튼 노출용 힌트다. 서버는 환불 요청 때 다시 검증한다.
- 가격은 담지 않는다. 결제 금액은 `purchasePaymentId`로 결제 API에서 조회한다(`payments:read` 필요).

## 멱등 키

- `POST /customers`, `POST /payments`, `POST /payments/{id}/refund`는 `Idempotency-Key`(16~128자)가 필수다. 누락은 422다.
- 공통 저장소 `idempotency_records`를 쓴다. 범위는 (요청자, operation, scope key, 키)이며 scope key는 고객 등록·결제 생성은 대상 지점 ID, 환불은 결제 ID다.
- 요청 해시는 허용 필드만 정규화한 본문과 path ID로 계산한다. 같은 키·같은 입력이면 최초 처리의 HTTP 상태와 본문을 그대로 돌려주고 새 거래·이용권·환불·감사를 만들지 않는다. **같은 키·다른 입력은 409 `IDEMPOTENCY_CONFLICT`다.** 처리 중이면 409 `REQUEST_IN_PROGRESS`와 `Retry-After`를 줄 수 있다.
- 결제 실패(`mockOutcome=FAILED`)도 성공 응답(201)이므로 멱등 결과로 저장한다. 다시 시도하려면 새 키를 쓴다.
- 권한·CSRF·입력 형식 검증은 멱등 조회보다 먼저 한다. 4xx 거절은 멱등 결과로 저장하지 않는다.
- `payments.idempotency_key`와 `refunds.idempotency_key`(전역 UNIQUE, 120자)에는 클라이언트 키 원문 대신 서버가 만든 값(예: operation·요청자·키 해시의 조합)을 저장한다. 다른 직원이 같은 키를 써도 충돌하지 않게 하고 DB 길이 제한을 지키기 위해서다. DB UNIQUE는 중복 결제·환불의 마지막 방어선이다.
- 기본 보존 24시간은 기존 공통 규칙의 기술 제안을 따른다.

## 트랜잭션 경계

모든 쓰기는 기존 `AuthorizedCommands` 잠금 순서(가드 → 직원 → 지점 → 업무 행)를 따르고, 권한 회수는 다음 요청부터 반영한다.

| 명령 | 한 트랜잭션에 묶는 작업 |
| --- | --- |
| 고객 등록 | 멱등 잠금 → 고객 행 → 감사 → `customers` 이벤트 → 멱등 결과 |
| 고객 수정·상태 변경 | 고객 행 잠금·version 확인 → 변경(상태 변경은 `customer_status_events`) → 감사 → 이벤트 |
| 결제 생성 | 멱등 잠금 → 고객 공유 잠금·상태 확인 → `requireSellable` 상품 공유 잠금 → 결제 행 → (승인 시) 이용권 행 → 감사 → `payments`·`passes` 이벤트 → 멱등 결과 |
| 환불 | 멱등 잠금 → 결제 행 잠금 → 이용권 행 잠금 → 사용 이력·만료 재확인 → `refunds` 행 → 결제·이용권 `REFUNDED` → 감사 → 이벤트 → 멱등 결과 |

중간에 하나라도 실패하면 전체를 롤백한다. 승인 결제만 남거나 이용권만 남는 부분 성공은 없다(FR-PAY-08, FR-PAY-09). DB의 `ck_payments_issued_pass_quantity`, `uk_passes_1`, `fk_passes_3`, `uk_refunds_1`이 같은 불변식을 한 번 더 막는다.

지점별 무효화 이벤트(`WorkspaceEvent.resource`)에 `customers`, `payments`, `passes`를 추가했다. 이벤트는 entityId·branchId만 담고 개인정보·금액을 담지 않는다. 고객 이벤트의 branchId는 등록 지점이다.

## 오류 코드

공통 코드([api.md](api.md))에 더해 이 범위에서 쓰는 코드다.

| HTTP | code | 쓰는 곳 |
| --- | --- | --- |
| 404 | `NOT_FOUND` | 범위 밖·없는 고객, 보이지 않는 상품·결제·이용권 |
| 409 | `BRANCH_NOT_ACTIVE` | 운영 중지 지점의 고객 등록·결제 |
| 409 | `CUSTOMER_NOT_ACTIVE` | 정상이 아닌 고객에게 결제 |
| 409 | `PRODUCT_NOT_ON_SALE`, `PRODUCT_NOT_AVAILABLE_AT_BRANCH` | 판매 불가 상품(상품 API와 같은 코드) |
| 409 | `PAYMENT_NOT_REFUNDABLE` | 실패 결제 환불 |
| 409 | `ALREADY_REFUNDED` | 이미 환불된 결제 |
| 409 | `PASS_ALREADY_USED` | 사용 이력이 있는 이용권 환불 |
| 409 | `PASS_EXPIRED` | 만료된 이용권 환불 |
| 409 | `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `REQUEST_IN_PROGRESS` | 공통 |
| 422 | `VALIDATION_FAILED`, `IMMUTABLE_FIELD` | 형식·범위·허용 외 필드, 변경 불가 필드 |

각 operation의 사용 코드는 OpenAPI의 `x-error-codes`에 적었다.

## V2 마이그레이션

`V2__customer_branch_and_memo.sql`이 M4-D05·D06의 열 두 개만 추가한다.

- `customers.registered_branch_id BIGINT UNSIGNED NULL`, `branches(id)` FK `ON DELETE RESTRICT`, 인덱스 `ix_customers_registered_branch`.
- `customers.memo VARCHAR(1000) NULL`.
- **기존 고객 행의 처리**: 결제가 있는 고객은 첫 결제(`created_at`, `id` 순)의 지점으로 채운다. 결제가 없는 고객은 NULL로 남기고 OWNER만 본다. 임의 지점에 넣으면 그 지점 직원에게 무관한 고객 정보가 노출되기 때문이다. 이 보정은 업무 변경이 아니므로 `updated_at`·`version`을 바꾸지 않는다.
- 열은 NULL을 허용하지만 신규 등록은 항상 등록 지점을 받는다(API 필수 필드, 서비스 보장). NULL 고객에게 등록 지점을 지정하는 경로는 필요해지면 후속에서 정한다.
- V1은 바꾸지 않는다. 버전 번호는 병합 직전 main의 가장 큰 버전 + 1로 맞춘다([마이그레이션 규칙](../../../src/main/resources/db/migration/README.md)).

## 이번 범위에서 제외

- 서비스·컨트롤러 구현(#56~#58)과 Frontend 연동(#19/#20).
- 타 지점 고객 최소 요약과 열람 기록(M6, FR-MEM-03·POL-01), 타 지점 입실용 정확 일치 조회(M5, POL-08).
- 스터디룸·사물함 결제·환불(M6), 결제 고급 필터(M13), 중복 고객 병합(M13), 개인정보 파기(M11).
- 이용권 사용·차감·수동 조정(M5), 만료 상태의 배치 갱신(조회 시각으로 판정하므로 M4에 필요 없음).
