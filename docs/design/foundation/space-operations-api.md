# 스터디룸·사물함·타 지점 요약 API 계약

Backend [#67](https://github.com/OpenStudyService/OpenStudy-Backend/issues/67)의 M6 계약이다. 구현은 스키마(#68), 룸·예약(#69), 취소·이용(#70), 사물함(#71), 타 지점 요약·이력(#72)에서 이 문서를 기준으로 진행한다. Frontend는 같은 [OpenAPI](openapi.json)(`0.1.8-m6-space-operations`)로 연동한다.

정책 근거는 [ADR-M6-001](../../db/ADR-M6-001.md)(M6-D01~D06)이다. 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을, 결제·환불은 [M4 계약](customer-sales-api.md)을, 고객 조회 범위는 ADR-M4-002 M4-D05를 따른다.

이 문서의 operation은 모두 설계 계약(`design-target`)이며 아직 서버에 구현되어 있지 않다. 이 이슈에서 스키마는 바꾸지 않는다. 룸 시간 단위 열과 새 권한은 #68의 마이그레이션이 추가한다. 사물함 표는 V1 그대로 쓴다.

## 제공 범위

### 룸

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /rooms` | scope 지점의 유효 소속 | 룸 목록 | #69 |
| `POST /rooms` | `branchId` 지점의 `operations:write` | 룸 등록. `Idempotency-Key` 필수 | #69 |
| `GET /rooms/{id}` | 룸 지점의 유효 소속 | 룸 상세 | #69 |
| `PATCH /rooms/{id}` | 룸 지점의 `operations:write` | 번호·이름·정원·시간당 요금 수정 | #69 |
| `POST /rooms/{id}/status` | 룸 지점의 `operations:write` | 운영 중단·재개 | #69 |

### 예약·이용

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /room-reservations` | scope 지점의 유효 소속 | 예약 목록 | #69 |
| `POST /room-reservations` | `branchId` 지점의 `payments:write` | 예약 생성과 선결제. `Idempotency-Key` 필수 | #69 |
| `GET /room-reservations/calendar` | `branchId` 지점의 유효 소속 | 기간 달력 | #69 |
| `GET /room-reservations/{id}` | 예약 지점의 유효 소속 | 예약 상세 | #69 |
| `POST /room-reservations/{id}/cancel` | 예약 지점의 `payments:write` | 취소·전액 환불. `Idempotency-Key` 필수 | #70 |
| `POST /room-reservations/{id}/start` | 예약 지점의 유효 소속 | 이용 시작 | #70 |
| `POST /room-reservations/{id}/complete` | 예약 지점의 유효 소속 | 이용 완료 | #70 |

### 사물함

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /lockers` | scope 지점의 유효 소속 | 사물함 목록과 배정 상태 | #71 |
| `POST /lockers` | `branchId` 지점의 `operations:write` | 사물함 등록. `Idempotency-Key` 필수 | #71 |
| `GET /lockers/occupancy` | scope 지점의 유효 소속 | 지점별 현황 | #71 |
| `GET /lockers/{id}` | 사물함 지점의 유효 소속 | 사물함 상세 | #71 |
| `PATCH /lockers/{id}` | 사물함 지점의 `operations:write` | 번호·4주 요금 수정 | #71 |
| `POST /lockers/{id}/status` | 사물함 지점의 `operations:write` | 운영 중단·재개 | #71 |
| `POST /branches/{id}/locker-rate` | 해당 지점의 `operations:write` | 지점 요금 일괄 적용. `Idempotency-Key` 필수 | #71 |
| `GET /locker-assignments` | scope 지점의 유효 소속 | 배정 목록 | #71 |
| `POST /locker-assignments` | `branchId` 지점의 `payments:write` | 유료 배정. `Idempotency-Key` 필수 | #71 |
| `GET /locker-assignments/{id}` | 배정 지점의 유효 소속 | 배정 상세 | #71 |
| `POST /locker-assignments/{id}/extend` | 배정 지점의 `payments:write` | 유료 연장. `Idempotency-Key` 필수 | #71 |
| `POST /locker-assignments/{id}/return` | 배정 지점의 유효 소속 | 반납 | #71 |

### 고객

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `POST /customers/{id}/cross-branch-summary` | OWNER, 응대 지점 MANAGER, `customers:cross-branch-read`(M6-D06) | 타 지점 최소 요약 | #72 |
| `GET /customers/{id}/space-history` | scope 지점의 유효 소속 + 고객 조회 범위 | 고객의 룸·사물함 이력 | #72 |

## 공통 규칙

- OWNER는 모든 권한 조건을 만족한다. 룸·사물함·예약·배정의 삭제 API는 없다. 룸·사물함은 운영 중단으로 닫는다.
- **권한 기준(M6-D05)**: 원장과 요금은 `operations:write`, 돈이 움직이는 예약·배정·연장·취소는 `payments:write`, 결제가 없는 이용 시작·완료와 반납은 해당 지점 직원이면 된다.
- **범위**: 범위 밖 자원은 목록·total에 없고 상세는 404다. scope에 없는 지점을 지정하면 403이다.
- **요청 키**: 등록·예약·배정·연장·취소는 `Idempotency-Key`가 필수다. 같은 키·같은 입력은 저장된 결과, 같은 키·다른 입력은 409 `IDEMPOTENCY_CONFLICT`다. 결제가 중복 생성되지 않는다.
- **금액**: 요청에 금액·시간 수·과금 구간 필드가 없다. 서버가 잠근 원장의 현재 요금으로 계산한다. 정의되지 않은 필드는 422다.
- **결제 정보 노출**: 금액과 결제 참조(`billing`, `charges`)는 해당 지점의 `payments:read` 또는 `payments:write`가 있어야 값이 있고 없으면 null이다. 좌석·이용권 응답이 결제 금액을 담지 않는 것과 같은 기준이다. `payments:write`를 포함하는 이유는 결제를 처리한 직원이 승인·실패 결과를 응답에서 확인해야 하기 때문이다. 룸의 시간당 요금과 사물함의 4주 요금은 공개된 판매 가격이므로 원장 조회에 포함한다.
- **고객**: 예약·배정 대상 고객은 M4 판매와 같이 요청자의 고객 조회 범위 안이어야 하고(아니면 404), 정상 상태여야 한다(아니면 409 `CUSTOMER_NOT_ACTIVE`). 응답의 고객은 `CustomerSummary`만 담는다. 정확 일치 조회로 찾은 다른 지점 고객의 예약·배정은 범위 밖이다.
- **운영 중지 지점(FR-BR-08)**: 신규 예약·배정·연장·이용 시작은 409 `BRANCH_NOT_ACTIVE`다. 취소 환불·이용 완료·반납과 조회는 허용한다. 룸·사물함 원장 수정은 운영 설정이므로 허용한다.
- 수정은 `version`을 받고 충돌하면 409 `VERSION_CONFLICT`, `reloadUrl`은 해당 상세 주소다. `version` 외 항목이 없는 `PATCH`는 422다.
- 변경 알림(`GET /workspace/events`)의 `resource`에 `rooms`, `room-reservations`, `lockers`, `locker-assignments`를 추가한다. 금액·고객 정보는 담지 않는다.

### 추가 오류 코드

| HTTP | code | 의미 |
| --- | --- | --- |
| 409 | `ROOM_TIME_CONFLICT` | 같은 룸의 활성 예약과 시간이 겹침 |
| 409 | `DUPLICATE_ROOM_NO` | 같은 지점의 룸 번호 중복 |
| 409 | `RESERVATION_NOT_CANCELLABLE` | 확정이 아니거나 시작 시각이 지난 예약의 취소 |
| 409 | `ROOM_BLOCKED` | 운영 중단 룸의 이용 시작 |
| 409 | `ROOM_IN_USE` | 같은 룸에 다른 이용 중 예약이 있음 |
| 409 | `DUPLICATE_LOCKER_NO` | 같은 지점의 사물함 번호 중복 |
| 409 | `LOCKER_PERIOD_CONFLICT` | 같은 사물함의 활성 배정과 기간이 겹침 |
| 409 | `LOCKER_NOT_RETURNED` | 기간이 끝났지만 반납되지 않은 배정이 있음 |
| 422 | `RESERVATION_TIME_INVALID` | 정시가 아닌 시작, 1시간 단위가 아닌 길이, 시작보다 이른 종료, 과거 시작, 24시간 초과 |
| 422 | `RESERVATION_CAPACITY_EXCEEDED` | 요청 인원이 정원을 넘음 |
| 422 | `ROOM_NOT_AVAILABLE` | 운영 중단 룸에 대한 예약 |
| 422 | `ROOM_BRANCH_MISMATCH` | 룸이 요청 지점 소속이 아님 |
| 422 | `LOCKER_TERM_INVALID` | 28일 배수가 아닌 기간, 오늘 이전 시작 |
| 422 | `LOCKER_NOT_AVAILABLE` | 운영 중단 사물함에 대한 배정·연장 |
| 422 | `LOCKER_BRANCH_MISMATCH` | 사물함이 요청 지점 소속이 아님 |

입력 오류(지점·시간·인원·운영 상태)는 422, 다른 예약·배정과의 충돌은 409다(FR-ROOM-02). 화면은 입력을 고치도록 안내할지, 다른 시간을 고르도록 안내할지 이 코드로 구분한다.

## 룸

- 필드는 지점·룸 번호(1~40자)·이름(1~120자)·정원·시간당 요금·운영 상태다. 같은 지점의 룸 번호 중복은 409 `DUPLICATE_ROOM_NO`(DB `uk_rooms_1`)다.
- `PATCH`로 이름·요금을 바꿔도 기존 예약의 지점·룸 참조와 요금 스냅샷은 그대로다.
- 운영 중단(`POST /rooms/{id}/status`)은 사유가 필수이고, 전후 값·작업자·사유를 감사에 남긴다. **운영 중단해도 기존 예약은 유지한다.** 다만 그 룸의 이용 시작은 거절된다(FR-ROOM-08). 필요한 예약은 관리자가 취소한다.
- 룸 운영 시간(`room_weekly_hours`) 검증은 범위 밖이다(ADR-M6-001).

## 예약

`POST /room-reservations`. 본문은 `branchId`, `roomId`, `customerId`, `startAt`, `endAt`, `partySize`, `method`, 선택 `mockOutcome`이다.

서버 검증 순서(모두 잠금 뒤 재확인):

| 조건 | 결과 |
| --- | --- |
| `branchId` 지점의 `payments:write` 없음 | 403 `FORBIDDEN` |
| 룸이 없거나 범위 밖 | 404 `NOT_FOUND` |
| 룸이 `branchId` 소속이 아님 | 422 `ROOM_BRANCH_MISMATCH` |
| 지점 운영 중지 | 409 `BRANCH_NOT_ACTIVE` |
| 룸 운영 중단 | 422 `ROOM_NOT_AVAILABLE` |
| 정시 시작 아님, 1시간 배수 아님, `endAt ≤ startAt`, `startAt ≤ 현재`, 24시간 초과 | 422 `RESERVATION_TIME_INVALID` |
| `partySize > capacity` | 422 `RESERVATION_CAPACITY_EXCEEDED` |
| 고객이 없거나 조회 범위 밖 | 404 `NOT_FOUND` |
| 고객이 정상이 아님 | 409 `CUSTOMER_NOT_ACTIVE` |
| 활성 예약과 시간 겹침 | 409 `ROOM_TIME_CONFLICT` |
| 성공 | 201 |

- **정시**: `startAt`의 분·초·마이크로초가 0이어야 한다. 한국 표준시와 UTC의 차이가 정수 시간이므로 어느 쪽으로 판정해도 같다. `endAt`도 같은 규칙이다.
- **이용 시간**은 최소 1시간, 최대 24시간이다. 상한은 슬롯 행이 무제한으로 생기는 것을 막기 위한 값이다.
- **인원**: `partySize`는 정원 검증에만 쓴다. V1과 #68에 저장 열이 없어 응답에 나오지 않는다.
- **활성 예약**은 `확정`·`이용 중`이다. `결제 실패`·`취소`·`완료`는 시간을 점유하지 않는다.
- **동시 예약**: 슬롯은 예약 시간마다 한 행이고 DB `uk_room_reservation_slots_1`(`room_id`, `slot_start_at`)이 마지막 방어선이다. 유일 제약 위반은 409 `ROOM_TIME_CONFLICT`로 바꾸고 전체를 롤백한다. 결제가 남지 않는다.

### 요금(M6-D01)

```text
hours = (endAt − startAt) ÷ 1시간
amountKrw = 예약 당시 룸의 시간당 요금 × hours
```

| 시간당 요금 | 예약 | 시간 | 금액 |
| --- | --- | --- | --- |
| 8,000원 | 14:00~15:00 | 1 | 8,000원 |
| 8,000원 | 14:00~17:00 | 3 | 24,000원 |
| 12,500원 | 09:00~11:00 | 2 | 25,000원 |
| 8,000원 | 14:30~15:30 | - | 422 `RESERVATION_TIME_INVALID` (정시 아님) |
| 8,000원 | 14:00~15:30 | - | 422 `RESERVATION_TIME_INVALID` (1시간 단위 아님) |

예약 후 요금을 바꿔도 금액은 그대로다. 시간당 요금 스냅샷과 시간 수를 예약에 저장한다.

### 결제

- `mockOutcome=APPROVED`(기본): 결제 `승인`, 예약 `확정`, 슬롯 점유를 한 트랜잭션으로 확정한다. 201이다.
- `mockOutcome=FAILED`: 실패 결제와 `결제 실패` 예약만 남기고 슬롯을 점유하지 않는다. 201이며 `status`로 구분한다. M4와 같이 멱등 결과로 저장하므로 다시 시도하려면 새 키를 쓴다.
- 시간 충돌은 결제보다 먼저 판정한다. 충돌한 요청에는 결제가 남지 않는다.
- Mock 결제는 요청 안에서 확정되므로 `PAYMENT_PENDING` 상태를 저장하거나 응답하지 않는다.
- 결제 행은 `purpose=ROOM`, `product_id=null`이다.

### 조회

- `GET /room-reservations`: 필터 `roomId`, `customerId`, `status`, `from`, `to`. 기간 필터는 구간이 겹치는 예약(`endAt > from`, `startAt < to`)을 고른다. 정렬은 `startAt` 내림차순으로 고정한다.
- `GET /room-reservations/calendar?branchId=&roomId=&from=&to=`: 한 지점의 기간과 겹치는 예약 **전체**를 준다. 달력에는 페이지가 맞지 않기 때문이다. 기간은 최대 62일이고 넘으면 422다. `결제 실패`는 시간을 점유하지 않으므로 담지 않는다.
- 목록·달력·상세는 같은 DTO와 같은 상태값을 쓴다(FR-ROOM-05).

## 취소·재예약(M6-D04)

`POST /room-reservations/{id}/cancel`. 본문은 `version`, `reason`(1~500자, 필수)이다.

| 조건(잠금 후 재확인) | 결과 |
| --- | --- |
| 예약이 없거나 범위 밖 | 404 `NOT_FOUND` |
| `payments:write` 없음 | 403 `FORBIDDEN` |
| version이 다름 | 409 `VERSION_CONFLICT` |
| 상태가 `확정`이 아님 | 409 `RESERVATION_NOT_CANCELLABLE` |
| 처리 시각 ≥ `startAt` | 409 `RESERVATION_NOT_CANCELLABLE` |
| 성공 | 200 |

- 시작 시각 이후에는 이용 시작이 없어도 취소·환불하지 않는다.
- 성공하면 `refunds`에 전액 환불, 결제 `REFUNDED`, 예약 `CANCELLED`, 슬롯 삭제를 한 트랜잭션으로 처리한다. 처리 지점은 원결제 지점이다. 슬롯이 사라져 같은 시간에 새 예약을 받을 수 있다.
- 같은 키의 재시도는 환불을 다시 만들지 않는다.
- **재예약 API는 없다.** 취소 후 새 키로 새 예약을 만든다. 취소된 예약과 원결제는 그대로 남는다.
- 이용권 환불 API(`POST /payments/{id}/refund`)는 이용권 결제만 받는다. 스터디룸은 이 취소로만 환불하고 사물함은 환불하지 않으므로, 다른 목적의 결제에는 409 `PAYMENT_NOT_REFUNDABLE`이다. `canRefund`도 false다.

## 이용 시작·완료(FR-ROOM-08)

`POST /room-reservations/{id}/start`, `.../complete`. 본문은 `version`뿐이다.

| 동작 | 조건 | 오류 |
| --- | --- | --- |
| 시작 | 상태가 `확정` | 409 `INVALID_STATE` |
| 시작 | 지점 운영 중 | 409 `BRANCH_NOT_ACTIVE` |
| 시작 | 룸 운영 중 | 409 `ROOM_BLOCKED` |
| 시작 | 같은 룸에 다른 `이용 중` 예약 없음 | 409 `ROOM_IN_USE` |
| 완료 | 상태가 `이용 중` | 409 `INVALID_STATE` |

- 시작 시각은 예약 시각이 아니라 서버 처리 시각이다. 예약 구간과 비교해 거절하지 않는다. 일찍 온 고객과 늦은 처리를 현장에서 받기 위해서다.
- 완료는 운영 중지 지점과 운영 중단 룸에서도 허용한다.
- 시작·완료는 `room_usages`에 시각과 처리자를 남기고 예약 상태를 `이용 중`·`완료`로 바꾼다. 응답의 `usage`로 확인한다.
- 노쇼 자동 처리는 범위 밖이다. 시작 없이 시간이 지난 예약은 `확정`으로 남는다.

## 사물함

- 필드는 지점·번호(1~40자)·4주 요금·운영 상태다. 같은 지점의 번호 중복은 409 `DUPLICATE_LOCKER_NO`(DB `uk_lockers_1`)다.
- **배정 상태**(`assignmentState`)는 저장하지 않고 계산한다. 순서대로 판정한다.

| 조건 | 값 |
| --- | --- |
| 운영 중단 | `운영 중단` |
| `ACTIVE` 배정이 있고 `startDate ≤ 오늘` | `사용 중` |
| 그 외 | `사용 가능` |

- 오늘은 Asia/Seoul 날짜다. 기간이 끝났지만 반납되지 않은 배정은 `사용 중`으로 세고 `overdue=true`다. 실제로 물건이 들어 있기 때문이다.
- 시작 전 예정 배정은 `사용 가능`이다.
- `GET /lockers/occupancy`의 수치는 같은 계산을 쓴다. `inUse + available + blocked = total`이고 `overdue`는 `inUse`에 포함된다. 운영 중단 사물함에 활성 배정이 있으면 `blocked`로만 센다.
- 운영 중단은 사유가 필수이고 활성 배정이 있어도 할 수 있다. 신규 배정·연장만 막고 반납은 허용한다.
- **요금 일괄 적용**(`POST /branches/{id}/locker-rate`): 지점의 모든 사물함 요금을 같은 값으로 바꾼다. 운영 중단 사물함도 포함한다. 각 사물함의 version이 오르고 감사는 지점 단위 한 건이다. 기존 배정의 스냅샷은 바뀌지 않는다.

## 사물함 배정

`POST /locker-assignments`. 본문은 `branchId`, `lockerId`, `customerId`, `startDate`, `endDate`, `method`, 선택 `mockOutcome`이다.

- **기간은 `[startDate, endDate)`** 이다. `endDate` 당일부터 다음 고객을 배정할 수 있다. DB의 `end_date - start_date = 과금 구간 × 28`과 같은 뜻이다.
- `startDate`는 오늘(Asia/Seoul) 이후여야 한다.

서버 검증 순서(모두 사물함 행 잠금 뒤 재확인):

| 조건 | 결과 |
| --- | --- |
| `branchId` 지점의 `payments:write` 없음 | 403 `FORBIDDEN` |
| 사물함이 없거나 범위 밖 | 404 `NOT_FOUND` |
| 사물함이 `branchId` 소속이 아님 | 422 `LOCKER_BRANCH_MISMATCH` |
| 지점 운영 중지 | 409 `BRANCH_NOT_ACTIVE` |
| 사물함 운영 중단 | 422 `LOCKER_NOT_AVAILABLE` |
| 기간이 28일 배수가 아님, `startDate < 오늘` | 422 `LOCKER_TERM_INVALID` |
| 고객이 없거나 조회 범위 밖 | 404 `NOT_FOUND` |
| 고객이 정상이 아님 | 409 `CUSTOMER_NOT_ACTIVE` |
| 미반납 초과 배정이 있음 | 409 `LOCKER_NOT_RETURNED` |
| 활성 배정과 기간이 겹침 | 409 `LOCKER_PERIOD_CONFLICT` |
| 성공 | 201 |

- 겹침은 `a.startDate < b.endDate`이고 `b.startDate < a.endDate`인 경우다.
- 활성 배정은 `ACTIVE`뿐이다. `반납`·`결제 실패`·`취소`는 기간을 점유하지 않는다.
- 기간 겹침을 막는 DB 제약이 없으므로 **사물함 행 잠금으로 보장한다.** 같은 사물함의 배정·연장은 잠금 안에서 순서대로 판정한다.

### 과금(M6-D02·D03)

```text
terms = (endDate − startDate) ÷ 28일
amountKrw = terms × 결제 당시 사물함의 4주 요금
```

| 4주 요금 | 시작일 | 종료일 | 일수 | 구간 | 금액 |
| --- | --- | --- | --- | --- | --- |
| 12,000원 | 2026-11-01 | 2026-11-29 | 28 | 1 | 12,000원 |
| 12,000원 | 2026-11-01 | 2026-12-27 | 56 | 2 | 24,000원 |
| 15,000원 | 2026-11-01 | 2027-01-24 | 84 | 3 | 45,000원 |
| 12,000원 | 2026-11-01 | 2026-12-01 | 30 | - | 422 `LOCKER_TERM_INVALID` |

남는 날짜를 일할 계산하지 않는다. 결제 행은 `purpose=LOCKER`, `product_id=null`이다. `locker_assignment_charges`에 기간·구간·요금 스냅샷·금액을 남긴다.

- `mockOutcome=APPROVED`: 결제 `승인`, 배정 `ACTIVE`, 과금 `적용`을 한 트랜잭션으로 확정한다. 201이다.
- `mockOutcome=FAILED`: 실패 결제, `결제 실패` 배정, `실패` 과금만 남긴다. 기간을 점유하지 않는다. 201이며 다시 시도하려면 새 키를 쓴다.

### 연장

`POST /locker-assignments/{id}/extend`. 본문은 `version`, `endDate`(연장 후 새 종료일), `method`, 선택 `mockOutcome`이다.

- `ACTIVE` 배정만 연장한다. 아니면 409 `INVALID_STATE`다.
- 연장 구간은 `[현재 endDate, 새 endDate)`이고 28일의 배수여야 한다. 아니면 422 `LOCKER_TERM_INVALID`다.
- 연장 구간이 같은 사물함의 다른 활성 배정과 겹치면 409 `LOCKER_PERIOD_CONFLICT`다.
- 운영 중단 사물함은 422 `LOCKER_NOT_AVAILABLE`, 운영 중지 지점은 409 `BRANCH_NOT_ACTIVE`다.
- 금액은 **연장 당시** 요금 기준이다. 과금 행은 `연장`으로 남는다.
- 기간이 지난 배정도 연장할 수 있다. 현재 종료일부터 이어 붙이므로 지난 기간도 과금된다.
- `mockOutcome=FAILED`면 실패 결제와 `실패` 과금만 남기고 종료일을 바꾸지 않는다. 200이며 `charges`의 마지막 항목으로 구분한다.
- 예시: 4주 요금 12,000원, 현재 종료일 2026-11-29, 새 종료일 2026-12-27이면 1구간 12,000원이다.

### 반납

`POST /locker-assignments/{id}/return`. 본문은 `version`, 선택 `reason`이다.

- `ACTIVE` 배정만 반납한다. 아니면 409 `INVALID_STATE`다.
- 실제 반납 시각과 처리자를 남기고 `반납`이 된다. 처리자는 `last_changed_by`, 시각은 `returned_at`이다.
- **잔여 이용료는 환불하지 않는다.** 계약상 `endDate`도 바꾸지 않는다. 사물함은 즉시 다른 고객에게 배정할 수 있다.
- 운영 중지 지점과 운영 중단 사물함에서도 허용한다.
- 시작 전 예정 배정도 같은 방식으로 반납해 닫는다. 환불은 없다.

## 타 지점 최소 요약(M6-D06, POL-01)

`POST /customers/{id}/cross-branch-summary`. 사유를 URL에 남기지 않도록 본문으로 받는다. 본문은 `branchId`(응대 중인 지점), `reason`(1~500자, 필수)이다. 조회가 상태를 바꾸지 않으므로 `Idempotency-Key`는 받지 않지만 CSRF는 필수다.

- **권한**: OWNER, `branchId` 지점의 MANAGER, 또는 `branchId` 지점의 `customers:cross-branch-read`. MANAGER는 역할만 보며 행위 권한을 더 요구하지 않는다. 그 외 직원은 403이다.
- 새 권한은 `permission_definitions`에 관리자 전용이 아닌 값으로 추가한다(#68). 기본 프리셋에는 넣지 않고 관리자가 지정 직원에게 부여한다.
- **응답은 다음만 담는다.**
  - `passes`: 활성·소진·만료·환불 상태의 보유 이용권. 유형·상품명·상태·만료 시각. 잔량과 구매 지점은 담지 않는다.
  - `usages`: 최근 이용 50건. 종류(좌석·스터디룸·사물함)·이용 지점·시작·종료.
- 결제 금액·결제 상세·연락처·이메일·메모·처리자·사유·이용 기록 ID는 담지 않는다. 결제 상세는 대상 지점의 `payments:read`로 `GET /payments/{id}`를 따로 호출해야 한다.
- 이 요약으로 일반 고객 조회 범위가 넓어지지 않는다.

**기록(NFR-SEC-09)**

- 성공: `customer_service_interactions`에 고객·응대 지점·조회자·사유·시각을 남긴다. 이 표는 소속을 참조하므로, 응대 지점에 소속 행이 없는 OWNER는 `audit_events`(`CROSS_BRANCH_SUMMARY`)에 남긴다.
- 거부: `audit_events`(`CROSS_BRANCH_SUMMARY_DENIED`)에 조회자·대상 고객 ID·응대 지점을 남긴다. 거부 응답이 롤백해도 남도록 별도 트랜잭션으로 기록한다. 사유 원문은 남기지 않는다.
- 없는 고객은 404다.

## 고객의 룸·사물함 이력(#72)

`GET /customers/{id}/space-history?scope=`

- 고객 조회 범위(M4-D05) 안의 고객만 조회한다. 정확 일치 조회로만 찾은 고객은 404다.
- scope 지점의 예약·배정을 담는다. `RoomReservation`·`LockerAssignment`와 같은 DTO라서 금액·결제 참조는 해당 지점의 `payments:read` 또는 `payments:write`가 있을 때만 값이 있다.
- 페이지는 없다. 한 고객의 기록 수가 많지 않고 M5 내역과 같은 방식이다.

## 결제 DTO 변경(M4 계약 확장)

`Payment`는 이용권 결제만 전제했으므로 다음을 바꾼다. 구현은 #69·#71이다.

- `purpose` 값에 `스터디룸`, `사물함`을 추가한다.
- `productId`, `productName`은 nullable이 된다. 스터디룸·사물함 결제는 상품이 없다(DB `ck_payments_1`).
- `roomReservationId`, `lockerAssignmentId`를 추가한다. 해당 목적이 아니면 null이다(FR-PAY-12의 결제·배정 참조).
- `POST /payments`로는 이용권 결제만 만든다. 스터디룸·사물함 결제는 예약·배정 명령으로만 생긴다.

## 트랜잭션 경계

| 명령 | 처리 순서 |
| --- | --- |
| 예약 생성 | 멱등 잠금 → 룸 공유 잠금·검증 → 고객 공유 잠금·상태 확인 → 슬롯 충돌 확인 → 결제 행 → 예약 행 → (승인 시) 슬롯 행 → 감사 → 이벤트 → 멱등 결과 |
| 예약 취소 | 멱등 잠금 → 예약 행 잠금·검증 → 결제 행 잠금 → `refunds` 행 → 결제·예약 갱신 → 슬롯 삭제 → 감사 → 이벤트 → 멱등 결과 |
| 이용 시작 | 예약 행 잠금 → 룸 행 잠금 → 다른 이용 중 예약 확인 → `room_usages` 행 → 예약 `이용 중` → 감사 → 이벤트 |
| 사물함 배정 | 멱등 잠금 → 사물함 행 잠금·검증 → 고객 공유 잠금·상태 확인 → 기간 겹침 확인 → 결제 행 → 배정 행 → 과금 행 → 감사 → 이벤트 → 멱등 결과 |
| 사물함 연장 | 멱등 잠금 → 배정 행 잠금 → 사물함 행 잠금 → 기간 겹침 확인 → 결제 행 → 과금 행 → (승인 시) 종료일 갱신 → 감사 → 이벤트 → 멱등 결과 |
| 반납 | 배정 행 잠금 → 상태 갱신 → 감사 → 이벤트 |

중간에 하나라도 실패하면 전체를 롤백한다. 승인 결제만 남거나 슬롯·배정만 남는 부분 성공은 없다.

`room_reservations.request_key`, `payments.idempotency_key`, `refunds.idempotency_key`는 120자다. `Idempotency-Key`는 최대 128자이므로 입실·이동과 같이 요청자·명령·키를 해시한 저장용 값을 쓴다.

## 범위 밖

- 룸 운영 시간 검증, 노쇼 자동 처리, 부분 환불
- 조기 반납 환불, 사물함 이용 중 이동
- 청소·수리 작업, 예약 변경 API(취소 후 재예약으로 처리)
