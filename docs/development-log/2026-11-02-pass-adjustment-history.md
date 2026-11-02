# 2026-11-02 이용권 수동 조정과 입퇴실·구매·사용 내역 API

관련: Issue [#64](https://github.com/OpenStudyService/OpenStudy-Backend/issues/64), [계약 문서](../design/foundation/seat-usage-api.md)의 "이용권 수동 조정"·"입퇴실 이력"·"고객의 구매·사용 내역", [ADR-M5-001](../db/ADR-M5-001.md)(M5-D04·D06), [ADR-M4-002](../db/ADR-M4-002.md)(M4-D05), [입퇴실 기록](2026-11-02-seat-check-in-out.md), [좌석 이동 기록](2026-11-02-seat-session-move.md).

## 1. 배경 / 문제

M5의 마지막 이슈다. 잔량과 유효기간을 사람이 직접 바꾸는 쓰기 하나와, 입퇴실 이력과 고객별 내역이라는 읽기 둘을 구현한다. 세 가지가 특히 중요했다.

- 조정은 돈과 같은 값을 바꾸므로 구매 지점의 `payments:write`로만 할 수 있어야 하고, 전후 값과 이유가 남아야 한다.
- 이용권은 어느 지점에서든 쓸 수 있으므로(M5-D01), 내역에는 요청자가 볼 수 없는 지점의 이용이 섞인다. 원장 합계를 설명하려면 보여 줘야 하지만 다른 지점의 처리자·사유는 드러나면 안 된다.
- 내역의 잔량 대조(`발급량 + 원장 합계 = 현재 잔량`)는 조정·퇴실이 동시에 일어나도 항상 맞아야 한다.

집계·분석(M9)과 고객 상세의 타 지점 요약(M6)은 구현하지 않았다.

## 2. 검토한 선택지

- **고객 내역을 둘 모듈**
  - 고객 모듈: 좌석·결제 모듈이 이미 고객 모듈에 의존해 의존 순환이 생기고 `ModuleBoundaryTests`가 막는다.
  - ticket 모듈에 네이티브 SQL로 `seat_sessions`·`payments`·`refunds`를 읽는 방식: `usedIds`에 선례가 있지만, 네 표를 한 저장소가 읽게 된다.
  - 공개 service만 묶는 읽기 전용 모듈 `passhistory`: 아무도 이 모듈에 의존하지 않아 순환이 없다. 이쪽을 택했다.
- **퇴실 처리자·사유의 원천**
  - `USE` 원장 행: 기간권과 차감 0인 퇴실에는 행이 없어 처리자·사유를 비워야 한다.
  - 퇴실 감사 행(`CHECKED_OUT`): 모든 퇴실에 있고 계약이 "퇴실 처리자와 사유는 `audit_events`에 남긴다"고 정했다. 이쪽을 택했다. `AuditTrail`에 읽기 메서드를 하나 추가했다.
- **원장 행의 시각**
  - DB 기본값(`created_at DEFAULT CURRENT_TIMESTAMP(6)`): entity 수정이 없지만 서버 시계와 DB 시계가 다를 수 있어 이용 기록·감사와 어긋난다.
  - 서버가 처리 시각을 넣는 방식: `USE`는 퇴실 시각, `ADJUST`는 처리 시각이 된다. 기간 필터의 기준이 이용 기록과 같아져 이쪽을 택했다.

## 3. 결정 및 이유

- **권한 판정은 잠금 뒤 계약 표의 순서다.** `inRecordBranch(READ)`로 "없음·구매 지점 범위 밖 = 404"를 guard 아래에서 정하고, 요청 `scope`가 구매 지점을 포함하지 않아도 404다. 그 뒤 구매 지점의 `WRITE_PAYMENT_HISTORY`를 요구해 403으로 답한다. 이용 지점의 `payments:write`는 아무 효력이 없다. 멱등 조회는 권한 판정 뒤다.
- **판정 순서는 입력 → 404 → 403 → 멱등 → 이용권 잠금 → 유형별 422 → `PASS_REFUNDED` → version이다.** 계약 표의 순서이며, 유형을 드러내는 422는 권한을 통과한 뒤에만 나온다.
- **상태는 잔량으로 다시 계산한다.** 시간권·정액권은 잔량이 0이면 `EXHAUSTED`, 아니면 `ACTIVE`다. 만료는 계속 시각으로 판정하고, 저장 상태 `EXPIRED`(지금은 아무도 쓰지 않는 V1 값)는 만료 시각이 풀릴 때만 벗어난다. 환불은 어떤 조정으로도 돌아오지 않는다.
- **입실 중 조정은 별도 분기가 없다.** 조정과 퇴실이 같은 guard와 같은 이용권 행 잠금을 쓰고, 퇴실은 잠금 뒤의 잔량·만료를 읽는다. 어느 쪽이 먼저든 나중 쪽이 커밋된 값을 본다. 퇴실이 먼저면 version이 바뀌어 조정은 `VERSION_CONFLICT`가 된다. 아무도 보지 못한 값을 조정하지 않게 하는 올바른 결과다.
- **내역은 읽기 전용 트랜잭션 하나로 만든다.** 이용권 행과 원장 행을 같은 스냅샷(MySQL 기본 REPEATABLE READ)에서 읽어야 잔량 대조가 동시 쓰기 중에도 맞기 때문이다. 원장은 기간과 무관하게 전체를 읽고, 기간 필터는 `events`에만 적용한다.
- **다른 지점 판정 기준은 요청 `scope`다.** 계약 문구가 "요청자 scope 밖"이다. 두 지점 소속 직원이 `scope=첫 지점`으로 조회하면 둘째 지점 이용도 최소 정보만 나온다.

계약이 정하지 않아 판단한 점:

- **다른 지점 이용의 `usedMinutes`·`seatSessionId`**: 스키마는 `reason`·`actor`만 null로 명시하지만, 계약 본문과 PR #136 결정이 노출을 "지점·시각·증감량"으로 한정한다. 두 필드도 null로 둔다. 이용 기록 ID는 그 지점에서 보이지 않는 기록의 식별자이고, 이용 분은 증감량이 아니다. 기간권의 다른 지점 이용은 그래서 증감 0으로만 보인다.
- **구매·환불 사건의 증감**: 0이다. 스키마가 `deltaMinutes`를 "원장 증감"으로 정의하고 두 사건은 원장 행이 없다. 그래서 전체 기간 사건의 증감 합은 `ledgerMinutes`·`ledgerKrw`와 같다. 발급량은 `passes`의 `issuedMinutes`·`issuedCreditKrw`가 준다.
- **진행 중 이용**: 사건이 아니다. 차감이 확정되는 퇴실 때 `이용` 사건이 된다. 사건 시각은 퇴실 시각이다.
- **`NOT_STARTED` 이용권의 조정**: 409 `INVALID_STATE`다. 입실과 같은 처리이며, `ck_passes_period_activation`이 이 상태에 만료 시각을 허용하지 않아 그대로 두면 500이 된다. `adjustPass`의 `x-error-codes`에는 없다.
- **`expiresAt` 비교**: `expiresAt`은 마이크로초로 자른 뒤 현재 값과 비교한다. DB 정밀도가 마이크로초라서 잘라야 "값이 같으면 원장을 만들지 않는다"가 성립한다.
- **`expiresAt` 상한·하한**: 1000~9998년만 받는다. 형식은 맞아도 `DATETIME`에 담기지 않는 연도가 500이 되지 않게 하기 위해서다. `from`·`to`도 같다.
- **변경 없는 요청도 멱등 결과로 저장한다.** 계약의 "최초 처리의 상태와 본문을 돌려준다"를 그대로 따랐다.
- **멱등 기록 만료 뒤의 키 재사용**: 입실·이동과 같이 저장 키 해시가 같아 `uk_pass_balance_events_1`이 거절하므로 409 `IDEMPOTENCY_CONFLICT`로 바꾼다.
- **감사**: 동작명 `ADJUSTED`, 전후 값은 `remainingMinutes`·`remainingCreditKrw`·`expiresAt`·`status`다.
- **같은 시각 사건 순서**: 구매 → 이용 → 조정 → 환불이다. 고정 시각에서도 결과가 흔들리지 않게 했다.
- **아주 큰 페이지 번호**: `ListQuery`는 2^53 − 1까지 허용하지만 JPA offset은 int다. 넘으면 빈 목록과 실제 total을 돌려준다.
- **원장 합계의 범위**: 합계는 해당 이용권의 모든 행이다. 계약은 `USE`·`ADJUST`라고 적었고, `RESTORE`를 만드는 코드는 아직 없어 결과가 같다.

## 4. 구현 내용

- `modules/ticket`: `PassAdjustmentService`(`POST /api/passes/{id}/adjustments`), `Pass.adjust`, `PassBalanceEvent.adjust`와 `created_at` 매핑, `PassService`의 `ledgers`·`view`·`sessionPasses`, DTO `PassLedger`
- `modules/seat`: `SeatSessionService.list`(`GET /api/seat-sessions`)·`usage`, `SeatSessionSearchRepository`, DTO `SeatUsage`. 목록 응답은 페이지 단위로 묶어 읽고, 상세와 같은 조립 코드를 쓴다.
- `modules/passhistory`(신규): `PassHistoryService`, `PassHistoryController`(`GET /api/customers/{id}/pass-history`), 응답 DTO 3개
- `modules/payment`: `PaymentService.trails`, DTO `PaymentTrail`(판매자·환불자·시각·사유. 금액 없음)
- `modules/customer`: `CustomerService.requireVisible`(M4-D05 공개 진입점)
- `common`: `TimeRange`(`[from, to)` 파싱), `AuditTrail.recorded`
- 계약 문서: OpenAPI 세 operation을 `implemented`로 바꾸고 소개 문구와 `seat-usage-api.md` 구현 상태를 갱신했다.
- 계약 보완(#62에서 구현했으나 문서에 없던 동작을 그대로 기록): 수동 조정으로 `E ≤ in`이면 `C = 0`, `A = U`. `NOT_STARTED` 이용권의 입실은 409 `INVALID_STATE`. 코드(`PassSettlementCalculator.beforeExpiry`, `PassService.requireUsable`, `ApiExceptionHandler`)를 확인한 뒤 적었고, `checkIn`의 `x-error-codes`에 `INVALID_STATE`만 추가했다. `moveSeatSession`은 이용권 상태를 읽지 않아 추가하지 않았다. 스키마는 바꾸지 않았다.
- `LocalPreviewController`의 샘플 경로는 세 API와 충돌하지 않아 수정하지 않았다.

마이그레이션은 없다(V1 스키마 그대로).

## 5. 검증 결과

- 신규 `PassAdjustmentIntegrationTests` 36개, `PassHistoryIntegrationTests` 25개를 HTTP와 실제 MySQL(Testcontainers)로 실행해 모두 통과했다. 판매·입실·퇴실은 fixture에서도 실제 흐름으로 만들었다.
  - **조정 기록**: 원장 행(증감·처리자·사유·`ADJ-` 키), 감사 전후 값, 구매 지점 이벤트, 시간권·정액권·기간권 각각, 잔량과 만료 동시 조정
  - **권한**: 권한 없는 직원 403, 다른 지점의 `payments:write` 404, 권한 없는 MANAGER 403, 이용 중인 지점 404, 소유자 가능, 소속 회수 404, 없는 ID 404, `scope` 교차, CSRF 누락 403, 운영 중지 구매 지점 허용, 거절 뒤 행·감사·멱등 기록 없음
  - **입력**: 음수·상한 초과·소수·문자열·null·필드 없음·사유 없음·공백·501자·version 없음·허용 외 필드·날짜 형식과 범위·유형 불일치·기간권 `null`·`expiresAt ≤ startedAt`·키 누락·짧은 키, 최대값 저장
  - **상태**: 환불 재활성화 거부, 소진 → 활성과 재입실, 0으로 조정 시 소진, 만료 연장 → 활성(시간권·기간권)과 재입실, 잔량만 늘린 만료는 만료 유지, 저장 `EXPIRED` 해제, 과거 만료 정정, 만료 없음 유지·설정·해제, `NOT_STARTED` 409, 조정 후에도 환불 가능
  - **멱등·버전**: 변경 없는 요청은 행·감사·이벤트·version 불변, version 충돌의 `reloadUrl`·`latestVersion`, 세 유형 모두 같은 키가 같은 본문 반환, 다른 입력 충돌, null과 생략 구분, 요청자별 키 분리, 거절은 저장 안 됨, 만료된 키 재사용 409
  - **입실 중 조정**: 잔량 감소·증가·0, 만료 조정으로 이용 분 분리, 만료를 입실 전으로 옮기면 전부 초과(시간권·정액권)
  - **입퇴실 이력**: 구매·이용 지점 구분, 고객 최소 정보, 스키마 전체 필드, scope 범위와 403, 고객 조회 범위와 무관, 필터(상태·기간 경계·좌석·고객)·정렬·같은 시각의 id 순·페이지, 잘못된 필터 422, 소속 없는 직원 빈 목록, 운영 중지 지점 조회
  - **고객 내역**: 사건 순서와 각 필드, 금액 미노출, 시간권·정액권·기간권 대조(사건 합계 = 원장 합계, 잔량 = DB 값), 기간권 이용의 처리자·사유, 진행 중 이용 제외, 기간 필터는 `events`만 좁힘, 경계 `[from, to)`, 잘못된 기간 422
  - **노출 범위**: 다른 지점 이용에 지점·시각·증감량만 있고 처리자·사유·좌석·이용 기록 ID·이용 분이 없음(본문 문자열까지 확인, 권한 있는 직원과 일반 직원 모두), 요청 `scope`로 판정이 바뀜, 소유자는 전부 봄, 정확 일치 조회·이 지점 입실 뒤에도 404, 결제 지점에는 그 지점 이용권만 보임, 다른 지점에서 산 이용권의 이 지점 이용은 내역에 없음, `scope` 교차 404·403·422
- **실제 동시 실행**(요청마다 스레드를 두고 래치로 동시에 출발): 전부 통과했다.
  - 조정과 퇴실 12회: 퇴실은 항상 성공, 조정은 성공하거나 `VERSION_CONFLICT`, `USE` 1행, 잔량 = 발급량 + 원장 합계. 한쪽을 0~10ms 늦췄고, 측정 실행에서 조정 성공 2회·충돌 10회로 두 순서가 모두 나왔다.
  - 같은 version 동시 조정 8건 3회: 1건 200, 나머지 `VERSION_CONFLICT`, 원장·감사 1행
  - 같은 키 동시 조정 8건: 모두 같은 본문, 원장·감사 1행
  - 조정과 환불 12회: 환불은 항상 성공, 최종 상태는 항상 `REFUNDED`, 조정은 성공하거나 `PASS_REFUNDED`. 측정 실행에서 조정 성공 4회·거절 8회였다.
  - 조정·입퇴실 8회를 반복하는 동안 내역을 스레드 3개로 계속 읽어도 모든 응답에서 잔량 대조가 맞았다.
  - 측정용 출력은 지웠고 단언은 양쪽 결과를 모두 허용한다.

- `./gradlew test` 전체: 52개 suite, 476개 테스트(이전 415개 + 신규 61개), 실패·오류·건너뜀 0. Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다.

**한계와 실행하지 않은 것**

- 모든 명령이 공통 guard로 직렬화되므로 쓰기 경합 시험의 통과는 주로 guard의 효과다. guard 없이 이용권 행 잠금만으로 버티는지는 검증하지 않았다.
- 내역 읽기의 스냅샷 일관성은 기본 격리 수준에서 시험했다. 격리 수준을 낮춘 경우는 시험하지 않았다.
- `passes` 이벤트가 SSE로 실제 전달되는지는 시험하지 않았다. 아웃박스 행까지만 확인했다.
- 실제 서버 실행, Frontend 연결, 인프라 서버 검증, 부하 측정은 하지 않았다. 내역 응답은 페이지가 없어(계약 스키마) 사건이 아주 많은 고객의 응답 크기는 측정하지 않았다.
- `scripts/validate_foundation_design.py`는 실행하지 않았다. PR #136 기록상 `main`에서도 이미 실패한다.

## 6. 남은 작업 / 보류 사항

- 계약 문구 정리 후보(이번에 바꾸지 않음): `PassHistoryEvent`의 다른 지점 이용에서 `usedMinutes`·`seatSessionId`도 null임을 스키마 설명에 적기, `adjustPass`의 `x-error-codes`에 `INVALID_STATE` 추가.
- 입퇴실 이력·상세의 `passRemaining*`은 다른 지점에서 산 이용권의 현재 잔량도 이용 지점 직원에게 보여 준다. 스키마가 "응답 시점 잔량"을 요구하고 #62 상세부터 그렇게 동작하지만, 목록은 지난 이용까지 포함하므로 노출 범위를 다시 볼 만하다.
- `AuthorizedCommands` guard를 나누게 되면 조정의 멱등 조회와 이용권 잠금 순서를 다시 봐야 한다. 지금은 guard 아래에서 처리된다.
- 고객 상세의 타 지점 요약(POL-01)은 M6, 집계·분석은 M9다.
