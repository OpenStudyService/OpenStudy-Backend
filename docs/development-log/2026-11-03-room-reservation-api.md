# 2026-11-03 스터디룸 원장과 예약·선결제·일정 조회 API

관련: Issue [#69](https://github.com/OpenStudyService/OpenStudy-Backend/issues/69), [계약 문서](../design/foundation/space-operations-api.md), [ADR-M6-001](../db/ADR-M6-001.md), [M4 계약](../design/foundation/customer-sales-api.md), [입퇴실 기록](2026-11-02-seat-check-in-out.md).

## 1. 배경 / 문제

M6의 첫 구현이다. 룸을 지점별로 관리하고, 서버가 계산한 요금의 Mock 결제 승인과 예약 확정을 한 정합성 단위로 처리해야 한다. 돈과 시간 점유가 함께 움직이므로 승인 결제만 남거나 슬롯만 남는 부분 성공, 같은 시간의 이중 예약, 재시도로 인한 이중 결제가 없어야 한다.

이번 범위의 operation은 룸 5개와 예약 생성·목록·달력·상세 4개다. 취소·이용 시작·완료(#70), 사물함(#71), 타 지점 요약(#72)은 구현하지 않았다. 스키마는 #68의 V4를 그대로 쓰며 마이그레이션은 없다.

결제 쪽은 이용권만 전제한 곳이 세 군데 있어 함께 고쳐야 했다.

- `PaymentSearchRepository`가 `purpose = 'PASS'`로 고정되어 스터디룸 결제가 목록에서 빠진다.
- `PaymentService.views`의 `Long.toString(productId)`가 상품 없는 결제에서 NPE를 낸다.
- `refund`가 이용권이 없는 승인 결제에서 `PassService.refund`의 `IllegalStateException`으로 500이 된다.

## 2. 검토한 선택지

- **결제 응답의 `roomReservationId`를 읽는 경로**: 예약 생성이 결제를 호출하므로 `reservation → payment` 의존이 생기고, `ModuleBoundaryTests`가 순환을 금지해 `payment`가 `reservation` service를 가져올 수 없다.
  - 결제 모듈이 조회 인터페이스를 선언하고 예약 모듈이 구현한다: 순환은 없지만 조회 한 건에 구조가 늘어난다.
  - `PaymentRepository`의 네이티브 조회로 `room_reservations`를 읽는다: 고객 검색이 `payments`를 네이티브로 읽는 선례가 있다. 이쪽을 택했다.
- **시간 충돌 판정 대상**
  - 슬롯 표를 본다: 단순하지만 계약의 "활성 예약" 정의와 다른 표를 보게 된다.
  - 예약 행의 `확정`·`이용 중` 겹침을 본다: 계약 정의 그대로다. 이쪽을 택했다. 슬롯 유일 제약은 마지막 방어선으로 남는다.
- **예약 권한의 `BranchAction`**: `NEW_PAYMENT`는 운영 중지 지점을 403으로 막아 계약 표의 409 `BRANCH_NOT_ACTIVE`를 낼 수 없다. `WRITE_PAYMENT_HISTORY`로 들어와 표의 순서대로 판정한다.
- **룸 운영 중단의 권한**: 좌석의 `MANAGE_SEATS`는 MANAGER를 요구하지만 M6-D05는 `operations:write`만 요구한다. `WRITE_ROOMS`를 새로 두었다.

## 3. 결정 및 이유

- **잠금 순서는 계약의 트랜잭션 표를 따른다.** 공통 guard → 멱등 → 룸 공유 잠금·검증 → 고객 공유 잠금 → 겹침 확인 → 결제 → 예약 → 슬롯 → 감사 → 이벤트 → 멱등 결과.
- **금액은 잠근 룸 행에서만 나온다.** `@Lock(PESSIMISTIC_READ)` 조회는 잠금 읽기라 최신 커밋 값을 받는다. 같은 트랜잭션에서 룸 entity를 먼저 읽지 않아 이전 값이 남을 일이 없다.
- **충돌은 결제보다 먼저 판정한다.** 판정을 지나쳐도 슬롯 유일 제약 위반이 예외가 되어 트랜잭션 전체가 롤백되므로 결제·예약·감사·이벤트·멱등 기록이 남지 않는다.
- **정시 판정은 초와 나노초를 그대로 본다.** `Instant`는 DATETIME(6)보다 정밀하므로 마이크로초 미만 값을 잘라 통과시키지 않고 거절한다.
- **`billing`은 행마다 그 행의 지점 권한으로 판정한다.** 결제 상태 조회도 볼 수 있는 행의 결제만 대상으로 해서, 값이 없으면 곧 null이 되게 했다.
- **룸·예약의 404는 `inRecordBranch`로 guard 아래에서 판정한다.** 트랜잭션 밖에서 미리 읽으면 그 사이 소속이 회수될 때 404가 403이 된다.
- **목록·달력·상세는 `views` 하나로 만든다.** 응답 모양과 상태값이 어긋날 수 없게 하기 위해서다.
- **`LocalPreviewController`에서 `rooms` 경로를 뺐다.** `/reservations`는 실제 경로 `/room-reservations`와 겹치지 않아 그대로 두었다. `LOOKUPS`의 `rooms`는 샘플 선택 데이터라 유지했다. `LocalPreviewIntegrationTests`의 샘플 예는 `shifts`로 바꿨다.

계약이 정하지 않아 판단한 점:

- **금액 상한**: 요금 상한이 2^53 − 1이라 `요금 × 시간`이 API 정수 상한을 넘을 수 있다. 스키마가 `amountKrw` 최댓값을 정하고 있으므로 결제 전에 422 `VALIDATION_FAILED`(`endAt`)로 거절한다. 판정 위치는 계약 표의 모든 조건 뒤다.
- **달력의 지점 권한**: 좌석 배치도는 읽을 수 없는 지점을 404로 답하지만, 공통 규칙 "scope에 없는 지점을 지정하면 403"을 따라 403으로 했다. `roomId`가 없거나 그 지점 룸이 아니면 404다.
- **예약의 "범위 밖"**: `POST`의 `scope`는 M4처럼 형식과 읽기 권한만 확인하고, 룸·고객의 범위 판정은 요청자가 읽을 수 있는 전체 지점 기준이다.
- **멱등 범위**: M4 결제와 같이 대상 지점 ID다. 같은 키로 다른 지점에 보내면 충돌이 아니라 별개 요청이다.
- **만료된 키 재사용**: 입실과 같이 기록을 지우고 새로 처리한다. 저장용 키는 같은 해시라 같은 시간이면 `ROOM_TIME_CONFLICT`, 다른 시간이면 `uk_payments_1`로 409 `IDEMPOTENCY_CONFLICT`다.
- **환불 거절 순서**: 목적 검사를 상태 검사보다 앞에 두었다. 스터디룸 결제는 #70 이후 `환불` 상태여도 `ALREADY_REFUNDED`가 아니라 `PAYMENT_NOT_REFUNDABLE`이다. 계약의 "다른 목적의 결제에는"을 그대로 따랐다.
- **`mockOutcome=FAILED`도 충돌 판정을 받는다.** 계약 표가 결과를 나누지 않으므로 겹치는 시간의 실패 시도는 409이고 실패 결제를 남기지 않는다.
- **`canCancel`**: 취소 API는 #70이지만 규칙이 계약에 있으므로 `확정`·시작 전·`payments:write`로 계산한다.
- **`usage`**: `room_usages`를 쓰는 곳이 #70이라 지금은 항상 null이다. 읽기 entity는 만들지 않았다.
- **`PAYMENT_PENDING` 행**: 어떤 API도 만들지 않는다. 결제의 `PENDING`과 같이 표시 시 `IllegalStateException`이다.
- **등록 시 `status=운영 중단`**: 스키마가 허용하므로 사유 없이 받는다. 좌석 등록과 같다.
- **정원 축소**: 기존 예약 인원을 저장하지 않으므로 제한하지 않는다.
- **결제의 고객 조회 범위**: M4-D05가 결제 상태·목적을 구분하지 않으므로, 다른 지점의 스터디룸 결제(실패 포함)도 그 지점의 고객 조회 범위를 만든다.

## 4. 구현 내용

- `modules/reservation`: `Room`, `RoomReservation`, `RoomReservationSlot` entity, 저장소, `RoomService`, `RoomReservationService`, `RoomConflicts`, `RoomInputs`, 컨트롤러 2개, 응답 DTO 8개
- `modules/payment`: `Payment.room`, `PaymentService.chargeRoom`·`statusLabels`, 환불의 목적 검사, `PaymentDetail`의 `roomReservationId`·`lockerAssignmentId`, 목적 표시, 목록의 `PASS` 고정 제거
- `BranchAction.WRITE_ROOMS`, `WorkspaceEventService`의 `rooms`·`room-reservations` 매핑, `LocalPreviewController`의 `rooms` 제거
- OpenAPI 9개 operation을 `implemented`로 변경, `space-operations-api.md` 구현 상태 표기. 계약 내용은 바꾸지 않았다.

매출 집계 코드는 아직 없다. `payments`를 읽는 다른 코드는 고객 조회 범위뿐이고 목적을 구분하지 않는다.

## 5. 검증 결과

- `./gradlew test` 전체(28분 36초): 61개 suite, 687개 테스트, 실패·오류·건너뜀 0. Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 전체 실행은 코드 기준 1회이고, 그 뒤에는 이 기록만 바꿨다.
- 신규 `RoomReservationIntegrationTests` 41개를 HTTP와 DB로 확인했다.
  - **요금 예시 5개 전부**: 8,000원 1시간 8,000원, 8,000원 3시간 24,000원, 12,500원 2시간 25,000원과 422 두 건. 응답, `room_reservations`, `payments`, 슬롯 시각을 모두 비교했다.
  - 룸: 권한 조합, 번호 중복(서비스 확인과 `uk_rooms_1` 모두), 멱등, 입력 범위, version·변경 불가 필드, 사유 필수, 운영 중단 후 기존 예약 유지, 운영 중지 지점에서의 원장 수정, 범위 밖 404
  - 예약: 권한 조합과 403 우선, 계약 표의 판정 순서, 시간 규칙(마이크로초·나노초, 0·음수 길이, 24시간 경계, 과거, 정확히 현재), 형식 오류, 금액 필드 거부, 겹침 5유형과 맞닿은 시간, 활성 상태만 점유, 금액 상한
  - 결제: 실패 시 슬롯 없음·재예약 가능·실패 재시도의 멱등, 같은 키·다른 입력 409, 다른 직원의 같은 키, 만료된 키, 저장용 키 120자 이내
  - 조회: 목록·달력·상세 응답 일치, 필터, 기간 겹침 경계, 페이지, 달력의 실패 제외와 62일 경계, 지점별 `billing` 노출, 스냅샷 유지, 응답 필드가 OpenAPI 필수 목록과 정확히 같음
  - 결제 API: 스터디룸 결제 상세·목록, 승인·실패 환불 409 `PAYMENT_NOT_REFUNDABLE`, 이용권 환불 유지
  - DB 방어선: 겹침 확인을 spy로 무력화하면 `uk_room_reservation_slots_1` 위반이 409 `ROOM_TIME_CONFLICT`가 되고 결제·예약·슬롯·감사·이벤트·멱등 기록이 남지 않음
- **실제 동시 실행**(요청마다 스레드를 두고 래치로 동시에 출발): 전부 통과했다.
  - 같은 룸·같은 시간 8건 3회(직원 2명): 1건 201, 나머지 `ROOM_TIME_CONFLICT`, 결제 1건
  - 일부만 겹치는 8건 3회: 겹침 없음, 결제 수 = 확정 수, 슬롯 수 = 확정 × 2
  - 같은 키 8건 3회: 모두 201·같은 ID, 결제 1건
  - 겹침 확인을 끈 8건: 1건 201, 나머지 슬롯 제약으로 409, 결제 1건
  - 예약과 요금 변경 6회: 금액이 항상 한 요금 × 시간. 두 순서의 분포는 측정하지 않았다.

**한계와 실행하지 않은 것**

- 모든 명령이 공통 guard로 직렬화되므로 동시성 시험의 통과는 주로 guard의 효과다. guard 없이 행 잠금만으로 버티는지는 검증하지 않았다.
- SSE로 `rooms`·`room-reservations` 이벤트가 실제 전달되는지는 시험하지 않았다. 아웃박스 행과 자원 매핑까지만 확인했다.
- 실제 서버 실행, Frontend 연결, 인프라 서버 검증, 부하 측정은 하지 않았다.
- 취소·이용 상태는 #70이 없어 시험에서 DB 값을 직접 바꿔 읽기만 확인했다.

## 6. 남은 작업 / 보류 사항

- #70: 취소·이용 시작·완료. `room_usages`를 읽어 `usage`를 채운다.
- #70 확인 필요: 계약은 `완료`가 시간을 점유하지 않는다고 하지만 슬롯 삭제는 취소에만 적혀 있다. 예약 종료 전에 완료하면 남은 슬롯이 `uk_room_reservation_slots_1`로 새 예약을 막는다. 화면에 활성 예약이 없는데 409 `ROOM_TIME_CONFLICT`가 나는 경우다. #70에서 완료 시 슬롯 처리를 정해야 한다.
- 계약 문구: `Payment` 스키마 설명의 "승인 결제는 passId와 approvedAt"은 이용권 결제만 맞다. 동작은 필드별 nullable을 따르며 설명은 바꾸지 않았다.
- `AuthorizedCommands` guard를 나누면 겹침 확인이 일반 읽기라는 점을 다시 봐야 한다. 공유 잠금끼리는 서로 막지 않으므로 그때는 슬롯 유일 제약이 동시 예약의 실제 방어선이 된다.
- 룸 운영 시간 검증은 범위 밖이다(ADR-M6-001).
