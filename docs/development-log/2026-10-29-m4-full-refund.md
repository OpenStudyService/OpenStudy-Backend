# 2026-10-29 M4 미사용 승인 거래 전액 환불

관련: Issue [#58](https://github.com/OpenStudyService/OpenStudy-Backend/issues/58), [계약 문서](../design/foundation/customer-sales-api.md) "환불"·"트랜잭션 경계", [ADR-M4-002](../db/ADR-M4-002.md) M4-D04·D08과 "미사용·만료 판정", [#57 결제·발급 기록](2026-10-29-m4-mock-payment-pass-issue.md).

## 1. 배경 / 문제

#57로 Mock 결제와 이용권 발급은 생겼지만 되돌릴 방법이 없었다. 결제 응답의 `refund`는 항상 null이었고 결제가 `REFUNDED`에 도달할 경로도 없었다. 환불은 원결제를 덮어쓰지 않는 별도 사건이어야 하고(FR-PAY-10), 실제로 쓰지 않았고 만료되지 않은 이용권만 대상이어야 한다(M4-D04). 저장 상태가 `ACTIVE`로 남은 만료 이용권도 처리 시각 기준으로 거부해야 한다(NFR-DATA-05).

## 2. 검토한 선택지

- **환불 권한이 없는 사용자의 응답(403과 404)**
  - 결제 상세와 같이 `payments:read`가 있어야 "보인다"고 본다: 판매만 하는 계산원(`payments:write`만 보유)이 자기가 판 결제를 환불하지 못한다. 결제 생성에서 이미 허용한 "장부를 읽지 않는 판매·환불" 흐름과 어긋난다.
  - 원결제 지점의 유효 소속을 가시성 기준으로 본다: 소속이 있으면 권한 부족을 403으로 알리고, 소속이 없으면 404로 결제 존재를 숨긴다.
- **잠금 위치**
  - 결제 행을 `AuthorizedCommands`의 업무 부모 잠금(권한 재조회 전)에서 잠근다: 고객 수정과 같은 형태다.
  - 계약 표의 순서(멱등 잠금 → 결제 행 → 이용권 행)를 그대로 따른다. 결제 지점은 바뀌지 않는 열이므로 권한 판정 전에 결제를 잠글 필요가 없다.

## 3. 결정 및 이유

- **가시성은 원결제 지점의 유효 소속으로 판정한다.** 소속이 없으면 404, 소속은 있지만 그 지점의 `payments:write`가 없으면 403이다. 그래서 `payments:read`만 있거나 권한이 없거나 다른 지점에만 `payments:write`가 있는 사용자는 403을 받는다(인수 조건). 계약 문서의 "결제가 보이지 않으면 404"가 무엇을 뜻하는지 이 기준으로 명확히 적었다.
- **잠금은 계약 표의 순서를 따른다.** 콜백 안에서 멱등 잠금 → 결제 `FOR UPDATE` → 이용권 `FOR UPDATE`를 잡고, 상태·사용 이력·만료를 모두 잠금 뒤에 다시 확인한다. `AuthorizedCommands`의 가드 잠금이 모든 명령을 직렬화하므로, 서로 다른 키로 동시에 환불해도 첫 요청만 성공한다. 나머지는 `ALREADY_REFUNDED`를 받는다. `uk_refunds_1`(결제당 환불 1건)이 마지막 방어선이다.
- **검사 순서**: 결제 `REFUNDED` → `ALREADY_REFUNDED`, 승인 아님 → `PAYMENT_NOT_REFUNDABLE`, 사용 이력 있음 → `PASS_ALREADY_USED`, `expires_at ≤ 처리 시각` → `PASS_EXPIRED`. 사용 이력이 있고 만료도 된 이용권은 사용 이력이 우선한다. 계약 표의 순서를 따랐다.
- **이용권 쪽 전이는 `PassService.refund`에 둔다.** 결제 모듈은 이용권 entity·repository를 직접 만지지 않는다는 모듈 규칙(#57과 같음)을 지키기 위해서다. 사용 이력 판정 쿼리도 #57의 `used`·`canRefund`와 같은 것을 써서 화면 힌트와 서버 판정이 어긋나지 않게 했다.
- **멱등 키 저장값**: `refunds.idempotency_key`에는 `RFD-` + sha256(요청자·operation·결제 ID·키)를 저장한다. 결제 생성의 `PAY-` 키와 같은 방식이고, 공통 멱등 범위는 (요청자, `payments:refund`, 결제 ID, 키)다.

## 4. 구현 내용

- `POST /api/payments/{id}/refund`: 본문은 `reason`(1~500자)만 받는다. `amountKrw` 등 다른 필드는 422다. `Idempotency-Key`는 필수다. 응답은 200과 갱신된 `Payment`이며 `refund`에 환불 사건이 담긴다.
- `payment` 모듈
  - `Refund` entity·`RefundRepository`를 추가했다. 처리 지점은 원결제 지점, 금액은 원결제 `amount_krw`로 고정한다.
  - `Payment.refund()`는 상태만 `REFUNDED`로 바꾼다. 금액·승인 시각은 원래 `updatable = false`다.
  - `PaymentRepository.lock`(`FOR UPDATE`)을 추가했다.
  - `PaymentService.refund`가 환불 행, 결제 전이, 감사(`REFUNDED`, 이전·이후 상태), `payments` 이벤트, 멱등 결과를 기록한다.
  - 결제 응답(목록·상세·생성·환불)이 `refunds`를 함께 읽어 `refund`를 채운다. 처리자 이름은 결제 생성자와 함께 한 번에 조회한다.
- `ticket` 모듈
  - `PassService.refund`는 이용권을 잠그고 사용 이력과 만료를 판정한다. 그다음 `REFUNDED`로 전이하고 감사와 `passes` 이벤트를 남긴다.
  - `Pass.refund()`는 `started_at`·`expires_at`을 바꾸지 않는다. 기간권 `REFUNDED`는 `ck_passes_period_activation`이 시작·만료 시각 보존을 허용한다.
  - `PassRepository`에 `findByPurchasePaymentId`와 `lockByPayment`를 추가했다.
- 경로 가드는 #57에서 `POST /api/payments/**`를 `payments:write`로 판정하도록 바꿔 두었으므로 이번에 추가로 바꾸지 않았다.
- 문서
  - OpenAPI `refundPayment`를 `implemented`로 바꿨다.
  - 계약 문서의 구현 현황과 403/404 기준을 고쳤다.
  - `payment`·`ticket` package-info의 구현 범위를 갱신했다.

## 5. 검증 결과

`PaymentIntegrationTests`(MySQL Testcontainers, HTTP)에 환불 테스트 5개를 추가했다. 결제 테스트 18개가 모두 통과했다.

- 성공한 환불: 200, 상태 `환불`, `refund`(금액 25,000·사유·처리 지점=원결제 지점·처리자)가 응답에 담긴다.
  - 원결제의 `amount_krw`·`approved_at`은 그대로다. 이용권의 `started_at`·`expires_at`(기간권)도 그대로다.
  - `refunds` 1행, 결제·이용권 감사 `REFUNDED` 각 1건, 결제 이벤트(발급 + 환불)가 기록된다.
  - 이용권 조회·상태 검색이 `환불`을 보이고 `canRefund=false`다.
- 입력: `amountKrw` 포함, 빈 사유, 501자 사유, 멱등 키 누락은 모두 422이고 환불 행이 생기지 않는다.
- 거절
  - 실패 결제 → `PAYMENT_NOT_REFUNDABLE`.
  - `USE` 기록이 있는 이용권 → `PASS_ALREADY_USED`.
  - 저장 상태가 `ACTIVE`이고 만료 1ms 지난 이용권 → `PASS_EXPIRED`.
  - 이 세 경우 환불 행과 `REFUNDED` 이용권이 생기지 않는다.
  - 만료 60초 전 이용권은 환불된다. 같은 결제를 새 키로 다시 환불하면 `ALREADY_REFUNDED`다.
- 권한
  - `payments:read`만 가진 사용자, 권한이 없는 사용자, 원결제 지점에는 `payments:read`만 있고 다른 지점에 `payments:write`가 있는 사용자는 403이다.
  - 원결제 지점 소속이 없는 다른 지점 판매자와 없는 결제는 404다.
  - `payments:write`만 가진 계산원과 OWNER는 환불할 수 있고, 처리 지점은 원결제 지점으로 기록된다.
- 멱등·동시성
  - 같은 키·같은 사유는 같은 `refund.id`를 돌려준다. 같은 키·다른 사유는 `IDEMPOTENCY_CONFLICT`다. DB 키는 원문이 아니다.
  - 서로 다른 키 4개로 동시에 환불하면 1건만 성공하고, 환불 행과 감사는 각 1건이다.
- 원자성: `PassService.refund`에서 예외를 주입하면 500이 된다. 결제는 `APPROVED`, 이용권은 `ACTIVE`로 남고, 환불 행과 멱등 결과는 생기지 않는다. 장애를 걷어낸 뒤 같은 키로 환불하면 성공한다.
- 전체 테스트(`./gradlew test`): 288개 중 283개 통과. 실패 5개는 `LocalPreviewIntegrationTests`다. 원인은 로컬 `.env`의 `openstudy.local-preview.enabled=false`이며, #56·#57 기록과 같은 환경 원인으로 이번 변경과 무관하다. `.env`는 사용자 개인 설정이라 바꾸지 않았다. 모듈 경계·native SQL 위치 아키텍처 테스트는 통과했다.

### 검증하지 못한 것

- **`seat_sessions` 기반 사용 이력**: 좌석 세션을 만들려면 좌석·구역 등 M5 데이터가 필요해 테스트에서 만들지 않았다. 판정 쿼리는 #57의 `used` 판정과 같은 `EXISTS` 절을 쓰지만, 이 경로 자체는 실제 행으로 확인하지 않았다.
- **만료 시각과 같은 순간**: 시계를 고정할 수 없어 만료 1ms 후와 60초 전으로만 확인했다. 코드상 경계는 `now ≥ expires_at`이면 만료다.
- 프론트엔드 연동과 SSE가 화면에 도착하는지는 확인하지 않았다.

## 6. 남은 작업 / 보류 사항

- 타 지점 환불(처리 지점 ≠ 원결제 지점)은 M4-D08에 따라 후속이다. 테이블은 이미 두 지점을 분리해 저장할 수 있다.
- 스터디룸·사물함 결제 환불은 M6이다.
- 고객 조회 범위 계산이 `payments` 테이블을 직접 읽는 문제(#57 기록)는 그대로다.
