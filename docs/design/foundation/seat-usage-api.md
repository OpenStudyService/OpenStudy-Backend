# 좌석·입퇴실·이용권 차감 API 계약

Backend [#60](https://github.com/OpenStudyService/OpenStudy-Backend/issues/60)의 M5 계약이다. 구현은 좌석(#61), 입퇴실·차감(#62), 좌석 이동(#63), 수동 조정·내역(#64)에서 이 문서를 기준으로 진행한다. Frontend는 같은 [OpenAPI](openapi.json)(`0.1.6-m5-seat-usage`)로 연동한다.

정책 근거는 [ADR-M5-001](../../db/ADR-M5-001.md)(M5-D01~D08)과 [ADR-M4-002](../../db/ADR-M4-002.md)(M4-D05 고객 조회 범위, 미사용·만료 판정)다. 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을 따르고, 고객·이용권 응답 모양은 [M4 계약](customer-sales-api.md)과 같다.

이 문서의 operation은 모두 설계 계약(`design-target`)이며 아직 서버에 구현되어 있지 않다. 스키마는 바꾸지 않는다. V1의 `seats`, `seat_sessions`, `seat_session_moves`, `pass_balance_events`, `customer_service_interactions`를 그대로 쓴다.

## 제공 범위

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /seats` | 대상 지점의 유효 소속 | 좌석 목록(구역·유형·상태 필터, 구역·좌석 번호 순) | #61 |
| `GET /seats/{id}` | 좌석 지점의 유효 소속 | 좌석 상세와 현재 이용 | #61 |
| `POST /seats` | `branchId` 지점의 `operations:write` | 좌석 등록. `Idempotency-Key` 필수 | #61 |
| `PATCH /seats/{id}` | 좌석 지점의 `operations:write` | 구역·좌석 번호·유형 수정 | #61 |
| `POST /seats/{id}/status` | 좌석 지점의 MANAGER + `operations:write` | 운영 중단·재개와 사유 기록 | #61 |
| `GET /seats/board` | `branchId` 지점의 유효 소속 | 한 지점의 배치도(구역별 전체 좌석)와 현황 | #61 |
| `GET /seats/occupancy` | scope 지점의 유효 소속 | 지점별 상태별 좌석 수·점유율·기준 시점 | #61 |
| `POST /customer-lookups/check-in` | `branchId` 지점의 유효 소속 | 입실용 정확 일치 고객 조회(M5-D05) | #62 |
| `POST /seat-sessions` | 좌석 지점의 유효 소속 | 직원 예외 입실. `Idempotency-Key` 필수 | #62 |
| `POST /seat-sessions/{id}/check-out` | 이용 기록 지점의 유효 소속 | 직원 예외 퇴실과 차감. `Idempotency-Key` 필수 | #62 |
| `POST /seat-sessions/{id}/move` | 이용 기록 지점의 `operations:write` | 이용 중 좌석 이동. `Idempotency-Key` 필수 | #63 |
| `GET /seat-sessions` | scope 지점의 유효 소속 | 입퇴실 이력(이용 지점 기준) | #64 |
| `GET /seat-sessions/{id}` | 이용 기록 지점의 유효 소속 | 이용 기록 상세와 이동 이력 | #62 |
| `POST /passes/{id}/adjustments` | 구매 지점의 `payments:write` | 잔여량·유효기간 수동 조정(M5-D04). `Idempotency-Key` 필수 | #64 |
| `GET /customers/{id}/pass-history` | 고객 조회 범위(M4-D05) | 기간별 구매·사용·조정 내역과 잔량 대조 | #64 |

- OWNER는 모든 권한 조건을 만족한다. MANAGER는 해당 지점의 `role=admin`이면서 명시한 행위 권한도 가져야 한다([API 계약](api.md) "API별 권한"). 운영 중단·재개는 지점 운영 상태 변경과 같은 기준이다.
- 입실·퇴실은 현장 응대 업무라 해당 지점 직원 누구나 할 수 있다. M4 계약의 "고객·이용권 읽기는 유효 소속이면 허용"과 같은 근거다. 좌석 이동은 FR-SEAT-17에 따라 `operations:write`가 필요하다.
- 좌석·이용 기록의 삭제 API는 없다. 좌석은 운영 중단으로 닫고, 이용 기록은 이력으로 보존한다.

## 지점 범위

- **좌석**: 좌석 지점이 요청 scope 안에 있어야 보인다. 범위 밖 좌석은 목록·total에 없고 상세는 404다.
- **이용 기록(M5-D06)**: 이용 지점(`seat_sessions.branch_id`)이 scope 안에 있으면 보인다. 고객 조회 범위(M4-D05)와는 무관하다. 다른 지점에서 등록한 고객이라도 이 지점에서 입실했다면 이 지점 직원이 퇴실·이동을 처리한다.
- **이용 기록의 고객 정보**는 `CustomerSummary`(ID·회원번호·이름)만 담는다. 연락처·이메일·메모·상태 사유는 담지 않는다.
- **이용권 사용 지점(M5-D01)**: 이용권은 구매 지점과 관계없이 모든 활성 지점에서 입실에 쓸 수 있다. 상품의 판매 지점은 판매 범위로만 쓴다.
- **이용권 조회**는 M4 규칙(구매 지점 기준)을 그대로 따른다. 다른 지점에서 산 이용권은 `GET /passes`에 나오지 않는다. 입실에 필요한 정보는 정확 일치 조회 응답의 `usablePasses`로만 준다.
- **운영 중지 지점(FR-BR-08)**: 입실과 좌석 이동은 409 `BRANCH_NOT_ACTIVE`다. 이동도 새 좌석 배정으로 보기 때문이다. 퇴실과 조회는 허용한다. 좌석 등록·수정·운영 상태 변경은 운영 설정이므로 허용한다.

## 좌석

- 필드는 지점·구역(`zone`, 1~80자)·좌석 번호(`seatNo`, 1~30자)·유형(`일반`·`모니터`·`기타`)·운영 상태(`사용 가능`·`운영 중단`)다. 구역과 좌석 번호는 trim하고, 공백만 남으면 422다.
- 같은 지점의 좌석 번호는 중복될 수 없다(409 `DUPLICATE_SEAT_NO`, DB `uk_seats_1`). 다른 지점과는 같아도 된다.
- **표시 상태**: 응답 `status`는 `사용 가능`·`사용 중`·`운영 중단` 셋 중 하나다. `사용 중`은 저장 상태가 아니라 활성 이용 기록이 있는지로 계산한다(`seats` 표 주석 "OCCUPIED는 활성 seat_session으로 계산"). 운영 중단 좌석에는 입실할 수 없고, 사용 중 좌석은 운영 중단할 수 없으므로 세 상태는 겹치지 않는다.
- 사용 중 좌석의 응답에는 `activeSession`(이용 기록 ID·고객 최소 정보·입실 시각)이 담긴다. 퇴실·이동 버튼에 쓴다.
- 목록 정렬은 `zone` → `seatNo` → `id` 오름차순으로 고정한다. 문자열 비교이며 `A-10`이 `A-2`보다 앞에 올 수 있다. 자연 정렬이 필요하면 번호 체계를 맞추거나 후속에서 정렬 열을 추가한다.
- 등록 시 운영 상태를 `운영 중단`으로 줄 수 있다. 활성 이용이 있을 수 없으므로 MANAGER 권한을 요구하지 않는다.
- 수정(`PATCH`)은 구역·좌석 번호·유형만 바꾼다. `status`를 보내면 422 `IMMUTABLE_FIELD`다. 사용 중 좌석도 번호·구역을 바꿀 수 있으며 이용 기록은 좌석 ID로 연결되어 영향이 없다.
- 운영 중단·재개(`POST /seats/{id}/status`)는 `version`·`status`·`reason`(1~500자)을 받는다. 활성 이용이 있는 좌석의 운영 중단은 409 `SEAT_OCCUPIED`다. 같은 상태 요청은 현재 값을 돌려주며 감사를 만들지 않는다.
- 좌석에는 상태 이력 표가 없으므로 등록·수정·운영 상태 변경은 `audit_events`에 전후 값·작업자·시각·사유를 남긴다(FR-SEAT-13). 점유·해제 이력은 이용 기록과 이동 이력이 원천이다.
- 수정·상태 변경은 좌석 version으로 충돌을 막는다(409 `VERSION_CONFLICT`, `reloadUrl=/api/seats/{id}`).

### 배치도와 현황

- `GET /seats/board?branchId=&kind=`는 한 지점의 좌석 **전체**를 구역별로 묶어 돌려준다(페이지 없음). 배치도는 한 화면에 모든 좌석이 있어야 하기 때문이다. `kind` 필터를 주면 해당 유형 좌석만 담고, 현황 수치도 같은 필터로 계산한다(FR-SEAT-15).
- V1 `seats`에는 좌표 열이 없다. 배치도는 구역 묶음과 좌석 번호 순서로 그리고, 자유 배치 좌표는 이번 범위에서 제외한다(스키마 변경 없음).
- `GET /seats/occupancy?scope=`는 scope 안의 지점마다 다음을 준다.
  - `total`, `inUse`, `available`, `blocked`, `occupancyRate`, `asOf`
  - 한 지점의 수치는 한 번의 조회(같은 기준 시점)로 계산하며 `inUse + available + blocked = total`이다.
- **점유율** = `inUse ÷ total × 100`, 소수 첫째 자리 반올림(HALF_UP)한 숫자다. 예: 4/10 → `40.0`, 1/3 → `33.3`, 2/3 → `66.7`. 운영 중단 좌석도 분모에 포함한다(FR-SEAT-11 "전체 좌석 수를 분모로").
- 좌석이 없는 지점은 수치를 모두 `0`으로, `occupancyRate`는 `null`로 준다. 화면은 null을 "해당 없음"으로 표시하고 0%와 구분한다.
- 배치도(`board`)·현황(`occupancy`)·목록은 같은 계산을 쓴다. M9 대시보드도 이 기준을 재사용한다.

## 입실용 정확 일치 조회(M5-D05·D07·D08)

`POST /customer-lookups/check-in`. 연락처를 URL에 남기지 않도록 조회 조건을 본문으로 받는다. 상태를 바꾸지 않으므로 `Idempotency-Key`는 받지 않지만, CSRF는 필수다.

요청은 `branchId`와, `memberNo` 또는 `phone` 중 **정확히 하나**를 받는다. 둘 다 있거나 둘 다 없으면 422다.

- `memberNo`는 저장된 회원번호와 완전히 같아야 한다.
- `phone`은 하이픈·공백을 제거한 숫자 9~11자리로 검증하고, 저장된 숫자 연락처와 완전히 같아야 한다.
- 부분 일치·이름 검색·와일드카드는 없다. 일반 고객 검색(`GET /customers?q=`)과 별도 진입점이다.
- 요청자는 `branchId` 지점의 유효 소속이어야 한다(OWNER는 모든 지점). 이 지점이 조회 지점으로 기록된다.

응답은 `{ items: CheckInCandidate[] }`이며 페이지가 없다. 연락처 중복을 막지 않으므로 여러 명이 나올 수 있고, 최대 10명까지 회원번호 순으로 준다.

- 각 항목은 `customer`(ID·회원번호·이름), `status`(정상·이용 제한·비활성), `activeSession`(다른 곳에 입실 중이면 이용 기록 ID·이용 지점 ID·입실 시각, 아니면 null), `usablePasses`를 담는다.
- `usablePasses`는 지금 입실에 쓸 수 있는 이용권만 담는다. 실효 상태가 활성이고, 시간권·정액권이면 잔량이 0보다 커야 한다. 각 항목은 ID·유형·상품명·남은 분·남은 금액·만료 시각·구매 지점 ID다.
- 연락처·이메일·메모·결제 정보·다른 이용권은 담지 않는다. 이용 제한·비활성 고객도 상태 확인을 위해 결과에 나오지만 입실은 409로 거절된다.

이 조회로 고객의 일반 조회 범위가 넓어지지 않는다. 결과로 찾은 다른 지점 고객은 `GET /customers`·`GET /customers/{id}`·`GET /customers/{id}/pass-history`에서 계속 보이지 않는다.

**조회 기록(M5-D07)**

- 일치한 고객마다 `customer_service_interactions`에 고객·조회 지점·조회 직원·시각을 남긴다. `reason`은 서버가 정한 문자열(`입실 정확 일치 조회(회원번호)` 또는 `(연락처)`)이다.
- 이 표는 (`branch_id`, `employee_id`)가 `branch_memberships`를 참조한다. 그래서 조회 지점에 소속 행이 없는 OWNER는 이 표에 남길 수 없다. 이 경우 일치 고객 ID를 담아 `audit_events`(`CHECK_IN_LOOKUP`)에 남긴다.
- 일치하는 고객이 없는 조회는 `audit_events`(`CHECK_IN_LOOKUP_NO_MATCH`)에 조회 방식(회원번호·연락처)과 함께 남긴다. 존재하지 않는 사람의 연락처를 저장하지 않도록 조회 값 원문은 남기지 않는다.
- 조회 횟수 제한은 M5에 없다(M5-D08). 서버는 이 조회를 한 서비스 진입점으로만 처리해 후속 제한을 그 지점에 붙일 수 있게 한다.

## 입실(직원 예외 배정)

`POST /seat-sessions`. 요청 본문은 `seatId`, `customerId`, `passId`, 선택 `reason`(0~500자)이다. 고객 QR 입실은 범위 밖이므로 M5의 모든 입실은 직원 예외 경로다(FR-SEAT-06). 처리 직원(`exception_actor_id`)과 사유(`exception_reason`)를 이용 기록에 남긴다.

`customerId`와 `passId`는 정확 일치 조회 결과나 고객·이용권 조회로 얻는다. 고객이 요청자의 고객 조회 범위 안에 있을 필요는 없다(M5-D05). 대신 이용권의 소유 고객이 `customerId`와 같아야 하며, 다르면 404다.

서버 검증 순서와 결과(모두 잠금 뒤 재확인):

| 조건 | 결과 |
| --- | --- |
| 좌석이 없거나 요청자가 좌석 지점의 유효 소속이 아님 | 404 `NOT_FOUND` |
| 좌석 지점 운영 중지 | 409 `BRANCH_NOT_ACTIVE` |
| 좌석 운영 중단 | 409 `SEAT_BLOCKED` |
| 좌석에 활성 이용 있음 | 409 `SEAT_OCCUPIED` |
| 이용권이 없거나 `customerId`의 이용권이 아님 | 404 `NOT_FOUND` |
| 고객이 정상이 아님(이용 제한·비활성) | 409 `CUSTOMER_NOT_ACTIVE` |
| 고객이 이미 어느 지점에서든 입실 중 | 409 `CUSTOMER_ALREADY_CHECKED_IN` |
| 이용권 환불 | 409 `PASS_REFUNDED` |
| `expiresAt`이 있고 처리 시각 ≥ `expiresAt` | 409 `PASS_EXPIRED` (저장 상태가 ACTIVE여도 시각으로 판정) |
| 저장 상태 `EXHAUSTED`이거나, 시간권·정액권의 잔량이 0 | 409 `PASS_EXHAUSTED` |
| 성공 | 201, 활성 이용 기록 |

- 유효기간이 없는 시간권·정액권은 만료를 이유로 거절하지 않는다(FR-PASS-10).
- 기간권은 잔량을 보지 않고 만료 시각만 본다.
- 같은 좌석·같은 고객의 동시 입실은 하나만 성공한다. 서비스가 잠금 뒤 다시 확인하고, DB의 `uk_seat_sessions_1`(활성 좌석 유일)·`uk_seat_sessions_2`(활성 고객 유일)가 마지막 방어선이다. 고객당 활성 이용이 하나이므로 이용권당 활성 이용도 하나다.
- 입실 시각은 서버 시각이다. `checked_in_at`과 `effective_checked_in_at`에 같은 값을 넣는다. 이용 시간 정정(`seat_session_corrections`)은 범위 밖이다.

## 퇴실과 차감(M5-D02·D03, FR-SEAT-14)

`POST /seat-sessions/{id}/check-out`. 요청 본문은 선택 `reason`(0~500자)뿐이며 **분·금액 필드는 없다.** 차감량은 서버가 계산한다. 퇴실 처리자와 사유는 `audit_events`에 남긴다. 이용 기록의 `exception_*` 열은 입실 처리자를 담는다.

| 조건(잠금 후 재확인) | 결과 |
| --- | --- |
| 이용 기록이 없거나 요청자가 이용 지점의 유효 소속이 아님 | 404 `NOT_FOUND` |
| 이미 종료됨(다른 키) | 409 `SESSION_ALREADY_CLOSED` |
| 성공 | 200, 종료된 이용 기록 |

고객 상태, 지점 운영 상태, 이용권 만료는 퇴실을 막지 않는다. 진행 중 이용의 종료·정산은 항상 허용한다(FR-BR-08, M5-D03).

### 계산 규칙

기호는 다음과 같다.

- 입실 시각 `in`, 퇴실 시각 `out`: 서버 시각, `effective_*` 열
- 이용권 만료 시각 `E`: 없으면 만료 없음
- 시간권 남은 분 `Rm`, 정액권 남은 금액 `Rk`, 구매 당시 시간당 요율 `rate`

1. **이용 분** `U = ceil((out − in) ÷ 1분)`. 초 이하도 올림한다. 0초면 0분, 1초~60초면 1분, 61초면 2분이다.
2. **만료 전·후 분리**: `E`가 있고 `out > E`이면 다음과 같이 나눈다. 아니면 `C = U`, `A = 0`이다.
   - 만료 전 분 `C = min(U, ceil((E − in) ÷ 1분))`
   - 만료 후 분 `A = U − C`
   - 걸친 1분 미만은 만료 전으로 센다. 입실 때 유효했던 이용권이므로 고객에게 불리하지 않은 쪽을 택했다.
3. **유형별 차감과 초과량**

   | 유형 | 차감 | `overage_minutes` | `overage_krw` |
   | --- | --- | --- | --- |
   | 시간권 | `D = min(C, Rm)`분 | `A + (C − D)` | 0 |
   | 정액권 | `K = ceil(C × rate ÷ 60)`원, 차감 `min(K, Rk)`원 | `A` | `K − min(K, Rk)` |
   | 기간권 | 없음 | `A` | 0 |

   - 시간권·정액권이 이용 중 만료되면 만료 후 분은 차감하지 않고 초과량으로 남긴다. 기간권 규칙(M5-D03)과 같으며, 2026-10-29 사용자 결정이다.
   - 잔량은 음수가 되지 않는다. 부족분은 초과량으로 남긴다(FR-PASS-09).
4. 차감 뒤 시간권·정액권의 잔량이 0이면 이용권 저장 상태를 `EXHAUSTED`로 바꾼다.
5. 저장 열은 다음과 같다.
   - `billed_minutes = U`, `billed_krw` = 정액권 실제 차감액(그 밖에는 0), `overage_minutes`, `overage_krw`.
   - 응답은 `usedMinutes`(U), `deductedMinutes`(시간권 D, 그 밖에는 0), `deductedKrw`, `overageMinutes`, `overageKrw`와, 퇴실 후 이용권 잔량(`passRemainingMinutes`·`passRemainingCreditKrw`)을 준다.

### 예시

모든 예는 입실 `10:00:00`이다.

| # | 이용권 | 퇴실 | 계산 | 결과 |
| --- | --- | --- | --- | --- |
| 1 | 시간권 남은 120분, 만료 없음 | 11:30:20 | U = ceil(90분 20초) = 91 | 91분 차감, 남은 29분, 초과 0 |
| 2 | 시간권 남은 60분 | 11:30:20 | U = 91, D = 60 | 60분 차감, 남은 0분(소진), 초과 31분 |
| 3 | 정액권 요율 1,500원, 남은 10,000원 | 11:30:20 | K = ceil(91 × 1500 ÷ 60) = ceil(2275) | 2,275원 차감, 남은 7,725원 |
| 4 | 정액권 요율 1,000원, 남은 10,000원 | 11:30:20 | K = ceil(91000 ÷ 60) = ceil(1516.67) | 1,517원 차감, 남은 8,483원 |
| 5 | 정액권 요율 1,000원, 남은 500원 | 11:30:20 | K = 1,517 | 500원 차감, 남은 0원(소진), 초과 1,017원 |
| 6 | 기간권, 만료 10:30:30 | 11:00:00 | U = 60, C = ceil(30분 30초) = 31, A = 29 | 차감 없음, 초과 29분 |
| 7 | 시간권 남은 120분, 만료 10:30:30 | 11:00:00 | U = 60, C = 31, A = 29 | 31분 차감, 남은 89분, 초과 29분 |
| 8 | 시간권 남은 120분 | 10:00:00(즉시) | U = 0 | 차감 없음, 원장 행 없음 |

### 원장과 반복 퇴실

- 시간권·정액권에서 실제 차감액이 0보다 크면 `pass_balance_events`에 `USE` 행 하나를 남긴다. 기간권과 차감 0인 퇴실은 원장 행을 만들지 않는다. 기간권의 사용 여부는 `seat_sessions` 존재로 판정한다(ADR-M4-002).
  - `delta_minutes`(시간권) 또는 `delta_krw`(정액권)는 음수다.
  - `seat_session_id`는 이용 기록, `actor_employee_id`는 퇴실 처리자다.
- `USE` 행의 `event_key`는 `USE-{이용 기록 ID}`로 고정한다. 이용 기록 하나에 차감 원장은 최대 하나이고, 전역 UNIQUE(`uk_pass_balance_events_1`)가 이중 차감의 마지막 방어선이 된다.
- 같은 키로 다시 퇴실하면 최초 결과를 그대로 돌려준다. 다른 키로 이미 종료된 기록을 퇴실하면 409 `SESSION_ALREADY_CLOSED`이고 원장·잔량은 바뀌지 않는다(FR-SEAT-07).
- 이 기록이 생기면 해당 이용권은 "사용 이력 있음"이 되어 M4 환불이 거절된다(`PASS_ALREADY_USED`). 입실만 하고 퇴실 전이어도 `seat_sessions` 행이 있으므로 마찬가지다.

## 좌석 이동(FR-SEAT-17)

`POST /seat-sessions/{id}/move`. 요청 본문은 `toSeatId`, 선택 `reason`(0~500자)이다.

| 조건(잠금 후 재확인) | 결과 |
| --- | --- |
| 이용 기록이 없거나 이용 지점의 유효 소속이 아님 | 404 `NOT_FOUND` |
| 이용 지점의 `operations:write` 없음 | 403 `FORBIDDEN` |
| 이미 종료됨 | 409 `SESSION_ALREADY_CLOSED` |
| 이용 지점 운영 중지 | 409 `BRANCH_NOT_ACTIVE` |
| 목적 좌석이 현재 좌석과 같음, 없음, 또는 다른 지점 좌석 | 422 `VALIDATION_FAILED`(`toSeatId`) |
| 목적 좌석 운영 중단 | 409 `SEAT_BLOCKED` |
| 목적 좌석 사용 중 | 409 `SEAT_OCCUPIED` |
| 성공 | 200, 같은 이용 기록(좌석만 바뀜) |

- 다른 지점 좌석을 404가 아닌 422로 답하는 이유: 목적 좌석은 같은 지점 좌석 중에서 고르는 입력값이다. 다른 지점 좌석이 있는지는 알려 주지 않는다.
- 이용 기록 ID·이용권·입실 시각은 그대로다. 퇴실 시 이용 분은 최초 입실 시각부터 계산한다.
- `seat_sessions.seat_id`만 바꾸고 `seat_session_moves`에 원래 좌석·목적 좌석·처리자·사유·시각을 남긴다. 원래 좌석 해제와 목적 좌석 점유는 같은 행의 갱신이라 한 트랜잭션에서 함께 반영된다.
- 같은 목적 좌석으로 향하는 동시 이동이나 입실은 하나만 성공한다. 목적 좌석 행 잠금과 `uk_seat_sessions_1`이 막는다.
- 지점 간 이동은 없다(`seat_session_moves` 표 주석 "같은 지점 내").

## 입퇴실 이력(FR-SEAT-18)

- `GET /seat-sessions?scope=&status=&from=&to=&seatId=&customerId=`
  - `status`는 `이용 중`·`종료`다.
  - `from`·`to`는 입실 시각 기준 `[from, to)`이고 ISO 8601 UTC다.
  - 정렬은 `checkedInAt`(기본 desc) 하나이며, 같으면 id 순이다.
- 이용 지점이 scope 안인 기록만 보인다. `customerId` 필터는 이용 기록 안에서만 거르며 고객 조회 범위를 검사하지 않는다(M5-D06).
- 각 항목: 이용 기록 ID, 고객 최소 정보, 이용 지점 ID, 좌석(ID·구역·번호), 이용권(ID·유형·상품명·**구매 지점 ID**), 입실·퇴실 시각, 이용·차감·초과량, 상태, 입실 처리자.
  - 구매 지점과 이용 지점을 따로 담아 구분한다.
- 상세(`GET /seat-sessions/{id}`)는 위 정보에 이동 이력(원래 좌석·목적 좌석·처리자·사유·시각)을 더한다.

## 이용권 수동 조정(M5-D04, FR-PASS-12)

`POST /passes/{id}/adjustments`. 요청 본문은 다음과 같다. **조정 후 값**을 받는다(증감량이 아님).

- `version`
- `reason`(1~500자)
- 다음 중 하나 이상
  - `remainingMinutes`: 시간권만, 0 이상 정수
  - `remainingCreditKrw`: 정액권만, 0 이상 정수
  - `expiresAt`: ISO 8601 UTC. 시간권·정액권은 `null`로 "만료 없음"을 명시할 수 있다.

| 조건 | 결과 |
| --- | --- |
| 이용권이 없거나 구매 지점이 요청자의 조회 범위 밖 | 404 `NOT_FOUND` |
| 구매 지점의 `payments:write` 없음 | 403 `FORBIDDEN` |
| 유형에 맞지 않는 필드, 음수, 조정 필드 없음, 기간권 `expiresAt=null`, `expiresAt ≤ startedAt` | 422 `VALIDATION_FAILED` |
| 환불된 이용권 | 409 `PASS_REFUNDED` (재활성화 불가) |
| version 불일치 | 409 `VERSION_CONFLICT`, `reloadUrl=/api/passes/{id}` |
| 성공 | 200, 조정된 `Pass` |

- 조정 뒤 저장 상태는 다음과 같다.
  - 시간권·정액권 잔량을 0보다 크게 만들면 `EXHAUSTED`가 `ACTIVE`로 돌아간다.
  - 잔량을 0으로 만들면 `EXHAUSTED`가 된다.
  - 만료 시각을 미래로 늘리면 응답 실효 상태가 `활성`이 된다. 만료는 시각으로 판정하므로 별도 전이가 없다.
  - 만료 시각을 과거로 정하는 정정도 허용한다.
- 조정 한 번에 `pass_balance_events` `ADJUST` 행 하나를 남긴다.
  - `delta_minutes`·`delta_krw` = 조정 후 − 조정 전이다. 유효기간만 바꾸면 둘 다 0이다.
  - `reason`, `actor_employee_id`를 함께 남긴다.
  - `event_key`는 서버가 만든 값이다.
- 조정 전후 값(잔량·만료 시각·저장 상태)은 `audit_events`에 남긴다. 원장 표에는 만료 시각 열이 없기 때문이다.
- 모든 값이 현재와 같으면 현재 이용권을 돌려주고 원장·감사를 만들지 않는다.
- 입실 중인 이용권도 조정할 수 있다. 퇴실 계산은 퇴실 시점의 잔량·만료 시각을 쓴다.
- `ADJUST` 행은 "사용 이력"이 아니다. 환불 판정은 `seat_sessions`와 `USE` 행만 본다(ADR-M4-002).

## 고객의 구매·사용 내역(FR-MEM-07)

`GET /customers/{id}/pass-history?scope=&from=&to=`

- 고객이 요청자의 고객 조회 범위(M4-D05) 안이어야 한다. 아니면 404다.
  - 정확 일치 조회로만 찾은 다른 지점 고객은 404다.
  - `from`·`to`는 선택이며 `[from, to)` UTC다.
- 대상 이용권은 구매 지점이 scope 안인 이 고객의 이용권이다. M4 이용권 조회 범위와 같다.
- `events`: 기간 안의 사건을 시각 오름차순으로 담는다.
  - `구매`(결제 승인·이용권 발급), `환불`, `이용`(퇴실 차감, 기간권은 차감 없이 이용 분), `조정`
  - 각 사건의 이용권 ID, 상품명, 증감 분·금액, 이용 지점 ID, 사유, 처리자
  - 결제 금액은 담지 않는다(NFR-SEC-06). 결제는 `paymentId`로 결제 API에서 조회한다.
- 이용 사건의 이용 지점이 요청자 scope 밖(M5-D01로 다른 지점에서 사용)이면 `branchId`를 null로 주고 증감량만 담는다. 원장 합계를 맞추되 다른 지점의 이용 정보는 드러내지 않는다. 다른 지점 이력 요약은 M6(POL-01)이다.
- `passes`: 대상 이용권마다 다음을 준다. 기간과 무관한 전체 원장 기준이다.
  - 발급량(구매 당시 분·금액), 원장 합계(전체 `USE`·`ADJUST` 증감의 합), 현재 잔량
  - `발급량 + 원장 합계 = 현재 잔량`이 항상 성립해야 한다(FR-PASS-09 "기록된 변경량으로 잔여량 확인").

## 멱등 키

- `Idempotency-Key`(16~128자)가 필수인 operation은 다음과 같다. 누락은 422다.
  - `POST /seats`, `POST /seat-sessions`, `POST /seat-sessions/{id}/check-out`, `POST /seat-sessions/{id}/move`, `POST /passes/{id}/adjustments`
- 공통 `idempotency_records`를 쓴다. 범위는 (요청자, operation, scope key, 키)다. scope key는 operation마다 다르다.

  | operation | scope key |
  | --- | --- |
  | 좌석 등록 | 지점 ID |
  | 입실 | 좌석 ID |
  | 퇴실·이동 | 이용 기록 ID |
  | 조정 | 이용권 ID |
- 요청 해시는 허용 필드만 정규화한 본문과 path ID로 계산한다.
  - 같은 키·같은 입력이면 최초 처리의 HTTP 상태와 본문을 돌려주고 새 이용 기록·원장·이동·감사를 만들지 않는다.
  - **같은 키·다른 입력은 409 `IDEMPOTENCY_CONFLICT`다.**
  - 처리 중이면 409 `REQUEST_IN_PROGRESS`와 `Retry-After`를 줄 수 있다.
- 권한·CSRF·입력 형식 검증은 멱등 조회보다 먼저 한다. 4xx 거절은 멱등 결과로 저장하지 않는다.
- DB의 요청 키 열은 전역 UNIQUE·120자다. 클라이언트 키 원문 대신 접두어 + SHA-256(요청자·operation·scope key·키)를 저장한다(M4와 같은 방식).
  - `seat_sessions.start_request_key`(`SIN-`), `end_request_key`(`SOUT-`)
  - `seat_session_moves.request_key`(`SMV-`)
  - `pass_balance_events.event_key`의 `ADJUST`(`ADJ-`)
  - 퇴실 `USE` 행만 위의 `USE-{이용 기록 ID}`를 쓴다.
- 운영 상태 변경(`POST /seats/{id}/status`)과 수정(`PATCH`)은 version으로 충돌을 막고 멱등 키를 받지 않는다. 고객 수정·상태 변경과 같다.

## 트랜잭션 경계

모든 쓰기는 기존 `AuthorizedCommands` 잠금 순서(가드 → 직원 → 지점 → 업무 행)를 따르고, 권한 회수는 다음 요청부터 반영한다.

| 명령 | 한 트랜잭션에 묶는 작업 |
| --- | --- |
| 좌석 등록 | 멱등 잠금 → 좌석 행 → 감사 → `seats` 이벤트 → 멱등 결과 |
| 좌석 수정·운영 상태 | 좌석 행 잠금·version 확인 → (중단 시) 활성 이용 확인 → 변경 → 감사 → 이벤트 |
| 입실 | 멱등 잠금 → 좌석 행 잠금 → 고객 공유 잠금·상태 확인 → 이용권 행 잠금·사용 가능 판정 → 활성 좌석·고객 재확인 → 이용 기록 → 감사 → `seats`·`seat-sessions` 이벤트 → 멱등 결과 |
| 퇴실 | 멱등 잠금 → 이용 기록 잠금 → 이용권 행 잠금 → 계산 → 이용 기록 종료 → (차감 > 0) `USE` 원장 → 잔량·`EXHAUSTED` → 감사 → `seats`·`seat-sessions`·`passes` 이벤트 → 멱등 결과 |
| 좌석 이동 | 멱등 잠금 → 이용 기록 잠금 → 목적 좌석 잠금·확인 → `seat_id` 변경 → `seat_session_moves` → 감사 → 이벤트 → 멱등 결과 |
| 수동 조정 | 멱등 잠금 → 이용권 행 잠금·version 확인 → `ADJUST` 원장 → 잔량·만료·상태 갱신 → 감사 → `passes` 이벤트 → 멱등 결과 |

- 중간에 하나라도 실패하면 전체를 롤백한다. 점유만 남거나 원장만 남는 부분 성공은 없다.
- DB 제약 `uk_seat_sessions_1`·`_2`·`_3`·`_4`, `ck_seat_sessions_1`·`_2`, `uk_pass_balance_events_1`, `ck_seat_session_moves_1`이 같은 불변식을 한 번 더 막는다.
- 지점별 무효화 이벤트(`WorkspaceEvent.resource`)에 `seats`, `seat-sessions`를 추가한다.
  - `seats`·`seat-sessions` 이벤트의 branchId는 좌석·이용 지점이다.
  - `passes` 이벤트의 branchId는 M4와 같이 구매 지점이다. 다른 지점에서 이용해 잔량이 바뀌면 구매 지점 화면이 갱신된다.
  - 이벤트는 entityId·branchId만 담고 개인정보·금액을 담지 않는다.

## 오류 코드

공통 코드([api.md](api.md))와 M4 코드([customer-sales-api.md](customer-sales-api.md))에 더해 이 범위에서 쓰는 코드다.

| HTTP | code | 쓰는 곳 |
| --- | --- | --- |
| 404 | `NOT_FOUND` | 범위 밖·없는 좌석·이용 기록·이용권, 고객과 이용권 불일치, 범위 밖 고객 내역 |
| 409 | `DUPLICATE_SEAT_NO` | 같은 지점 좌석 번호 중복 |
| 409 | `SEAT_OCCUPIED` | 사용 중 좌석에 입실·이동, 사용 중 좌석 운영 중단 |
| 409 | `SEAT_BLOCKED` | 운영 중단 좌석에 입실·이동 |
| 409 | `CUSTOMER_ALREADY_CHECKED_IN` | 이미 입실 중인 고객의 입실 |
| 409 | `CUSTOMER_NOT_ACTIVE` | 정상이 아닌 고객의 입실(M4와 같은 코드) |
| 409 | `PASS_REFUNDED`, `PASS_EXPIRED`, `PASS_EXHAUSTED` | 사용할 수 없는 이용권의 입실, 환불 이용권 조정 |
| 409 | `SESSION_ALREADY_CLOSED` | 종료된 이용의 퇴실(다른 키)·이동 |
| 409 | `BRANCH_NOT_ACTIVE` | 운영 중지 지점의 입실·이동 |
| 409 | `VERSION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `REQUEST_IN_PROGRESS` | 공통 |
| 422 | `VALIDATION_FAILED`, `IMMUTABLE_FIELD` | 형식·범위·허용 외 필드, 좌석 수정의 `status` |

각 operation의 사용 코드는 OpenAPI의 `x-error-codes`에 적었다.

## 이번 범위에서 제외

- 서비스·컨트롤러 구현(#61~#64)과 Frontend 연동.
- 고객 QR 정상 입퇴실(FR-SEAT-06 제외 항목), 이용 시간 정정(`seat_session_corrections`), 정확 일치 조회 횟수 제한(M5-D08, 후속).
- 좌석 자유 배치 좌표(스키마 변경 필요), 좌석 점유 추이 분석(FR-SEAT-16, M13), 지점 간 좌석 이동.
- 다른 지점 고객의 이력 요약(POL-01, M6), 스터디룸·사물함 이용(M6).
