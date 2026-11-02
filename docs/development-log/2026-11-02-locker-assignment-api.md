# 2026-11-02 사물함 원장·유료 배정·연장·반납·가용 현황 API

관련: Issue [#71](https://github.com/OpenStudyService/OpenStudy-Backend/issues/71), [계약 문서](../design/foundation/space-operations-api.md), [ADR-M6-001](../db/ADR-M6-001.md) M6-D02·D03·D05, [룸 예약 기록](2026-11-02-room-reservation-api.md), [취소·이용 기록](2026-11-02-room-cancel-usage.md).

## 1. 배경 / 문제

룸은 슬롯 유일 키가 시간 겹침을 막지만 사물함은 그런 제약이 없다. `locker_assignments`에는 기간 겹침을 막는 DB 제약이 없어서, 잠금 안에서 판정하지 않으면 같은 사물함이 같은 날짜에 두 번 팔린다. 돈도 함께 움직이므로 결제·배정·과금이 어긋나도 안 된다.

배정 상태는 저장하지 않고 오늘 날짜로 계산한다. 그래서 목록·상세·현황이 서로 다른 기준으로 계산하면 숫자가 어긋날 수 있고, 오늘의 기준(Asia/Seoul)을 잘못 잡으면 UTC 15시부터 9시간 동안 틀린 값이 나온다.

## 2. 검토한 선택지

- **겹침 판정 쿼리**: 룸의 `existsOverlapping`처럼 잠금 없는 `SELECT`를 쓰는 방안과 잠금 읽기(`FOR SHARE`)를 쓰는 방안을 비교했다. 지금은 공통 guard가 명령 전체를 직렬화하고 사물함 잠금 전에 잠금 없는 읽기도 없어서 둘 다 동작한다. 그러나 잠금 없는 읽기는 REPEATABLE READ의 스냅샷을 읽으므로, 나중에 guard를 나누거나 잠금 앞에 일반 읽기가 끼면 방금 기다린 배정을 놓친다. 이 경우 막아 줄 DB 제약이 없다.
- **요금 일괄 적용**: 엔터티를 하나씩 바꾸는 방안은 사물함마다 UPDATE가 나가고, 같은 요금이면 dirty가 아니어서 version이 오르지 않는다. 계약은 각 version이 오른다고 정했으므로 벌크 UPDATE로 `version + 1`을 함께 쓴다.
- **금액 넘침**: 룸의 `Math.multiplyExact`를 그대로 쓰면 요금이 2^53 - 1에 가깝고 구간이 수만 개일 때 `long`을 넘어 500이 된다. 시간은 24 이하이지만 구간은 날짜 범위까지 커질 수 있어 곱하기 전에 비교한다.

## 3. 결정 및 이유

- **겹침·미반납은 사물함 쓰기 잠금 안에서 잠금 읽기로 판정한다.** 사물함 행 `FOR UPDATE` 뒤에 `ACTIVE` 배정을 `FOR SHARE`로 읽는다(`LockerAssignmentRepository.holding`). 안전성이 guard 하나에 기대지 않게 하기 위해서다. 연장은 계약 순서대로 배정 → 사물함을 잠그고 배정 생성은 사물함 → 배정 읽기이므로, guard가 나뉘면 교착이 생길 수 있다. 이때는 InnoDB가 한쪽을 실패시키므로 이중 판매 대신 오류가 된다.
- **금액은 잠근 행에서만 읽는다.** 요금이 바뀌는 쪽(수정·일괄 적용)도 같은 행을 쓰기 잠그므로, 배정은 옛 요금 전체나 새 요금 전체 중 하나로만 계산된다.
- **판정은 결제보다 먼저다.** 미반납·겹침으로 거절되면 `mockOutcome=FAILED`여도 결제가 남지 않는다.
- **배정 상태의 정의는 한 곳이다.** `LockerOccupation`의 JPQL 조각으로 목록 필터·현황 집계·응답 매핑을 만든다. 현황은 SQL로 센다. `:today`는 서비스가 Asia/Seoul로 계산해 넘기고 DB 시계는 쓰지 않는다.
- **`locker_assignments`에는 지점 열이 없다.** 지점 범위는 항상 사물함을 거쳐 판단한다. 고객이 내 지점 소속이어도 다른 지점 사물함의 배정은 보이지 않는다.
- **멱등 재생은 version 검사보다 앞이다.** 처리된 연장의 재시도는 지나간 version을 들고 오기 때문이다(#70과 같은 이유).
- **날짜 입력은 `yyyy-MM-dd` 형식만 받는다.** `LocalDate.parse`는 `+12026-11-29`도 읽는데 DATE 열에 넣을 수 없다.

계약이 정하지 않아 판단한 점:

- **연장의 판정 순서**: 계약은 표 없이 조건만 나열한다. 트랜잭션 표에 맞춰 404 → 403 → 멱등 재생 → 배정 잠금 → version → 상태 → 지점 → 사물함 잠금 → 운영 중단 → 기간 → 겹침으로 했다.
- **연장의 고객 상태**: 절·트랜잭션 표·`x-error-codes` 어디에도 없어 확인하지 않는다. 제한·비활성 고객의 기존 배정도 연장된다.
- **연장과 다른 배정의 미반납**: 연장의 오류 코드에 `LOCKER_NOT_RETURNED`가 없다. 앞 배정이 미반납인 상태에서 뒤 예정 배정을 연장하는 것은 허용된다.
- **`POST /branches/{id}/locker-rate`의 범위 밖**: 경로의 지점을 자원으로 보아 소속이 아니거나 없는 지점은 404, 소속이고 권한이 없으면 403이다(`x-error-codes`에 둘 다 있다).
- **같은 요금의 일괄 적용**: 계약 문구대로 version이 오르고 `updated`에 포함한다. 사물함이 없는 지점은 `updated=0`이고 감사 1건이 남는다.
- **일괄 적용의 감사·이벤트**: 감사는 `entity_type=branches`, `action=LOCKER_RATE_APPLIED`, 이전 요금은 고유값 목록이다. 이벤트는 지점의 `lockers` 1건이고 `entityId`는 null이다.
- **배정·연장·반납의 이벤트**: 사물함 상태가 배정에서 계산되므로 `locker-assignments`와 `lockers`를 함께 보낸다. 실패 연장도 과금 내역이 바뀌므로 보낸다.
- **`activeAssignment`**: 미반납 배정과 다음 배정이 함께 시작된 상태면 시작일이 빠른 쪽, 곧 미반납 배정을 보여 준다. 운영 중단 사물함도 사용 중인 배정이 있으면 담는다. 반납할 직원에게 필요하기 때문이다. 상태는 `운영 중단`이다.
- **현황의 `overdue`**: 배정이 아니라 사물함 수다. `inUse`의 부분집합이어야 하므로 운영 중단 사물함은 세지 않는다.
- **연장의 `lastChangedBy`**: 승인된 연장은 처리자를 남긴다. 실패 연장은 배정을 바꾸지 않으므로 version과 함께 그대로다.
- **금액 상한 초과**: 전용 코드가 없어 룸과 같이 422 `VALIDATION_FAILED`(`endDate`)다. 표 순서상 겹침 판정 뒤에 나온다.
- **만료된 키 재사용**: 룸과 같이 멱등 기록을 지우고 새로 처리하지만, 결제 저장 키가 같아 `uk_payments_1`에서 409 `IDEMPOTENCY_CONFLICT`가 되고 전체가 롤백된다.
- **멱등 범위**: 룸과 같이 (요청자, operation, 지점 또는 배정 ID, 키)다.
- **감사 `action`**: 배정은 결과 상태 이름(`ACTIVE`, `PAYMENT_FAILED`), 연장은 `EXTENDED`·`EXTENSION_FAILED`, 반납은 `RETURNED`다.

## 4. 구현 내용

- `modules/locker`: 사물함·배정·과금 엔터티와 상태 enum, 저장소, `LockerService`, `LockerAssignmentService`, 컨트롤러 2개, 응답 DTO. V1 스키마를 그대로 쓰고 마이그레이션은 없다. `initial_assignment_slot` 생성 열은 매핑하지 않았다.
- `modules/payment`: `Payment.locker`, `PaymentService.chargeLocker`, 결제 응답의 `lockerAssignmentId`(`locker_assignment_charges`에서 조회). 연장 결제도 해당 배정을 가리킨다. `canRefund`와 환불 API의 거절은 기존 코드 그대로 사물함 결제에 적용된다.
- `BranchAction.WRITE_LOCKERS`, `WorkspaceEventService`의 `lockers`·`locker-assignments` 매핑, `LocalPreviewController`의 `lockers` 샘플 경로 제거
- OpenAPI 12개 operation을 `implemented`로 변경하고 `space-operations-api.md`의 구현 상태 문장을 갱신. 그 밖의 계약 변경은 없다.

## 5. 검증 결과

- `./gradlew test` 전체(32분 8초): 64개 suite, 798개 테스트, 실패·오류·건너뜀 0. Testcontainers MySQL 8.4 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 전체 실행은 코드 기준 1회로 첫 실행에서 통과했고, 그 뒤에는 이 기록만 바꿨다.
- 전체 실행 전 부분 실행에서 실패한 것은 테스트 코드의 실수뿐이었다(리플렉션 오버로드 선택, 없는 날짜 11-31, 28일 배수가 아닌 시험 날짜, spy 복구 방식). 구현은 바꾸지 않았다.
- 신규 `LockerAssignmentIntegrationTests` 53개, `LockerLedgerIntegrationTests` 22개를 HTTP와 DB로 확인했다. `SpaceOperationsContractTests`에 구현 상태 시험 1개를 더했다.
  - 권한: 배정·연장의 `payments:write`, 원장·일괄 적용의 `operations:write`, 반납의 일반 직원, 범위 밖 404·403
  - 판정 순서: 계약 표의 각 행을 앞 행과 겹치게 만들어 순서대로 확인
  - 28일 경계: 27·29·55·57·0·음수 일 거절, 28·56일 허용. 연장도 같은 방식
  - 날짜 경계: 오늘 시작 허용과 어제 거부, Asia/Seoul 자정 전후(14:59:59.999999Z, 15:00:00Z)의 배정·상세·필터·현황
  - 과금: 계약 표 4행과 연장 예시를 응답과 DB(`locker_assignment_charges`, `payments`)로 확인. 12,000원 1구간 12,000원, 12,000원 2구간 24,000원, 15,000원 3구간 45,000원, 30일 422, 연장 1구간 12,000원
  - 결제 실패: 기간 미점유, 실패 후 새 키 재시도, 실패 연장의 종료일·version 유지
  - 멱등: 같은 키 재시도의 같은 본문과 결제 1건, 다른 입력 409, 거절의 미저장, 다른 직원의 키, 만료된 키, 처리된 연장의 재시도
  - 반납: 조기 반납 후 같은 날짜 재배정, 환불 없음, `endDate` 유지, `returned_at`·`last_changed_by`, 운영 중단·운영 중지에서 허용
  - 미반납: 종료일 당일부터 `overdue`, `LOCKER_NOT_RETURNED`, 끝나는 날 다음 배정 허용, 미반납과 다음 배정이 겹칠 때 사물함 1개로 집계
  - 현황: 9가지 상태를 섞은 지점에서 현황 수치와 필터 목록이 일치하고, 목록 행과 상세가 같음
  - 스냅샷·노출: 요금 변경·일괄 적용 뒤 스냅샷 유지, `charges`의 권한별 null, 지점이 섞인 목록의 행별 판단
  - 결제 연동: `lockerAssignmentId`, `purpose=사물함`, `canRefund=false`, 환불 API 409 `PAYMENT_NOT_REFUNDABLE`
  - 중간 실패: 과금 저장·연장 저장에서 예외를 내면 전부 롤백되고 같은 키로 다시 성공
- **실제 동시 실행**(요청마다 스레드를 두고 래치로 동시에 출발): 전부 통과했다.
  - 같은 사물함의 겹치는 배정 8건 3회: 1건 201, 나머지 `LOCKER_PERIOD_CONFLICT`, 결제·배정 합계 1건
  - 겹치지 않는 3건: 모두 201
  - 같은 키 8건 3회: 모두 201·같은 본문, 결제 1건
  - 같은 version 연장 6건: 1건 200, 나머지 `VERSION_CONFLICT`, 추가 결제 1건
  - 연장과 같은 기간 배정 8회: 항상 한쪽만 성공. 전체 실행에서 연장 승 4회, 배정 승 4회
  - 반납과 연장 10회: 항상 한쪽만 성공. 전체 실행에서는 10회 모두 반납 승이었다. 연장 승은 그 전 부분 실행 두 번에서 5회, 1회 확인했다.
  - 일괄 적용과 배정 8회: 금액은 항상 구간 × 잠근 요금이고 사물함 version은 일괄 적용으로만 1 오름. 전체 실행에서 새 요금 4회, 옛 요금 4회
- **잠금의 실효성**: guard가 명령을 직렬화하므로 위 경쟁만으로는 행 잠금이 시험되지 않는다. 그래서 guard 없이 행만 잠근 트랜잭션을 두고, 요청이 `LOCK WAIT`에 들어간 뒤 커밋하는 시험 2개를 더했다. 기다린 요청은 커밋된 배정을 보고 409가 되고, 커밋된 새 요금으로 계산한다. 배정의 `lockers.locked`를 `findById`로 임시 변경하면 요금 시험이 실패하는 것(15,000 기대, 12,000)을 확인하고 되돌렸다. 겹침 쪽 시험은 이 변경으로 실패하지 않았다. 커밋 전의 배정 행을 요청의 잠금 읽기(`holding`)가 기다리기 때문으로 본다(원인은 따로 확인하지 않았다).
- JVM 기본 시간대 차이: 사물함 시험 전체를 `-Duser.timezone=UTC`로 따로 실행해 통과했다(당시 75개). 전체 실행은 이 PC의 Asia/Seoul이다. `DATE` 열의 값이 시간대로 밀리지 않음을 DB 문자열로 확인했다.
- 미검증: 실제 운영 환경, 실제 SSE로 받는 `lockers`·`locker-assignments` 이벤트(outbox 행과 매핑 함수만 확인), `local` 프로필에서 `/api/lockers`가 실제 컨트롤러로 가는 것(샘플 경로 제거만 함).

## 6. 남은 작업 / 보류 사항

- 조기 반납 환불과 사물함 이동은 범위 밖이다.
- 고객의 룸·사물함 이력과 타 지점 요약은 #72다.
- 앞 배정이 미반납인 채로 뒤 예정 배정의 시작일이 오면 계약상 막을 방법이 없다. 현황은 사물함 1개로 세고 미반납 배정을 보여 주지만, 실제 사용은 현장에서 정리해야 한다. 필요하면 정책 결정이 있어야 한다.
