# 2026-11-05 공통 집계 기반과 매출·지점 비교·최소 원장 API

관련: Issue [#87](https://github.com/OpenStudyService/OpenStudy-Backend/issues/87), [계약 문서](../design/foundation/aggregation-api.md), [ADR-M9-001](../db/ADR-M9-001.md) M9-D01~D03·D05·D07·D09, [계약 기록](2026-11-05-m9-aggregation-contract.md), [M10 정책](../db/ADR-M10-001.md) M10-D05·D06.

## 1. 배경 / 문제

#86에서 고정한 M9 계약 중 #87 몫인 `GET /sales/revenue`, `GET /sales/revenue/branches`, `GET /sales/ledger`를 구현해야 했다. `modules/sales`에는 구조 설명만 있었다. 이번 작업의 요구는 세 가지였다.

- 기간·지점 권한·환불 기준·메타·빈값 표현을 한 곳에서 처리해, #88(판매량·순위·추세)이 같은 기반 위에 바로 올라갈 수 있어야 한다.
- M10이 HTTP 없이 같은 조건 객체(`AggregationCondition`)로 호출해도 같은 검증과 권한 판정을 받아야 한다(M10-D05·D06).
- 매출 합계·지점 비교·원장·메타가 같은 조건에서 서로 맞아야 한다. 환불은 원결제 지점에서, 환불 기준별로 다른 구간에서 차감한다.

## 2. 검토한 선택지

- **원장 조회 방식**
  - 승인·환불을 엔터티로 읽어 애플리케이션에서 합치고 정렬·페이지: 기간 안의 원거래를 모두 메모리로 읽어야 한다. 계약의 "한 응답에 원거래 전체를 담지 않는다"와 맞지 않는다.
  - 승인 항목과 환불 항목의 `UNION ALL`에 정렬·`LIMIT/OFFSET`을 거는 native SQL: 페이지만 읽는다. 이쪽을 택했다. `PersistenceAccessTests`에 따라 native SQL은 `SalesSourceRepository`에만 둔다.
- **합계 계산 위치**
  - 원장 행을 모두 읽어 더하기와 DB `GROUP BY`(지점·용도별 `COUNT`·`SUM`)를 비교했다. 후자를 택했다. 합계·지점 비교·용도별 소계·원장 `totals`가 모두 같은 그룹 결과에서 나오므로 서로 어긋날 수 없다.
- **원장 시각 읽기**: native 스칼라 결과의 `DATETIME`은 드라이버·JVM 시간대에 따라 `Timestamp` 해석이 달라질 수 있다. `DATE_FORMAT(..., '%Y-%m-%dT%H:%i:%s.%fZ')`로 UTC 문자열을 받아 `Instant.parse`하는 방식을 택했다.
- **M10 경계의 권한 입력**: 호출자가 `AuthorizationState`를 넘기는 방안은 호출자가 넓은 상태를 만들어 넘길 수 있다. 요청자 직원 ID만 받고 서비스 안에서 `AuthorizationService.current`로 현재 권한을 읽는 방안을 택했다.

## 3. 결정 및 이유

- **구조**
  - `dto/request/AggregationCondition`: 요청 쪽 조건(지점 목록·from·to·환불 기준·용도·상품·분류·구간 단위, null = 생략). controller와 M10이 같은 객체를 만든다.
  - `service/AggregationConditions.resolve(requester, condition, target, trend)`: 공통 조건 규칙 전부. 연산이 받지 않는 필터(매출: `productId`·`categoryCode`·`granularity`, 이용권: `purpose`) 422 → 지점 목록 형식(빈 목록·중복 422) → 기간(한쪽만 422, `from ≥ to` 422, 31,622,400초 초과 422 `PERIOD_TOO_LONG`) → 요청자 현재 권한 읽기 → `asOf` → 기본 기간 → `READ_SALES` 판정(하나라도 없거나 없는 지점이면 403, 생략 시 권한 지점 전체, 없으면 403). 결과는 `AggregationScope`다.
  - `service/SalesRevenueService`: 세 operation의 서비스 경계. M10은 이 public 메서드를 조건 객체로 호출한다. 권한 판정이 이 경계 안에 있어 HTTP를 거치지 않아도 우회되지 않는다.
  - `repository/SalesSourceRepository`: 승인 항목·환불 항목의 WHERE 조각을 한 곳에서 만들고 합계(`totals`)와 원장 페이지(`ledger`)가 같은 조각을 쓴다.
  - `service/RevenueTally`, `service/AggregationMetas`: 지점×용도 셀의 정확한 합(`Math.addExact`)과 공통 메타·지표 목록.
- **지표 정의**: 승인 항목은 `payments.approved_at`이 `[from, to)`인 행(`approved_at IS NOT NULL` = 승인 결제). 환불 항목은 `refunds` ⨝ `payments`에서 처리일이면 `refunds.refunded_at`, 원결제일이면 `payments.approved_at`이 `[from, to)`인 행. 지점 조건은 항상 `payments.branch_id`다. `pass_balance_events`·`seat_sessions`는 읽지 않는다. `SUM`의 DECIMAL은 `longValueExact`로 바꿔 넘치면 실패한다.
- **한 읽기 트랜잭션**: 세 메서드는 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`다. 첫 읽기(요청자 권한)가 InnoDB 일관된 읽기 snapshot을 열고, 그 뒤 `asOf`를 잡고, 합계·페이지·메타를 같은 snapshot에서 계산한다. 격리 수준을 명시해 서버 기본값이 바뀌어도 유지된다.
- **원장 정렬**: `basisAt` → 종류(승인 0, 환불 1) → 원천 ID, 모두 `direction`. 원장 `meta.total`과 `sourceRowCount`는 같은 그룹 합계의 건수이며 `entryType`이 있으면 그 종류만이다.
- **지점 비교 정렬**: 금액 정렬은 같은 값이면 `branchId`를 같은 방향으로. `requested`는 보낸 순서(생략 시 ID 오름차순)이고 `direction`을 무시한다.
- **로컬 샘플**: `LocalPreviewController`에 `/api/sales` 경로가 없어 겹치지 않는다. 정리할 것이 없었다.

계약이 정하지 않아 판단한 점:

- **오류 판정 순서**: 입력 형식·기간 오류(422)를 권한(403)보다 먼저 판정한다. 권한 판정에 DB 읽기가 필요하고, 다른 목록 API도 `ListQuery.parse`를 서비스 호출 전에 한다.
- **`PERIOD_TOO_LONG`의 본문**: 기존 422처럼 `fieldErrors.to`를 담는다(`ApiException.invalid` 추가).
- **한쪽 기간만 보낸 경우의 필드**: 빠진 쪽(`from` 또는 `to`)을 `fieldErrors`에 담는다.
- **마이크로초보다 정밀한 시각**: `DATETIME(6)`에 저장된 시각만 비교되므로 다음 마이크로초로 올려 적용하고 메타에도 그 값을 돌려준다(선택되는 행은 같다). 366일 판정은 올리기 전 값으로 한다.
- **`branchIds` 형식**: 반복 query만 받는다. `branchIds=1,2`나 빈 값은 422다.
- **매출 operation의 `productId`·`categoryCode`**: 계약대로 422다. `granularity`·`measure` 같은 다른 query는 HTTP에서는 다른 목록 API처럼 무시하고, 조건 객체로 `granularity`를 넘기면 서비스가 422로 거부한다.
- **승인 결제 조건**: 지표 정의대로 `approved_at`만 본다. `status IN ('APPROVED','REFUNDED')`를 더하지 않았다. 둘을 묶는 DB 제약이 없어, 둘이 다를 때 정의와 다른 값이 나오지 않게 하기 위해서다(인덱스 관점은 #89에 남김).

## 4. 구현 내용

- `modules/sales`: controller 1개(`SalesController`), 요청 DTO·열거형 6개, 응답 DTO 11개, 서비스 쪽 클래스 5개, repository 1개. 빈 디렉터리의 `.gitkeep`을 지웠다(`entity`는 그대로).
- `build.gradle`: test 작업의 heap을 1 GiB로 올렸다(5절 참조).
- `common/exception/ApiException.invalid(code, message, field, fieldMessage)`: 자체 code를 쓰면서 `fieldErrors`를 담는 422.
- 계약 문서: `openapi.json`에서 세 operation을 `implemented`로 바꾸고 `info.description`에 "#87에서 구현됐다"를 더했다. `aggregation-api.md`의 구현 상태 문장과 로컬 샘플 절의 `modules/sales` 설명을 고쳤다. `AggregationContractTests`에 구현 상태 시험을 더했다.
- 시험(`src/test/java/com/openstudy/modules/sales`)
  - `SalesIntegrationSupport`: 계약 "계산 예시"를 고정 시계(`MutableClock`)로 만든다. P1·P3·P4(실패)·P6·P7과 P1·P6 환불은 실제 결제·환불 API, P2(룸 결제·예약 취소 환불)는 예약 모듈이 쓰는 `PaymentService.chargeRoom`·`refundRoom`, P5(사물함)는 SQL 직접 삽입이다. P6 환불의 `processing_branch_id`는 SQL로 지점 2로 바꾼다.
  - `SalesRevenueIntegrationTests`: 계산 예시 표 8행·용도별 소계, 용도 필터, 실패 결제·이용권 조정 이벤트 제외, 저장 값과 지표 정의 대조, 처리 지점이 다른 환불, 지점 비교 정렬과 0 행, 값이 있는 0과 데이터 없음.
  - `SalesLedgerIntegrationTests`: 11월 원장(두 기준의 항목·순서·`basisAt`), 응답 필드 집합 = 계약 schema, 고객·결제 수단·처리자·사유 부재, 페이지·`entryType`·`canViewPayment`, 4기간×2기준×4용도×3지점 조합 96개에서 매출·지점 비교·원장 정합성.
  - `SalesConditionIntegrationTests`: 기본 기간(11-05와 1일), 자정 전후·`[from, to)` 경계(1마이크로초), 365·366일·윤년 200, 366일+1초·+1마이크로초 422 `PERIOD_TOO_LONG`, 형식 오류 422, 권한 밖 지점·없는 지점 403과 숫자 비노출, 생략 시 권한 지점만, 운영 중지 지점 열람, OWNER, M10 경계(서비스 직접 호출 결과 = HTTP 응답, 권한 밖·기간 초과·다른 필터 거부), 응답 필드 집합 = 계약 schema.
  - 모든 sales 시험은 고정 시계와 `SalesSourceRepository` spy(기본은 실제 호출)를 가진 같은 Spring context 하나를 쓴다.
  - `SalesSnapshotIntegrationTests`: 합계를 읽은 뒤 다른 연결이 결제를 커밋해도 같은 응답의 원장·`meta.total`·`sourceRowCount`·합계가 서로 맞고, 다음 요청에서 보인다.

## 5. 검증 결과

- 관련 시험: `./gradlew test --tests 'com.openstudy.modules.sales.*' --tests 'com.openstudy.architecture.*'` 통과(전체 실행 전).
- 일관된 읽기 시험의 유효성 확인: 원장 메서드의 격리 수준을 임시로 `READ_COMMITTED`로 바꾸면 `SalesSnapshotIntegrationTests`가 실패하는 것을 확인하고 되돌렸다.
- 계약 계산 예시: 매출 표 8행, 11월 처리일 용도별 소계(27,000/10,000/17,000 · 6,000/6,000/0 · 20,000/0/20,000), 원결제일 이용권 소계(27,000/12,000/15,000), 지점 비교(지점 1 37,000 `hasData`, 지점 2 0 `noData`, totals 53,000/16,000/37,000), 11월 원장(처리일 6항목 합 37,000, 원결제일 6항목 합 35,000, `meta.total = sourceRowCount = 6`)을 그대로 재현했다. 이용권 지표 표·주 추세 표는 #88 몫이라 API로 검증하지 않았다(원장의 `passQuantity`로 10월 원결제일 판매 1·환불 1만 확인).
- 전체 시험 `./gradlew cleanTest test`
  - 1회차(실패): 시험 JVM이 `OutOfMemoryError: Java heap space`로 중단됐다. 실패한 시험 단언은 없었다(XML 79개 suite, 947개, 실패·오류 0, 건너뜀 1 — 중단된 실행이라 수치가 불완전하다). 이번 시험이 고정 시계 context와 spy context 2개를 더해 캐시된 Spring context가 늘어난 것이 원인으로 판단했다.
  - 조치 1: spy를 `SalesIntegrationSupport`로 옮겨 sales 시험 전체가 context 하나를 쓰게 하고 `@DirtiesContext(AFTER_CLASS)`를 붙였다. 이 상태의 관련 시험 실행 중 한 번 `SalesLedgerIntegrationTests` 4개가 CSRF 발급 응답에 `token`이 없어 실패했고, 같은 시험을 두 번 다시 돌렸을 때는 모두 통과했다. 원인은 확인하지 못했다(로그인 helper가 이제 CSRF 응답 상태를 단언해 재발 시 본문이 남는다).
  - 2회차(실패): 42분 56초, 79개 suite, 955개 중 실패 10(`WorkspaceConnectionIntegrationTests`·`WorkspaceIntegrationTests`의 context 적재 실패, 원인은 같은 heap 부족). context를 닫아도 기존 suite만으로 512 MiB(Gradle 기본값)에 가까웠다고 판단했다. 2026-11-04 #84 전체 실행(74개 suite)은 같은 설정으로 통과했다.
  - 조치 2: `build.gradle`의 test 작업에 `maxHeapSize = '1g'`를 더하고 `@DirtiesContext`는 뺐다(공유 context 하나는 유지).
  - 3회차(최종): **BUILD SUCCESSFUL, 40분 49초. 79개 suite, 955개 테스트, 실패 0·오류 0·건너뜀 0.** Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 이 실행 뒤에는 이 기록만 바꿨다.
  - 주의: 3회차 실행 직전(17:16 무렵)부터 이 작업과 무관한 변경이 작업 트리에 생겼다(`LocalPreviewData`, `modules/auth`의 `AuthController`·`CurrentUser`·`LoginRequest`·`CurrentUserService`, `modules/employee`의 `EmployeeAccount`). 3회차의 컴파일 결과(17:18:34)에 그중 일부(적어도 `LocalPreviewData`의 `getCurrentUser` 호출과 그에 맞는 `CurrentUserService`)가 들어갔을 수 있다. 이 파일들은 건드리거나 커밋하지 않았다. 따라서 3회차 결과는 "이번 커밋 + 그 시점의 외부 변경" 트리의 결과이며, 이번 커밋만의 트리로 전체 시험을 다시 돌린 것은 아니다.
- `scripts/validate_foundation_design.py`는 `requirements.json` 확인 단계(26행)에서 이번 변경과 무관한 assert로 실패한다. 이번 작업에서 그 파일을 바꾸지 않았다.
- 미검증: 실제 운영 DB 규모의 실행 계획(EXPLAIN), 금액 합계 오버플로 경로(DB 제약상 시험 데이터로 만들기 어렵다), Frontend 연동.

## 6. 남은 작업 / 보류 사항

- **#88**: `AggregationConditions.Target.PASSES`·`trend`와 `AggregationMetas.of`의 구간 플래그를 쓴다. 이용권 operation의 `condition.purpose`는 항상 `이용권`이어야 하므로 #88에서 메타 조건을 그렇게 채워야 한다(현재 `AggregationMetas.condition`은 scope의 purpose를 그대로 쓴다). 수량은 `issued_pass_quantity`, 발급 건수는 `passes` 행 수(원결제 `approved_at` 기준), 상품 행은 `passes` 스냅샷 열이다. `SalesSourceRepository`의 WHERE 조각을 재사용한다.
- **#89**: 실행 계획 확인 대상
  - 승인 항목: `branch_id IN (...) AND approved_at` 범위. `status` 조건이 없어 `ix_payments_1 (branch_id, status, approved_at)`의 앞 열만 쓰일 수 있다. `status IN` 추가와 `(branch_id, approved_at)` 인덱스 중 무엇이 맞는지 실행 계획으로 정해야 한다.
  - 환불 처리일: `refunds.refunded_at` 범위(`ix_refunds_1`) + `payments` PK 조인 후 지점 조건.
  - 환불 원결제일: `payments` 범위 후 `uk_refunds_1` 조인.
  - 원장: `UNION ALL` 결과 전체를 `basis_at`으로 정렬한 뒤 `LIMIT/OFFSET`이라 기간 안 항목 수만큼 정렬하고, 뒤 페이지일수록 비싸다.
  - 이번에는 인덱스를 추가하지 않았다.
- 시험 JVM heap: 기본 512 MiB로는 전체 suite가 부족했다. 1 GiB로 올렸지만 context 수가 더 늘면(#88·#90) 다시 한계에 닿을 수 있다. context를 나누는 시험 설정(전용 Clock·spy bean)을 줄이는 정리가 필요할 수 있다.
- `openapi.json`을 바꿨지만 `OpenStudy-Foundation-API.xlsx`는 이전 구현 PR들처럼 다시 만들지 않았다.
