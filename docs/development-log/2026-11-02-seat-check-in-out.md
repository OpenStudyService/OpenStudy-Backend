# 2026-11-02 직원 예외 입실·퇴실과 이용권 차감 API

관련: Issue [#62](https://github.com/OpenStudyService/OpenStudy-Backend/issues/62), [계약 문서](../design/foundation/seat-usage-api.md), [ADR-M5-001](../db/ADR-M5-001.md), [ADR-M4-002](../db/ADR-M4-002.md), [좌석 원장 기록](2026-11-02-seat-management-api.md).

## 1. 배경 / 문제

M5의 핵심 흐름이다. 직원이 고객을 좌석에 배정하고 퇴실시킬 때 좌석 점유·이용 기록·이용권 잔량이 한 번에 맞게 확정되어야 한다. 잔량과 금액을 다루므로 차감량은 서버만 계산하고, 반복 퇴실이나 동시 요청으로 이중 차감이 생기면 안 된다. 또한 M5-D01로 다른 지점 고객이 입실할 수 있게 되었지만 M4-D05 범위로는 그 고객을 찾을 수 없어, 범위를 넓히지 않는 별도 정확 일치 조회가 필요했다.

이번 범위의 operation은 정확 일치 조회, 입실, 이용 기록 상세, 퇴실 4개다. 좌석 이동(#63), 수동 조정·이력 목록·구매 내역(#64)은 구현하지 않았다.

## 2. 검토한 선택지

- **차감 계산 위치**
  - `SeatSessionService`에 둔다: 다른 모듈의 `Pass` entity와 `ProductKind`를 가져와야 하는데 `ModuleBoundaryTests`가 금지한다.
  - ticket 모듈에 순수 계산 클래스를 두고, 잠금·원장·잔량은 `PassService.settle`이 맡는다: 이쪽을 택했다. 계산이 DB 없이 시험되고, seat 모듈은 결과 DTO만 받는다.
- **잠금 뒤 행 읽기**
  - 기존처럼 네이티브 `FOR UPDATE` 뒤 `findById`를 쓴다: REPEATABLE READ의 스냅샷 값을 읽을 수 있어 공통 guard에 기대게 된다.
  - JPA `@Lock(PESSIMISTIC_WRITE)` 조회를 쓴다: 잠금 읽기라 항상 최신 커밋 값을 받는다. 이쪽을 택했다. 다만 같은 트랜잭션에서 entity를 먼저 읽으면 이전 값이 남으므로, 지점 판정은 `branchOf` 투영 조회로 entity를 불러오지 않는다.
- **범위 밖 404 판정**
  - 기존처럼 트랜잭션 밖에서 미리 읽고 `inBranches`로 들어간다: 그 사이 소속이 회수되면 404여야 할 응답이 403이 된다. 계약은 "모두 잠금 뒤 재확인"이다.
  - `AuthorizedCommands.inRecordBranch`를 추가해 guard 아래에서 지점을 읽고 판정한다: 이쪽을 택했다. 없음과 범위 밖이 같은 404다.
- **DB 유일 제약 위반의 변환**: `DuplicateKeys.translate`로 MySQL 1062만 좁힌 뒤 제약 이름으로 구분한다. 메시지 문자열에 의존하지만, 실제 MySQL 오류로 시험해 고정했다.
- **활성 이용 기록 fixture**: `SeatIntegrationTests`의 외래 키를 끈 직접 삽입을 실제 판매·입실·퇴실로 바꿨다. 입실 시각이 서버 시각이 되므로 고정 시각 단언은 DB 값 비교로 바꿨다.

## 3. 결정 및 이유

- **잠금 순서는 계약의 트랜잭션 표를 따르고, 거절 판정은 오류 표의 순서를 따른다.** 멱등 → 좌석 → 고객 공유 잠금 → 이용권 잠금 뒤, 이용권 없음·고객 상태·활성 고객·이용권 상태 순으로 판정한다. 그래서 고객 잠금과 고객 상태 판정을 두 메서드로 나눴다.
- **처리 시각은 잠금을 모두 잡은 뒤 읽는다.** 대기 중 만료된 이용권이 통과하지 않게 하기 위해서다. 저장 상태가 `ACTIVE`여도 시각으로 판정한다.
- **차감 분은 저장 열이 없으므로 계산한다.** 시간권은 사용 분이 차감 또는 초과이므로 `billed_minutes − overage_minutes`가 차감 분이다. 정액권의 `billed_krw`는 실제 차감액이다.
- **이중 차감은 세 겹으로 막는다.** 같은 키는 멱등 기록이 첫 결과를 돌려주고, 다른 키는 이용 기록 잠금 뒤 `SESSION_ALREADY_CLOSED`이며, 마지막으로 `USE-{이용 기록 ID}` 키와 `uk_pass_balance_events_1`이 막는다. 마지막 방어선이 동작하면 트랜잭션 전체가 롤백된다.
- **정확 일치는 자바에서 한 번 더 비교한다.** 컬럼 collation(`utf8mb4_0900_ai_ci`)이 대소문자와 악센트를 무시해 `m-abc`가 `M-ABC`에 일치하기 때문이다. 계약의 "완전히 같아야 한다"를 지키려고 DB 조회 뒤 `String.equals`로 다시 확인한다.
- **조회도 guard 안에서 처리한다.** 조회 기록을 쓰는 명령이고, 요청자 소속을 기록의 외래 키와 같은 시점으로 판정하기 위해서다.

계약이 정하지 않아 판단한 점:

- **만료 전 분의 하한**: 만료 시각이 입실 전으로 조정되면(M5-D04, #64) `ceil((E − in) ÷ 1분)`이 음수가 될 수 있다. `C`를 0으로 막아 전부 만료 후 분으로 기록한다.
- **서버 시각이 입실 시각보다 앞선 퇴실**: `ck_seat_sessions_2`가 거절하면 퇴실이 영구히 막히므로 퇴실 시각을 입실 시각으로 올려 0분으로 닫는다.
- **아주 큰 요율**: 요율 상한은 2^53 − 1이라 `분 × 요율`이 `long`을 넘을 수 있다. `BigInteger`로 정확히 계산하고, `overage_krw`만 API 정수 상한에서 멈춘다. 퇴실이 산술 오류로 실패하지 않게 하기 위해서다.
- **만료된 멱등 기록의 키 재사용**: 기록이 지나도 `start_request_key` 해시는 같아 `uk_seat_sessions_3`이 거절한다. 409 `IDEMPOTENCY_CONFLICT`로 답한다. 퇴실은 이미 닫힌 기록이므로 `SESSION_ALREADY_CLOSED`다.
- **입실의 멱등 범위**: 계약대로 범위가 좌석 ID라서, 같은 키로 다른 좌석에 보내면 충돌이 아니라 별개 요청이다.
- **`NOT_STARTED` 이용권**: 신규 발급에는 없지만 V1에 남은 값이다. 계약에 코드가 없어 409 `INVALID_STATE`로 거절한다.
- **`reason`**: 없음·공백은 사유 없음이고, `null`과 문자열이 아닌 값은 스키마대로 422다. `memberNo`는 다른 입력처럼 앞뒤 공백을 지운 뒤 비교한다.
- **OWNER의 조회 기록**: 조회 지점에 소속 행이 있으면(상태 무관) `customer_service_interactions`, 없으면 `audit_events`다.
- **USE 원장의 `reason`**: 퇴실 사유를 그대로 남긴다. 처리 순서는 원장·잔량 갱신 뒤 이용 기록 종료이나 한 트랜잭션이라 결과는 같다.
- **퇴실의 `passes` 이벤트**: 트랜잭션 표대로 차감 여부와 무관하게 보낸다. 퇴실 응답의 `moves`도 상세와 같이 채운다.
- **`usablePasses` 정렬**: 계약에 없어 이용권 ID 오름차순이다.

## 4. 구현 내용

- `modules/seat`: `SeatSession`을 쓰기 가능한 entity로 확장, `SeatSessionMove` 읽기 모델, `SeatSessionService`, `CheckInLookupService`(정확 일치의 유일한 진입점), `SeatSessionConflicts`, `SeatSessionController`, `CheckInLookupController`, 응답 DTO 7개
- `modules/ticket`: `PassBalanceEvent` entity·repository, `PassSettlementCalculator`, `PassService`의 `lockOwned`·`requireUsable`·`settle`·`sessionPass`·`usableBy`, `Pass.deduct`, DTO 3개
- `modules/customer`: `CustomerServiceInteraction` entity·repository, `CustomerService`의 입실 잠금·상태 확인·정확 일치·조회 기록
- `AuthorizedCommands.inRecordBranch`, `WorkspaceEventService`의 `seat-sessions` 매핑
- OpenAPI 4개 operation을 `implemented`로 변경, `seat-usage-api.md` 구현 상태 표기. 계약 내용은 바꾸지 않았다.

마이그레이션은 없다(V1 스키마 그대로).

## 5. 검증 결과

- `./gradlew test` 전체: 49개 suite, 399개 테스트, 실패·오류·건너뜀 0. Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다.
- 신규 `PassSettlementCalculatorTests` 25개: 계약 예시 8개, 분 올림 경계(0초·1마이크로초·60초·61초 등), 잔량 경계, 만료 경계, 입실 전 만료, 아주 큰 요율.
- 신규 `SeatSessionIntegrationTests` 35개를 HTTP와 DB로 확인했다.
  - **계산 예시 8개 전부**: 고정 시계로 입실 10:00:00과 계약의 퇴실 시각을 재현했다. 응답, `seat_sessions`, 잔량·상태, 원장 행의 증감·키가 #60에서 손으로 계산한 값과 모두 같았다. 계약 예시에서 틀린 값은 찾지 못했다.
  - 입실: 오류 표의 각 조건과 거절 순서, 거절 시 이용 기록·감사·멱등 기록이 남지 않음, 만료 경계(같은 시각 거절, 1마이크로초 전 허용), 무기한 이용권, 입력 검증, CSRF, 멱등 재요청과 키 충돌
  - 다른 지점: 정확 일치 조회·입실·퇴실이 되고, 일반 고객 목록·상세와 이용권 조회에는 계속 나타나지 않음. 구매 지점이 운영 중지여도 사용 가능. 이벤트 지점 분리(`seats`·`seat-sessions`는 이용 지점, `passes`는 구매 지점)
  - 정확 일치 조회: 부분·이름·대소문자·와일드카드 불일치, 최소 필드, 사용 가능 이용권만 노출, 조회 기록, OWNER의 감사 기록, 일치 없음 감사에 조회 값이 남지 않음, 공유 연락처 10명 제한과 순서
  - 퇴실: 처리자·사유, 반복 퇴실(같은 키·다른 키·다른 직원), 고객 비활성·지점 중지·이용권 만료에도 허용, 요청의 분·금액 필드 거부, 발급량 + 원장 합계 = 잔량
  - DB 방어선: `USE-{id}` 행을 미리 넣으면 409와 전체 롤백. 서비스 재확인을 spy로 무력화하면 실제 MySQL의 `uk_seat_sessions_1`·`_2` 위반이 각각 `SEAT_OCCUPIED`·`CUSTOMER_ALREADY_CHECKED_IN`이 됨. 만료된 키 재사용은 `uk_seat_sessions_3`으로 409
- **실제 동시 실행**(요청마다 스레드를 두고 래치로 동시에 출발): 전부 통과했다.
  - 같은 좌석 고객 8명 3회: 1건 201, 나머지 `SEAT_OCCUPIED`
  - 같은 고객 좌석 8개 3회(직원 2명): 1건 201, 나머지 `CUSTOMER_ALREADY_CHECKED_IN`
  - 다른 키 동시 퇴실 8건 3회(직원 2명): 1건 200, 나머지 `SESSION_ALREADY_CLOSED`, 원장 1행
  - 같은 키 동시 입실·퇴실 각 8건: 모두 같은 결과, 행 1개
  - 입실과 운영 중단 12회: 둘 중 하나만 성공. 처음에는 입실이 12번 모두 먼저여서 입실 출발을 0~33ms 늦추도록 고쳤고, 측정 실행에서 입실 선행 4회·중단 선행 8회로 두 순서가 모두 나왔다. 측정용 출력은 지웠고 단언은 합계만 본다.
  - 입실과 환불 6회: 둘 중 하나만 성공. 두 순서의 분포는 측정하지 않았다.
- `SeatIntegrationTests` 19개: 활성 이용 기록을 실제 판매·입실·퇴실로 만들도록 바꿨고 의도는 유지했다. 입실 이벤트가 생겨 좌석 수정 시험의 이벤트 수만 1에서 2로 바뀌었다.

**한계와 실행하지 않은 것**

- 모든 명령이 공통 guard로 직렬화되므로 동시성 시험의 통과는 주로 guard의 효과다. guard 없이 행 잠금만으로 버티는지는 검증하지 않았다.
- SSE로 `seat-sessions` 이벤트가 실제 전달되는지는 시험하지 않았다. 아웃박스 행까지만 확인했다.
- 실제 서버 실행, Frontend 연결, 인프라 서버 검증, 부하 측정은 하지 않았다.
- 이동 이력은 #63이 없어 시험에서 표에 직접 넣어 읽기만 확인했다.

## 6. 남은 작업 / 보류 사항

- #63: 좌석 이동. `SeatSessionMove`를 쓰기 가능하게 바꾸고 `seat_id` 변경 메서드를 추가한다. 상세 응답은 이미 이동 이력을 읽는다.
- #64: 입퇴실 이력 목록, 수동 조정, 구매·사용 내역. 샘플 경로의 `usage`는 건드리지 않았다.
- 조회 횟수 제한(M5-D08)은 M11 후속이며 `CheckInLookupService.lookup`에 붙인다.
- `AuthorizedCommands` guard를 나누게 되면 고객·활성 이용 재확인이 일반 읽기라는 점을 다시 봐야 한다. 지금은 guard 아래에서 스냅샷을 잡으므로 최신이고, DB 유일 제약이 마지막 방어선이다.
