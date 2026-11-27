# 2026-11-27 sales 모듈 가독성 정리

## 1. 배경 / 문제

#250의 `sales, workforce` 묶음 중 첫 번째 모듈이다. sales 모듈(50개 파일, 약 2,800줄)은 다음을 담당한다.

- 매출 요약·지점 비교·원장, 이용권 판매량·상품 순위·추세(M9 집계)
- 이용 지점별 이용권 사용량
- CSV 내보내기
- 일별 마감
- AI 분석이 읽는 집계 모양(`SalesAggregationService`)

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`, 한 줄짜리 메서드 본문, `var`
- 한 줄에 쓴 enum·레코드·대입 여러 개
- 120자를 넘는 줄(응답 생성 10개 이상 인자, 동적 SQL 조립, 조건 문장 만들기)
- 셀 합계를 `long[3]` 배열 칸 번호(0·1·2)와 결과 행 칸 번호로 다루는 코드
- 코드 중간의 완전한 이름(`java.math.BigDecimal`, `java.util.Arrays`, `java.security.NoSuchAlgorithmException`)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `between`, `adjustments`, `sessions`, `deductions`, `query`, `marks` 등
  - 서비스 private: `purposes`, `entry`, `bucket`, `view`, `cell`, `filters`, `branches`, `bound`, `period`, `conditions`, `names`, `snapshot` 등
  - 패키지 내부: `RevenueTally.of`·`byPurpose`, `TrendBuckets.of`, `PassTally.byCodePoint`
- 주입 필드 이름이 타입과 다름(`conditions`, `sources`, `policy`, `keys`, `json`, `usageRows` 등)
- 매출 추이 메서드 하나가 매출·이용권 두 갈래 집계를 모두 담고 있음

## 2. 검토한 선택지

- 매출 추이의 두 갈래는 각각 `foldRevenue`·`foldPasses`로 나누고, 결과(구간 목록·합계·원천 행 수)를 private 레코드 `Folded`로 돌려받게 했다. 원래 지역 변수 세 개를 두 갈래에서 채우던 방식과 같은 값을 만든다.
- CSV 내보내기의 `switch` 안에 있던 보고서별 블록은 `create...Table` 메서드로 옮겼다. 보고서 키는 상수로 바꿨고, `switch`의 순서와 `default`(이용권 사용 현황)는 그대로다.
- 응답 레코드와 `AggregationScope`·`SourceFilter`는 생성 지점이 한두 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - `SalesAggregationService`의 공개 메서드·레코드·`RefundBasis.fromParam`(ai 모듈, 테스트)
  - 서비스 공개 메서드: `revenue`, `compareBranches`, `ledger`, `volume`, `products`, `trend`, `usage`, `export`, `create`, `get`, `list`
  - `AggregationConditions.resolve`·`Target`, `AggregationCondition.revenue`·`passes`, `SalesExportService.REPORTS`
  - `SalesSourceRepository`의 공개 메서드(`totals`, `dayTotals`, `passTotals`, `ledger`)와 중첩 레코드
  - `SalesController`의 경로 값(`AnalysisReuseContractTests`가 `GetMapping` 값을 읽는다)
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `DailyClosingRepository`(모두 `@Query`라 이름 변경이 안전)
    - `between` → `findBetween`, `adjustments` → `findAdjustments`, `adjustmentCounts` → `countAdjustments`
    - `approvalsByMethod` → `sumApprovalsByMethod`, `refundsByMethodProcessed`·`Paid` → `sumRefundsByMethodProcessed`·`Paid`
    - `employeeNames` → `findEmployeeNames`
  - `PassUsageRepository`: `sessions` → `countSessions`, `deductions` → `sumDeductions`, `query` → `runQuery`, `marks` → `toPlaceholders`
  - `SalesSourceRepository` private: `findGroupedTotals`, `toDayColumn`, `toApprovalFragment`, `toRefundFragment`, `appendAdvanced`, `bindAll`, `toSourceTotal` 등
  - `RevenueTally`: `of` → `findAmounts`·`sumAmounts`, `byPurpose` → `toPurposeRows`, `view` → `toView`
  - `TrendBuckets.of` → `findPosition`, `PassTally.byCodePoint` → `compareByCodePoint`
  - `AggregationMetas`: `notes` → `toNotes`, `condition` → `toConditionView`
  - `AggregationConditions` private: `validateFilters`, `parseBranchIds`, `toBound`, `validatePeriod`
  - `SalesExportService.conditions` → `toConditionText`
  - `DailyClosingService` private: `toView`, `createBasisSnapshot`, `toMethodSums`, `resolveBranches`, `findEmployeeNames`, `findReplay`, `parseDate`, `validateKey` 등
- 컨트롤러
  - `DailyClosingController`: 클래스 매핑을 `/api/daily-closings`로 올리고 메서드 이름을 operationId(`listDailyClosings`, `createDailyClosing`, `getDailyClosing`)로 바꿨다.
  - `SalesController`: 클래스 매핑 `/api/sales`와 경로 값은 그대로 두고 메서드 이름을 operationId 8개로 바꿨다. 쿼리 파라미터 해석을 `parse...` 헬퍼로 나눴다.
- 호출 순서는 그대로 둔다.
  - 조건 해석: 필터 검증 → 지점 목록 → 기간 → 권한 상태 읽기(스냅샷 시작) → asOf → 지점 권한 → 처리 지점 권한
  - 원장: 전체 건수가 offset 이하이면 원장 조회를 하지 않는 조건
  - 이용권 사용량: 판매 지점마다 권한 확인을 한 번씩 하는 원래 방식
  - 일별 마감: 재실행 확인 → 중복 확인 → 두 기준 스냅샷 → 저장 → 감사 → 응답 → 멱등 완료
  - CSV 내보내기의 페이지 반복과 지점 이름 조회 횟수
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265).

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다.
  - `long[3]` 셀의 칸 번호를 `SESSIONS`·`MINUTES`·`CREDIT` 상수로, 결과 행 칸 번호를 열 상수로 바꿨다. 셀 더하기는 `addCell`로 모았다.
  - 일별 마감 조정 행의 칸 번호와 결제 수단 합계 칸 번호를 상수로 바꿨다.
  - 201·24시간·길이 제한·366일·`SHA-256`·보고서 키·정렬 필드 등을 상수로 바꿨다.
  - 지점 비교 정렬 기준 만들기(`createRowOrder`), 원장 항목 조회(`findLedgerEntries`), 상품 순위 매기기(`toRankedRows`), 사용량 메타 만들기(`toUsageMeta`)를 메서드로 나눴다.
  - 조건 문장의 목록 조건 네 개를 `appendList`로 모았다.
- 리포지토리
  - `DailyClosingRepository`: JPQL·SQL을 text block으로 옮겼다.
  - `PassUsageRepository`: 두 SQL 틀을 상수로 뺐다.
  - `SalesSourceRepository`: 두 번 쓰던 집계 열·GROUP BY 조각을 상수로 모으고, 동적 조립을 `append...` 메서드로 나눴다. 바인딩 순서와 SQL 조각은 그대로다.
- 엔티티: 필드 어노테이션과 생성자 대입을 한 줄에 하나씩 풀었다.
- enum 6개: 상수와 생성자를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava`: 통과.
- sales `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교(`origin/main` 대비)
  - `DailyClosingRepository`·`PassUsageRepository`: 차이 없음.
  - `SalesSourceRepository`: 두 번 쓰던 집계 열(`SELECT p.branch_id, p.purpose, COUNT(*), `)·`GROUP BY p.branch_id, p.purpose`·`, g_day` 조각이 상수 하나씩으로 모인 것뿐이다. 이어 붙인 SQL을 원래 코드와 나란히 비교해 같음을 확인했다.
- sales와 이를 쓰는 모듈의 30개 테스트 클래스, 198개 테스트: 실패 0, 건너뜀 14.
  - sales 모듈 전체(11개 클래스), `PaymentIntegrationTests`(마감 조정)
  - `AnalysisReuseIntegrationTests`, `CustomerMergeIntegrationTests`, `RetentionIntegrationTests`
  - `com.openstudy.architecture` 계약·경계 테스트 14개(`AggregationContractTests`, `AnalysisReuseContractTests`, `CustomerSalesContractTests`, `OperationsExtensionContractTests` 포함)
  - 건너뜀 14는 모두 환경 변수로 켜는 테스트다: `OpenStudyGoldenAggregationsIntegrationTests` 12, `AiLiveParityIntegrationTests` 1, `SalesQueryPlanMeasurementTests` 1.
- `OpenStudySemanticCatalogTests`(3개): 로컬 모델로 실행해 통과.
- 검증하지 못한 항목: `OpenStudyGoldenAggregationsIntegrationTests`를 `OPENSTUDY_AI_LOCAL_TESTS=true`로 실행했으나, 기대값 파일 `docs/04_TEST_DATA/expected_aggregations.json`이 저장소에 없어 초기화 단계에서 실패했다. 이번 변경과 무관한 환경 문제이며, `SalesAggregationService` 경로는 AI 질의 API(`/api/ai/queries`)로 등록 매출 분석을 실행해 M9 API와 숫자를 맞춰 보는 `AiSalesEquivalenceIntegrationTests`로 확인했다.
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: workforce, 그다음 ai
