# 2026-11-08 AI 매출 분석을 M9 매출 서비스로 계산 (#95)

## 1. 배경 / 문제

- #95의 핵심 인수 조건("같은 조건에서 자연어 결과와 M9 정형 API의 수치·단위가 같다")을 일반 테스트로 옮기다가 수치가 다른 것을 발견했다(#186 개발 기록).
- AI 매출 분석은 DomainLens 이식(#169) 때 들어온 `SalesAggregationService`의 자체 쿼리를 썼다. 대상은 다음 분석과 결과다.
  - POC-001 지점별 매출, POC-007 일별 추세, POC-008 항목별 매출
  - 원결제일 기준 재계산
  - 옛 형식 결과의 매출 행
- 이 쿼리는 결제를 **승인 시각이 아니라 행 생성 시각(`createdAt`)**으로 걸렀다. 환불 기준·상태 판정도 M9와 따로 구현돼 있었다.
- POC-003 인기 이용권 TOP 5도 자체 쿼리(생성 시각 기준)였다.
- ADR-M10-001 M10-D05("자연어 결과의 수치는 M9 공통 집계를 같은 조건 객체로 호출해 계산한다")에 어긋난다. M9 `SalesRevenueService` 주석도 "M10이 같은 `AggregationCondition`으로 부르는 경계"라고 적고 있었지만 실제로는 부르지 않았다.
- 계산 예시(aggregation-api.md)로 재현했다.
  - 11월 지점 1 승인액: M9 API 53,000원, AI 63,000원
  - AI는 10월 31일 결제(P1)와 12월 1일 결제(P7)까지 포함했다. 테스트 데이터의 행이 모두 실행 당일에 만들어졌기 때문이다.

## 2. 검토한 선택지

- **자체 쿼리의 시각 기준만 `approvedAt`으로 고치기**: 변경은 작지만, 계산 로직이 둘로 남아 M10-D05를 계속 어긴다.
- **AI가 M9 서비스를 직접 부르도록 바꾸기(선택)**
  - `SalesRevenueService`·`SalesTrendService`·`PassSalesService`를 같은 조건 객체로 부른다.
  - M9 서비스는 요청자의 `sales:read`를 지점마다 다시 확인하므로, AI 분석 실행에 요청자 ID를 넘긴다.

## 3. 결정 및 이유

- `SalesAggregationService`를 **M9 서비스의 어댑터**로 바꿨다.
  - AI 분석이 읽는 모양(`BranchSalesSummary` 등)은 유지한다.
  - 숫자는 모두 M9 서비스에서 온다. 자체 쿼리는 지웠다.
- POC-003은 M9 `GET /sales/passes/products`의 순위(순판매 수량 내림차순, 경쟁 순위)를 쓴다.
  - M9 상품별 판매에는 금액이 없어, AI 결과의 상품별 금액 열(`approved_krw`·`refund_krw`·`net_krw`)을 뺐다.
  - 수량 열은 M9의 판매·환불·순판매 수량이다.
- 분석의 지점 범위가 비면 "전체 지점"으로 해석하던 대체 동작을 없앴다. 이제 오류다. 권한 판단을 거친 범위는 비지 않는다. 이식 코드가 데모 지점 ID(9100001 이상)를 우선하던 `allBranchIds`도 일반 조회로 바꿨다.
- 쓰이지 않던 `queryForApi`(고정 날짜 기준의 옛 API 응답)를 지웠다.

## 4. 구현 내용

- `SalesAggregationService`
  - `aggregateByBranch`는 `SalesRevenueService.compareBranches`를 쓴다.
  - `aggregateByPurpose`는 `revenue`의 `byPurpose`를 쓴다.
  - `aggregateDailyTrend`는 `SalesTrendService.trend`(일 단위, 매출)를 쓴다.
  - `topPassProducts`(신규)는 `PassSalesService.products`를 쓴다.
  - 모든 메서드가 요청자 ID를 받는다.
- `OpenStudyAnalyses`
  - `executeCase`와 매출 분석 4개(001·003·007·008)가 요청자를 받는다.
  - 빈 범위는 `requireBranches`로 거부한다.
- `AiWorkspaceService`: 질문·다시 열기·실행 다시 조회·품질 평가·옛 형식 매출 행에 요청자를 넘긴다.
- `AiSalesEquivalenceIntegrationTests`(신규, 계산 예시, AI 기준 시각 2026-12-10)
  - POC-001 이번 달(12-01~12-11)이 `GET /sales/revenue/branches`와 지점별 승인·환불·순매출·건수까지 같다.
  - POC-008 지난달(11월)이 `GET /sales/revenue`의 `byPurpose`와 같다.
  - POC-003 지난달이 `GET /sales/passes/products`의 순서·수량과 같다.
- 로컬 전용 골든 테스트(`OpenStudyGoldenAggregationsIntegrationTests`)
  - 새 시그니처(OWNER ID)에 맞췄다.
  - 지운 `queryForApi` 대신 M9 어댑터로 합계를 확인한다.
  - POC-003 금액 단언은 지웠다.

## 5. 검증 결과

- 매출 동등성 테스트와 기존 매출(M9) 테스트·AI 테스트·아키텍처 테스트 통과
- 전체 `./gradlew test`: 1029개 중 실패 0, 건너뜀 25(로컬 전용 AI 테스트), 15분 51초
- **미검증**
  - POC-007(일별 추세)의 M9 동등성 테스트는 없다. M9 추세 서비스를 그대로 부르고 날짜 버킷만 옮긴다.
  - 로컬 전용 골든 테스트(데모 DB). 기존 기대값은 이식 코드의 생성 시각 기준 계산으로 만든 값이다. 데모 데이터의 생성 시각과 승인 시각이 다르면 기대값을 다시 확인해야 한다.
  - Frontend: POC-003 결과 표에서 금액 열이 빠진다.

## 6. 남은 작업 / 보류 사항

- 이 PR이 병합되면 #95를 근거와 함께 닫는다.
- 다음에 로컬 데모 DB로 골든 테스트를 다시 돌려 기대값을 확인한다.
