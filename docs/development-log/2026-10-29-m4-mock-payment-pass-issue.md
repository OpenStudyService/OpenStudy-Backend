# 2026-10-29 M4 Mock 결제·이용권 발급·구매 조건 보존

관련: Issue [#57](https://github.com/OpenStudyService/OpenStudy-Backend/issues/57), [계약 문서](../design/foundation/customer-sales-api.md), [ADR-M4-002](../db/ADR-M4-002.md) M4-D01~D03·D07, [ADR-M3M4-001](../db/ADR-M3M4-001.md) D01·D02, [#56 고객 기록](2026-10-29-m4-customer-management.md).

## 1. 배경 / 문제

고객(#56)과 상품(#16)은 있었지만 판매 흐름이 없었다. 결제와 이용권 발급은 한쪽만 남으면 안 되므로 한 트랜잭션으로 묶어야 한다. 금액은 서버가 산정해야 하고, 이용권에는 구매 당시 조건이 남아야 한다(FR-PAY-02, FR-PASS-03). `payment` 모듈은 빈 골격이었고, `ticket` 모듈에는 이용권 엔티티가 없었다.

## 2. 검토한 선택지

- **이용권 코드의 위치**
  - `payment` 모듈에 결제와 이용권을 함께 둔다: 트랜잭션은 단순하지만, M5 입실·차감이 결제 모듈 내부를 직접 다뤄야 한다.
  - `ticket` 모듈("상품과 보유 이용권")에 이용권을 두고, 결제 서비스가 `PassService.issue`를 호출한다: 모듈 경계 규칙(서비스 진입점만 호출)을 지키고, M5도 `ticket`만 보면 된다.
- **구매 Revision 찾기**
  - `ProductService.requireSellable`의 반환형을 바꾼다: 기존 테스트와 호출부가 바뀐다.
  - `sellable`(새 진입점)이 스냅샷과 현재 version의 revision id를 함께 돌려주고, `requireSellable`은 그 스냅샷만 돌려준다: 기존 계약을 유지한다.
- **공통 경로 가드(`AuthorizationWebConfiguration`)와 결제 생성**
  - 가드는 `/api/payments/**` 전체에 "어느 지점이든 `payments:read`"를 요구하고 있었다. 그대로 두면 `payments:write`만 받은 직원은 계약상 허용된 결제 생성도 403을 받는다.
  - POST만 `payments:write`로 판정하도록 바꾼다: 계약의 권한 표("`branchId` 지점의 `payments:write`")와 맞는다.

## 3. 결정 및 이유

- **이용권은 `ticket`, 결제는 `payment`에 둔다.** 결제 서비스는 고객(`CustomerService.requirePurchasable`), 상품(`ProductService.sellable`), 이용권(`PassService.issue`), 직원 이름(`EmployeeAccountService.names`)을 각 모듈의 서비스 진입점으로만 호출한다.
- **트랜잭션은 `AuthorizedCommands.inBranches` 하나로 감싼다.** 진입점은 모두 `@Transactional`(기본 전파)이라 같은 트랜잭션에 참여한다. 순서는 계약 표와 같다: 권한 403 → 멱등 재생 → 지점 운영 409 → 고객(공유 잠금) 404/409 → 상품(공유 잠금) 404/409 → 결제 행 → 이용권 행 → 감사·이벤트 → 멱등 결과.
- **지점 운영 중지는 403이 아니라 409 `BRANCH_NOT_ACTIVE`다.** `BranchAction.NEW_PAYMENT`는 운영 중지 지점을 권한 실패(403)로 처리하므로, 같은 `payments:write`를 쓰되 운영 여부를 따로 보는 `WRITE_PAYMENT_HISTORY`로 판정하고 운영 여부는 직접 확인했다.
- **결제 `idempotency_key` 열에는 서버가 만든 값을 저장한다.** `PAY-` + SHA-256(요청자·operation·지점·클라이언트 키)이다. 계약대로 다른 직원이 같은 키를 써도 충돌하지 않고 120자 제한을 지킨다.
- **시각은 마이크로초로 자른 `Clock` 시각 하나를 승인 시각과 이용권 시작 시각에 같이 쓴다.** DB `DATETIME(6)`과 응답이 같은 값을 보이고, `started_at = approved_at`이 정확히 성립한다.
- **이용권 상태 응답과 상태 검색은 같은 판정식을 쓴다.** 환불 > 만료(`expires_at <= now`) > 소진 > 활성이다. 저장 상태가 ACTIVE여도 만료 시각이 지나면 만료로 보이고, 만료로 검색된다(FR-PASS-11).
- **사용 여부는 이력으로 판정한다.** `seat_sessions` 또는 `pass_balance_events`의 `USE` 행이 있으면 사용한 것이다(ADR-M4-002). `canRefund`도 이 판정을 쓴다.
- **환불 응답 필드(`refund`)는 항상 null로 둔다.** 환불은 #58 범위이고, 이번 변경으로는 `REFUNDED` 상태가 생길 수 없다.

## 4. 구현 내용

- `ticket`
  - `entity/Pass`, `PassStatus`: 발급 팩토리 `Pass.issue`가 M4-D01~D03의 시작·만료 규칙을 적용한다. `NOT_STARTED`는 만들지 않는다.
  - `repository/PassRepository`(사용 이력 조회), `PassSearchRepository`(구매 지점 범위·고객·실효 상태·유형 필터, 만료 없음은 정렬 끝)
  - `service/PassService`(발급·조회·결제별 사용 상태), `controller/PassController`(`GET /api/passes`, `/api/passes/{id}`)
  - `ProductService.sellable`과 `dto/SaleTerms`, `ProductRevisionRepository`에 현재 version revision 조회 추가
- `payment`
  - `entity/Payment` 및 enum, `repository/PaymentRepository`, `PaymentSearchRepository`(상태·기간 `[from, to)`·고객 필터)
  - `service/PaymentService`, `controller/PaymentController`(`POST/GET /api/payments`, `GET /api/payments/{id}`), 응답 DTO
- `customer`: `requirePurchasable`(공유 잠금 + 조회 범위 + 정상 상태), `summaries`, `CustomerSummary`
- `employee`: `EmployeeAccountService.names`
- 공통
  - `AuthorizationWebConfiguration`: `POST /api/payments/**`는 `payments:write`로 판정한다.
  - `WorkspaceEventService`: `payments`(구매 지점 `payments:read` 보유자에게만)·`passes` 이벤트를 전달한다.
- 문서: OpenAPI의 결제 3개·이용권 2개 operation을 `implemented`로 바꾸고, 결제 상세에 403을 추가했다. 계약 문서에 결제 조회 가드 동작을 적었다.

## 5. 검증 결과

- `PaymentIntegrationTests`(Testcontainers MySQL, HTTP·DB 통합) 13개 통과
  - 승인 결제: 서버 가격 산정, 이용권 1장, 고객·지점·결제·구매 revision 추적, `started_at = approved_at`, 만료 = 시작 + 일수 × 24시간, 감사·이벤트
  - 유형별 발급: 기간권·유효기간 있는 정액권은 만료가 있고, 유효기간 없는 시간권·정액권은 만료 없음(null). `NOT_STARTED`는 0건, 만료 없음은 정렬 끝
  - 실패 결제: `FAILED`, `issued_pass_quantity = 0`, 이용권 없음
  - 금액·상품명·수량·상태 필드와 잘못된 수단·결과값, 멱등 키 누락은 422
  - 판매 중지 상품, 판매 지점이 아닌 지점, 이용 제한·비활성 고객, 범위 밖 고객(404), 권한 없음(403), 운영 중지 지점(409)은 거부되고 결제 행이 남지 않음
  - 같은 키 재전송은 같은 결과, 다른 입력은 409, 다른 직원의 같은 키는 별도 거래, 클라이언트 키 원문은 DB에 저장되지 않음
  - 같은 키 동시 요청 4개에서 결제·이용권 1건씩만 생성
  - 발급 단계 강제 실패(`PassService.issue` 예외 주입): 500 응답, 결제·이용권·멱등 결과·감사가 모두 남지 않음. 이후 같은 키로 정상 구매 가능
  - DB 제약: 이미 발급된 결제에 이용권 추가, 다른 고객·다른 지점 조합은 `uk_passes_1`·`fk_passes_3`로 거부
  - 상품 가격·조건·분류명을 바꾼 뒤에도 기존 결제 금액·상품명과 이용권 조건이 바뀌지 않고, 다음 판매는 새 가격·새 revision을 씀
  - 실효 상태: 저장 상태가 ACTIVE여도 만료 시각이 지나면 만료로 응답·검색되고, USE 이력이 있으면 `used=true`·`canRefund=false`
  - 조회 범위: 이용권은 구매 지점 소속이면 읽기 가능, 다른 지점은 404. 결제는 `payments:read`가 없으면 403, 다른 지점 권한만 있으면 404. `payments:write`만 가진 직원은 판매는 가능하고 결제 조회는 403
- 고객·상품 통합 테스트(23개)도 변경 후 다시 통과했다.
- 전체 테스트(`./gradlew test`, 43개 클래스 283개): 278개 통과, `LocalPreviewIntegrationTests` 5개 실패
  - 첫 실행에서는 앱이 기동하지 않았다. `payments.issued_pass_quantity`가 `smallint unsigned`라 `ddl-auto: validate`가 엔티티의 `int`를 거부했다. 열 타입을 명시해 해결했다.
  - LocalPreview 실패는 #56 기록과 같은 원인이다. 개인 `.env`(git 무시)의 `openstudy.local-preview.enabled=false` 때문이며, 이번 변경과 무관하다.
- 미검증
  - 만료 경계의 정확히 같은 시각(`now == expires_at`): 시계를 고정하지 않아 직전·직후(1초 전 만료, 30일 뒤 만료)로만 확인했다.
  - Frontend 연동, SSE로 결제·이용권 이벤트가 화면에 도착하는지(매핑만 추가했다)

## 6. 남은 작업 / 보류 사항

- 환불(#58): `refunds` 엔티티, 결제·이용권 `REFUNDED` 전이, 응답 `refund` 채우기. `PassService.byPayments`와 `PassUsage.refundable()`을 판정에 재사용할 수 있다.
- `CustomerRepository.paymentBranchIds`와 `CustomerSearchRepository`는 여전히 `payments` 테이블을 직접 읽는다. 결제 모듈이 생겼으므로 진입점으로 옮길지 정해야 한다. 고객 → 결제 → 고객의 순환 의존이 생기므로 이번에는 옮기지 않았다.
- 결제 목록의 고급 필터(수단·분류·처리자)는 M13, 잔량 차감은 M5, 매출 집계는 M9다.
