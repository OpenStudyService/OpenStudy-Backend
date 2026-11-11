# 2026-11-11 M15 해석 필터 수정·최근 질의·결과 페이지 (#125)

## 1. 배경 / 문제

- #124(PR #213)에서 M15 계약을 정했다. 이 작업은 그중 #125 부분을 구현한다.
  - 필터 수정: `POST /ai/executions/{id}/refinements`
  - 최근 질의: `GET /ai/recent-queries`
  - 결과 페이지·정렬·상세: `GET /ai/executions/{id}`에 query 추가
- 등록 분석 10개는 기간을 코드 안에서 정했다(`clock.lastMonth()` 등). 상품 조건은 받지 않았다.
- 저장된 실행(`ai_executions`)에는 분석 ID·지점·환불 기준·0원 채우기만 있었다.
  - 사용자가 고친 기간·상품을 다시 실행하려면 이 조건도 저장해야 했다.
- #125 이슈 본문은 #124 이전 기준(30분 세션 계획 재사용, 계획 만료)이었다.
  - 착수 전에 M15-D04 변경(저장 실행 재실행)에 맞게 본문을 고쳤다.

## 2. 검토한 선택지

| 항목 | 선택지 | 결과 |
| --- | --- | --- |
| 분석에 기간·상품 전달 | 분석 메서드마다 기간(`override`)·상품 매개변수를 더하고, 기존 메서드는 기본값으로 위임 / 실행기 전체를 조건 객체 기반으로 재구성 | 매개변수 추가. 질문 흐름과 골든 테스트가 쓰는 기존 메서드의 동작이 그대로 남는다 |
| POC-003 상품 조건 | M9 이용권 상품 순위의 상품 필터(한 개)를 상품마다 호출해 합침 / M9에 여러 상품 필터를 새로 추가 | 상품마다 호출. M9 계약을 바꾸지 않고 M9 권한 검사를 그대로 쓴다. 상품은 최대 20개다 |
| 페이지 query가 없을 때 | 결과 전체를 그대로 돌려줌 / 계약의 기본값(1쪽 20행)으로 항상 자름 | 그대로 돌려줌. 항상 자르면 M10 화면의 차트(예: 31일 추이)가 20행으로 잘린다 |
| 재사용 경로의 권한 거절 코드 | 기존 `FORBIDDEN` 유지 / 계약의 사유 코드 | 사유 코드(`AI_PERMISSION_DENIED`·`BRANCH_SCOPE_DENIED`·`SALES_PERMISSION_DENIED`). 계약 공통 규칙("거절하고 사유 코드를 돌려준다")을 따른다. HTTP 상태(403)는 같다 |

## 3. 결정 및 이유

- **조건 저장(V17)**: `ai_executions`에 열 다섯 개를 더했다.
  - `period_from`·`period_to`: 고친 기간. NULL이면 분석 기본 기간이다.
  - `product_ids_csv`: 상품 조건. NULL이면 전체 상품이다.
  - `origin`: `QUESTION` 또는 `REFINEMENT`
  - `source_execution_id`: 필터 수정의 원래 실행
  - 기존 행은 모두 질문(`QUESTION`)이고 기본 조건을 쓴다. 결과는 여전히 저장하지 않는다(M10-D12).
- **필터 수정 판정 순서**: 계약 표 순서(404 → 422 `NOT_EXECUTABLE` → 422 `ANALYSIS_UNAVAILABLE` → 형식·`FILTER_NOT_EDITABLE` → `PERIOD_TOO_LONG` → `NO_FILTER_CHANGE` → 403 사유 코드)를 따랐다.
  - 바뀐 지점과 그대로인 지점을 모두 현재 권한으로 다시 검사한다.
  - 모델을 부르지 않는다. 질문 처리의 모델 입구(`planQuestion`)를 거치지 않는다.
- **재실행 범위**: 대화를 다시 열 때 필터 수정 실행은 질문의 범위가 아니라 저장된 지점으로 다시 실행한다. 질문 범위로 다시 계산하면 사용자가 고른 지점이 사라지기 때문이다.
- **최근 질의**: 대화에 저장된 질문(`origin=QUESTION`)을 기준 시각부터 30일 안에서 최신 20건 읽는다.
  - 기존 인덱스 `ix_ai_executions_emp(employee_id, created_at)`를 쓴다.
- **계약 정정(POC-009)**
  - #124 계약 표에서는 POC-009(급한 업무)를 "한 지점"으로 적었다.
  - 하지만 질문 흐름에서 한 지점만 보는 분석은 POC-002·004뿐이다(`SINGLE_BRANCH`). POC-009는 여러 지점을 함께 계산한다.
  - 그래서 코드에 맞춰 표를 "여러 지점"으로 고쳤다. 단위 테스트가 두 목록이 같은지 확인한다.
- **최대 일수**: 표의 값(366·92·31)을 그대로 썼다. 계산 비용 측정은 하지 않았다(남은 작업).

## 4. 구현 내용

- `V17__ai_execution_conditions.sql`, `AiExecutionEntity`: 조건 열과 `refines(...)`
- `OpenStudyAnalyses`
  - 기간이 있는 분석 메서드 8개에 기간 매개변수를 더했다.
  - POC-003·006에는 상품 매개변수를 더했다.
  - `executeCase(…, period, products)`, `defaultPeriod`, `existingProducts`를 추가했다.
- `SalesAggregationService.topPassProducts(…, productId)`: M9 상품 필터를 쓰는 overload
- 새 `AiReuse`(순수 함수)
  - 분석별 수정 가능 조건 표(`editableFilters`)
  - 요청 본문 검증
  - 조건(`AiConditions`)과 바뀐 조건 계산
  - 후속 문맥(`AiContext`)
  - 데이터셋 페이지·정렬
- `AiWorkspaceService`
  - `refine`, `recentQueries`
  - `getExecution`의 table query와 `conditions`·`editableFilters`·`origin`·`sourceExecutionId`
  - 대화 다시 열기의 `origin`·`sourceExecutionId`
  - 재사용 경로 권한 거절의 사유 코드
- `AiWorkspaceController`: 새 경로 두 개, 실행 조회 query
  - 필터 수정은 M10 직원별 빈도 제한에 함께 집계한다.
- 계약
  - `openapi.json`: #125 operation을 `implemented`로 바꿨다. `getAiExecution`·`AiExecution`·`AiConversationTurn`의 계획 변경을 본 정의로 옮겼다.
  - `analysis-reuse-api.md`: POC-009 정정, 상품 20개 제한, query가 없을 때의 동작, 사유 코드
- 테스트
  - 새 `AiReuseUnitTests`(8건)
  - `AnalysisReuseContractTests`: 구현 상태, 계획 변경 이동, 컨트롤러 경로 확인
  - V17에 맞춘 마이그레이션 버전 고정 테스트 3개와 V17 테스트 1건

## 5. 검증 결과

- 실행함
  - `./gradlew test --tests "com.openstudy.architecture.*" --tests "com.openstudy.modules.ai.service.*"`: 15개 클래스 116건 통과
    - `AiReuseUnitTests` 8건 포함
  - `FoundationMigrationTests`: 24건 통과(새 V17 테스트 포함)
    - 처음 병렬 실행에서는 Docker 연결 오류(`Could not find a valid Docker environment`)로 시작하지 못했다.
    - `-PtestForks=1`로 다시 실행해 통과했다.
  - `OwnerBootstrapTests` 10건, `DatabaseInitializationTests` 4건, `MigrationVersionTests` 1건 통과
- 실행하지 않음(마일스톤 검증 때 실행)
  - AI 통합 테스트(`modules.ai.*Integration*`, `OpenStudyAiLifecycleAndNegativeCasesTests` 등)
    - 필터 수정·최근 질의·페이지를 실제 HTTP와 DB로 확인하지 않았다.
    - 모델 호출 횟수, 타인 실행 접근, 권한 축소 후 재실행도 HTTP로 확인하지 않았다.
  - 기존 `AiConversationHistoryIntegrationTests`는 권한이 줄어든 실행을 403으로 확인한다. 코드가 `FORBIDDEN`에서 사유 코드로 바뀌어도 상태는 같다. 다만 실행해 확인하지는 않았다.
  - 전체 `./gradlew test`
- `graphify update .`: 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- **분석별 최대 일수 측정**: 이슈에 적힌 비용 측정은 하지 않았다. 지금은 계약 표의 값이다.
- **결과 제목**: 고친 기간으로 실행해도 화면 제목은 분석 기본 문구(예: "이번 달 전체 순매출 요약")로 남는다. 실제 기간은 `conditions.period`로 알 수 있다. 제목을 조건에 맞추는 작업은 화면(Frontend #46)과 함께 정한다.
- **#126**: 질문 응답(`AiQueryResponse`)의 `conditions`·`context`는 아직 설계 계약이다. 필터 수정 응답에만 먼저 넣었다.
- **#127**: CSV 내보내기
- **통합 검증**: 위 "실행하지 않음" 항목을 M15 마일스톤 검증에서 실행한다.
