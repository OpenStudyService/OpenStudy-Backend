# 분석 필터 수정·최근 질의·후속 질문·결과 재조회·CSV 내보내기 API 계약

Backend [#124](https://github.com/OpenStudyService/OpenStudy-Backend/issues/124)의 M15 계약이다. 구현은 이 문서를 기준으로 다음 이슈에서 진행한다.

- 필터 수정·최근 질의·결과 재조회: #125
- 정규화·후속 질문 문맥: #126
- CSV 내보내기: #127

Frontend는 같은 [OpenAPI](openapi.json)(`0.1.16-m15-analysis-reuse`)로 연동한다. 화면은 Frontend #46·#47에서 만든다.

2026-11-11: 이 문서의 operation은 모두 설계 계약(`design-target`)이다. 이미 구현된 M10 operation·schema에 생길 변경은 그 operation·schema의 `x-planned-changes`에 적었다. 구현 PR이 이 내용을 본 정의로 옮기고 항목을 지운다.

2026-11-11(#125): 필터 수정·최근 질의를 구현했다(`implemented`). 실행 다시 조회의 query와 `AiExecution`·`AiConversationTurn`의 새 필드도 본 정의로 옮겼다. 남은 설계 계약은 #126(`AiQueryResponse`의 `conditions`·`context`)과 #127(CSV)이다.

- 정책 근거: [ADR-M15-001](../../db/ADR-M15-001.md)(M15-D01~D10)
  - M15-D04는 2026-11-11 사용자 결정으로 바뀌었다.
  - M15-D05의 적용 방식과 M15-D09·D10은 이 계약을 쓰면서 받은 사용자 결정이다.
- 바탕 계약
  - 자연어 분석: [자연어 분석 계약](nl-analysis-api.md)(M10, 등록 분석 방식 [ADR-M10-003](../../db/ADR-M10-003.md))
  - 정형 집계: [정형 집계 계약](aggregation-api.md)(M9), M13 고급 필터는 [운영·거래 확장 계약](operations-extension-api.md)
  - 공통 요청·응답·오류: [API 계약](api.md)

## 등록 분석 방식과의 관계

M10은 등록된 분석(POC-001~010) 중 하나를 고르는 방식이다(ADR-M10-003 M10-D14). ADR-M15-001은 이 결정 전에 범용 QuerySpec 흐름을 전제로 썼다. 이 계약은 M15 결정을 등록 분석 방식에 맞춰 다음과 같이 적용한다.

- **필터 수정(M15-D05)**
  - "QuerySpec을 고친다"가 아니라 "등록 분석의 조건을 바꿔 다시 실행한다"로 읽는다.
  - 분석마다 고칠 수 있는 조건을 정해 둔다. 자세한 내용은 아래 "분석별 수정 가능 조건"에 있다.
- **실행 계획 재사용(M15-D04, 2026-11-11 변경)**
  - 세션에 계획을 30분 두지 않는다.
  - 페이지·정렬·상세는 저장된 실행을 요청마다 현재 권한과 상한으로 다시 실행한다. 모델은 부르지 않는다.
  - 그래서 "계획 만료" 오류는 없다. 대화를 지워 실행이 없어지면 404다.
- **질문 속 기간**: 질문에서는 여전히 임의 기간을 읽지 않는다(ADR-M10-003). 기간은 분석의 기본 기간이거나, 필터 수정으로 사용자가 직접 정한 기간이다.

## 제공 범위

### 새 operation

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `POST /ai/executions/{executionId}/refinements` | 실행 주인 + 바뀐 조건에 대한 M10 권한 | 조건을 바꿔 다시 실행하고, 같은 대화에 새 실행으로 남긴다. 모델을 부르지 않는다 | #125 |
| `GET /ai/recent-queries` | 본인 | 30일 안의 본인 질문 최신 20건 | #125 |
| `GET /ai/executions/{executionId}/export` | 실행 주인 + 현재 M10 권한 | 결과를 CSV로 내려준다 | #127 |
| `GET /sales/exports/{report}` | 해당 집계 화면과 같다(요청한 모든 지점의 `sales:read`) | 정형 집계를 CSV로 내려준다 | #127 |

### 기존 operation 변경(`x-planned-changes`)

| API | 변경 | 구현 |
| --- | --- | --- |
| `GET /ai/executions/{executionId}` | query `datasetId`, `page`, `size`, `sort`, `direction`. 응답에 `conditions`, `editableFilters`, `origin`, `sourceExecutionId` | #125 |
| `POST /ai/queries`, `POST /ai/conversations/{conversationId}/requests`의 결과(`AiQueryResponse`) | `conditions`, `context`(이어받은 조건·바뀐 조건·문맥 끊김) | #126 |
| `GET /ai/conversations/{conversationId}`의 질문(`AiConversationTurn`) | `origin`, `sourceExecutionId` | #125 |

## 공통 규칙

- **세션과 CSRF**: M10과 같다. 로그인 세션(`WORKSPACE`)이 필요하고, 쓰기 요청은 CSRF 토큰이 필요하다.
- **모든 재사용 경로는 권한을 다시 확인한다.** 대상은 필터 수정, 최근 질의 재실행, 페이지·정렬·상세, 후속 질문 문맥, CSV 내보내기다.
  - 저장된 조건·문맥·화면에서 받은 값은 권한의 근거가 아니다.
  - 요청할 때마다 현재 `ai:read`, 지점 범위, 매출이면 대상 지점 모두의 `sales:read`(M10-D06)를 다시 검사한다.
- **다른 직원의 실행·질문**은 OWNER를 포함해 404 `NOT_FOUND`다(M10-D12). 존재 여부를 알리지 않는다.
- **권한이 줄어든 경우**
  - 일부 지점만 남았다고 범위를 줄여 계산하지 않는다. 거절하고 사유 코드를 돌려준다(M9-D04).
  - 사유 코드: `AI_PERMISSION_DENIED`, `BRANCH_SCOPE_DENIED`, `SALES_PERMISSION_DENIED`, `AUTHORIZATION_SCOPE_CHECK`
  - 화면은 사유를 보여 주고, 사용자가 범위를 직접 줄여 필터를 수정하게 한다.
- **결과 상한(M10-D03)**: 한 결과 최대 1,000행, 표 페이지 50행, 기간 366일 이하. 재실행·내보내기도 같다.
- **빈도 제한**: 필터 수정과 AI 결과 내보내기는 M10의 직원별 빈도 제한(분당·동시)에 함께 집계한다. 초과하면 429 `RATE_LIMITED`다.
- **화면 갱신**: 변경 이벤트는 없다. 화면은 응답과 다시 조회로 갱신한다(ADR-COM-001).

### 추가 오류 코드

| HTTP | code | 의미 | 대상 정보 |
| --- | --- | --- | --- |
| 422 | `FILTER_NOT_EDITABLE` | 그 분석에서 고칠 수 없는 조건을 보냄(예: 실시간 빈자리의 기간) | `fieldErrors`(조건 이름) |
| 422 | `NO_FILTER_CHANGE` | 필터 수정 요청이 원래 조건과 같음 | - |
| 404 | `EXPORT_REPORT_UNKNOWN` | `GET /sales/exports/{report}`의 `report`가 정해진 값이 아님 | - |

기존 코드도 쓴다.

- 403: `FORBIDDEN`, `AI_PERMISSION_DENIED`, `BRANCH_SCOPE_DENIED`, `SALES_PERMISSION_DENIED`, `AUTHORIZATION_SCOPE_CHECK`
- 404: `NOT_FOUND`
- 422: `VALIDATION_FAILED`, `PERIOD_TOO_LONG`, `NOT_EXECUTABLE`, `ANALYSIS_UNAVAILABLE`
- 429: `RATE_LIMITED`
- 503: `AI_NOT_READY`

## 분석 조건(`AiConditions`)

실행 하나가 어떤 조건으로 계산됐는지를 나타낸다. 실행 다시 조회·질문 결과·필터 수정 결과에 들어간다.

| 필드 | 형식 | 의미 |
| --- | --- | --- |
| `analysisId` | 문자열 | 등록 분석 ID |
| `period` | `{from, to}` 날짜(Asia/Seoul, 양 끝 포함) 또는 null | 계산 기간. 기간이 없는 분석(실시간)은 null |
| `periodSource` | `DEFAULT`·`EDITED` | 분석의 기본 기간인지, 사용자가 고친 기간인지 |
| `branchIds` | ID 목록 | 계산한 지점 |
| `productIds` | ID 목록 | 상품 조건. 없으면 빈 목록(전체) |
| `refundBasis` | `processed`·`original` 또는 null | 매출 분석의 환불 기준 |
| `zeroFill` | 불리언 또는 null | 추이 분석의 0원 채우기 |

### 분석별 수정 가능 조건(`editableFilters`)

M15-D05에 따라 고칠 수 있는 조건은 기간·지점·상품뿐이다. 분석마다 의미가 있는 조건만 열어 둔다(2026-11-11 사용자 결정). 응답의 `editableFilters`는 `{period: {maxDays} 또는 null, branches: {single: 불리언}, products: 불리언}`이다.

| 분석 | 기간 | 지점 | 상품 |
| --- | --- | --- | --- |
| POC-001 지점별 매출 비교 | 고침(366일) | 여러 지점 | - |
| POC-002 시간대별 혼잡도 | 고침(92일) | 한 지점 | - |
| POC-003 이용권 판매 순위 | 고침(366일) | 여러 지점 | 고침 |
| POC-004 실시간 빈자리 | - (지금 기준) | 한 지점 | - |
| POC-005 스터디룸 예약 현황 | 고침(31일) | 여러 지점 | - |
| POC-006 만료 예정 이용권 | 고침(92일) | 여러 지점 | 고침 |
| POC-007 일별 매출 추이 | 고침(366일) | 여러 지점 | - |
| POC-008 매출 구성(이용권·룸·사물함) | 고침(366일) | 여러 지점 | - |
| POC-009 급한 업무 | - (지금 기준) | 여러 지점 | - |
| POC-010 직원별 근무시간 | 고침(92일) | 여러 지점 | - |

- 표의 최대 일수는 구현 기준이다. #125에서 실제 계산 비용을 측정해 낮출 수 있고, 바꾸면 이 문서와 개발 기록에 남긴다.
- 상한은 응답의 `editableFilters`로 알려 주므로, 화면은 이 값을 따른다.
- "한 지점" 분석에 여러 지점을 보내면 422 `VALIDATION_FAILED`다(M10-D17과 같은 취지).
- 2026-11-11(#125): POC-009를 "한 지점"에서 "여러 지점"으로 고쳤다. 질문 처리에서 한 지점만 보는 분석은 POC-002·004뿐이고(ADR-M10-003), POC-009는 여러 지점을 함께 계산한다. 처음 표가 이와 달랐다.
- 최대 일수는 아직 측정으로 바꾸지 않았다. #125 구현은 표의 값을 그대로 쓴다.

## 필터 수정(FR-NL-22, M15-D05)

`POST /ai/executions/{executionId}/refinements`

### 요청

| 필드 | 필수 | 의미 |
| --- | --- | --- |
| `period` | 아니오 | `{from, to}` 날짜(Asia/Seoul, 양 끝 포함) |
| `branchIds` | 아니오 | 지점 ID 목록(1개 이상) |
| `productIds` | 아니오 | 상품 ID 목록(0~20개, 빈 목록은 전체) |
| `clientRequestKey` | 아니오 | 중복 제출 방지. M10과 같은 형식·동작이다 |

- 세 조건 중 하나 이상이 있어야 하고, 없는 조건은 원래 실행의 값을 그대로 쓴다.
- 그 외 필드(분석 ID, 환불 기준, 정렬 등)는 422 `VALIDATION_FAILED`다. 분석을 바꾸려면 새 질문을 한다.

### 판정 순서

| 순서 | 조건 | 결과 |
| --- | --- | --- |
| 1 | 실행이 없거나 남의 것 | 404 `NOT_FOUND` |
| 2 | 원래 실행이 실행된 질문이 아님(확인 질문·거절) | 422 `NOT_EXECUTABLE` |
| 3 | 카탈로그에서 그 분석이 빠짐 | 422 `ANALYSIS_UNAVAILABLE` |
| 4 | 형식 오류, 그 분석에서 고칠 수 없는 조건 | 422 `VALIDATION_FAILED`·`FILTER_NOT_EDITABLE` |
| 5 | 기간이 그 분석의 최대 일수 초과 | 422 `PERIOD_TOO_LONG` |
| 6 | 바뀐 조건이 원래와 같음 | 422 `NO_FILTER_CHANGE` |
| 7 | 현재 권한·지점 범위 검사(바뀐 지점과 그대로인 지점 모두) | 403 사유 코드 |
| 8 | 통과 | 실행하고 결과를 돌려준다 |

- 권한 밖 지점을 보내면 그 지점의 이름·수치를 응답에 담지 않는다(ADR-M10-003).
- 존재하지 않거나 볼 수 없는 상품 ID는 422 `VALIDATION_FAILED`(`fieldErrors.productIds`)다. 어느 쪽인지는 알리지 않는다.

### 결과

- 응답은 질문 결과와 같은 `AiQueryResponse`다(`status: COMPLETED`).
  - `conditions`는 적용한 조건이다.
  - `context.changed`에 바뀐 조건 이름이 들어간다.
- 같은 대화에 새 실행으로 저장한다.
  - `origin: REFINEMENT`, `sourceExecutionId`에 원래 실행 ID를 둔다.
  - 질문 원문은 원래 질문을 그대로 둔다.
- 대화의 마지막 성공 요청이 되므로, 다음 후속 질문은 이 조건을 이어받는다.
- 모델을 호출하지 않는다. 질문 처리의 모델 입구를 거치지 않는다(#180과 같은 확인).

## 최근 질의(FR-NL-23, M15-D01)

`GET /ai/recent-queries`

- 본인이 30일 안에 한 질문 중 최신 20건이다(질문 시각 내림차순). query는 받지 않는다.
- 지운 대화의 질문은 나오지 않는다. 필터 수정으로 생긴 실행도 나오지 않는다. 질문만 나온다.
- 저장소: 별도로 두지 않는다. M10 대화에 저장된 질문을 쓴다(M15-D01의 "별도 저장소 없음"과 같다).
  - 원래 결정은 진단 로그를 근거로 했다.
  - 진단 로그 원문은 30일 뒤 지워지고, 대화 질문은 사용자가 지울 때까지 남는다. 그래서 30일 범위는 조회 조건으로 둔다.
- 항목 `AiRecentQuery`
  - `requestId`, `executionId`(실행되지 않은 질문은 null), `conversationId`, `conversationTitle`
  - `question`, `status`, `analysisId`(없으면 null), `askedAt`
  - `rerunnable`: 실행된 질문인지
- 다른 직원의 질문은 목록에 없다. 다른 직원의 실행·대화 ID로 직접 요청하면 404다.

**재실행**

- 실행된 질문은 `GET /ai/executions/{executionId}`로 다시 실행한다. 현재 권한으로 다시 검증하고 새 기준 시각으로 계산하며, 모델을 부르지 않는다.
- 권한이 줄었으면 403 사유 코드를 돌려준다. 화면은 사유와 함께 범위를 줄인 필터 수정을 안내한다.
- 실행되지 않은 질문(확인 질문·거절)은 다시 물어야 한다. 화면이 같은 질문으로 `POST /ai/queries`를 보내며, 이것은 새 질문이라 모델을 부른다.

## 결과 페이지·정렬·상세(FR-NL-35, M15-D04 변경)

`GET /ai/executions/{executionId}`에 query를 더한다(`x-planned-changes`).

| query | 의미 |
| --- | --- |
| `datasetId` | 결과의 데이터셋 ID. 없으면 첫 데이터셋 |
| `page`, `size` | 표 페이지(기본 1, 20). `size`는 50 이하 |
| `sort` | 정렬할 열 별칭(그 데이터셋의 `columns[].alias`만) |
| `direction` | `asc`·`desc`(기본 `desc`) |

- **상세**: 차트 결과의 데이터셋을 표 페이지로 보는 것이다. 같은 query로 조회한다.
- **재실행 방식**: 요청마다 저장된 조건을 현재 권한·상한으로 다시 실행한다. 세션·시간 제한은 없다(M15-D04 변경).
- **수치가 같은 경우**: 기준 시각이 바뀌므로 "같은 조건이면 같은 수치"는 같은 기준 시각 범위의 데이터에 대해 성립한다. 응답의 `asOf`로 확인한다.
- **정렬 범위**: 정렬은 1,000행 상한 안에서 한다. 상한에 걸린 결과를 다른 열로 정렬해도, 잘린 뒤의 1,000행 안에서만 정렬된다. `truncation`이 이를 알린다.
- **오류**: 알 수 없는 `datasetId`·`sort`는 422 `VALIDATION_FAILED`다.
- **query가 없을 때**: 다섯 query가 모두 없으면 결과 전체를 페이지로 자르지 않고 그대로 돌려준다(M10 화면과 같은 동작). 하나라도 있으면 그 데이터셋만 페이지로 자르고, 다른 데이터셋은 그대로 둔다.
- **권한이 줄어든 경우**: 다른 재사용 경로와 같이 403 사유 코드(`AI_PERMISSION_DENIED`·`BRANCH_SCOPE_DENIED`·`SALES_PERMISSION_DENIED`)를 돌려준다. #125 전에는 `FORBIDDEN`이었다.

## 후속 질문 문맥(FR-NL-25, M15-D06)

`POST /ai/queries`와 `POST /ai/conversations/{conversationId}/requests`에 `conversationId`가 있으면, 같은 대화의 직전 성공 요청 조건을 이어받는다. 직전 성공 요청은 마지막 `COMPLETED` 실행이며, 필터 수정 실행도 포함한다.

결과의 `context`(`AiContext`):

| 필드 | 의미 |
| --- | --- |
| `basedOnExecutionId` | 이어받은 실행. 이어받지 않았으면 null |
| `inherited` | 이어받은 조건 이름 목록(`ANALYSIS`, `PERIOD`, `BRANCHES`, `PRODUCTS`, `REFUND_BASIS`, `ZERO_FILL`) |
| `changed` | 이번 질문으로 바뀐 조건 이름 목록 |
| `reset` | 문맥을 끊었는지 |
| `resetReason` | `NO_PRIOR_RESULT`(이을 결과 없음), `SCOPE_CHANGED`(선택 지점 범위가 바뀜), `PERMISSION_LOST`(이어받을 지점의 권한이 없어짐) 또는 null |

- **문맥을 끊는 경우**
  - 요청의 `scope`가 직전 요청과 다르면 끊는다(M15-D06).
  - 이어받을 지점 중 하나라도 현재 권한이 없으면, 이어받지 않고 끊는다(`PERMISSION_LOST`). 문맥으로 권한을 우회하지 못하게 하기 위해서다.
- **조건을 바꾼 경우**: 사용자가 질문에서 바꾼 조건(지점 이름, 환불 기준 등)은 새 값이 적용되고 `changed`에 들어간다.
- **기간**
  - 질문에서 임의 기간을 읽지 않는다(ADR-M10-003).
  - 이어받은 분석이 같으면 이어받은 기간(사용자가 고친 기간 포함)을 쓴다. 분석이 바뀌면 새 분석의 기본 기간을 쓴다.
- **모델에 보내는 문맥**: 이전 질문 원문이 아니라 직전 실행의 조건(분석 ID와 부가 조건)이다.
- **정규화(FR-NL-24, M15-D03·D08)**: 동의어·오타·띄어쓰기 정규화는 질문을 처리할 때 서버 안에서 일어난다. 새 API는 없다.

## CSV 내보내기(FR-AGG-16, M15-D02·D07·D09·D10)

### 경로

- **자연어 분석 결과**: `GET /ai/executions/{executionId}/export?datasetId=`
  - 저장된 조건을 현재 권한으로 다시 실행해 만든다. 화면에 보인 결과 파일을 다시 내려주지 않는다.
  - `datasetId`가 없으면 첫 데이터셋이다.
- **정형 집계**: `GET /sales/exports/{report}`
  - `report`가 정해진 값이 아니면 404 `EXPORT_REPORT_UNKNOWN`이다.
  - 화면 API와 같은 query(기간·지점·환불 기준·용도·고급 필터 등)를 받는다. 권한·검증·오류도 그 API와 같다. 페이지 query는 받지 않는다.

| `report` | 같은 조건의 화면 API |
| --- | --- |
| `revenue` | `GET /sales/revenue` |
| `revenue-branches` | `GET /sales/revenue/branches` |
| `ledger` | `GET /sales/ledger` |
| `trend` | `GET /sales/trend` |
| `passes-volume` | `GET /sales/passes/volume` |
| `passes-products` | `GET /sales/passes/products` |
| `passes-usage` | `GET /sales/passes/usage` |

**승인 절차는 없다(M15-D09, 2026-11-11 사용자 결정).** 권한을 다시 확인해 바로 내려주고 감사에 남긴다. DomainLens에서 이식된 반출 승인 기능(`/api/ai/approvals`)은 이 계약에 넣지 않는다. 그 기능의 제거는 별도 이슈로 다룬다.

### 응답

- 200
  - `Content-Type: text/csv; charset=UTF-8`
  - `Content-Disposition: attachment; filename*=UTF-8''<이름>_<YYYYMMDD-HHmm>.csv`
  - `Cache-Control: no-store`
- **인코딩**: UTF-8에 BOM(`EF BB BF`)을 붙인다(M15-D10). 한글 Excel에서 바로 열기 위해서다.
- **형식**: 줄바꿈은 CRLF다. 값에 쉼표·따옴표·줄바꿈이 있으면 큰따옴표로 감싸고, 안의 큰따옴표는 두 번 쓴다(RFC 4180).

### 파일 구조

머리말 행, 빈 행 하나, 표 순서다.

```
항목,값
제목,지점별 매출 비교
조회 조건,"기간 2026-10-01~2026-10-31 (Asia/Seoul) · 지점 강남점, 성수점 · 환불 기준 처리일"
단위,원
통화,KRW
기준 시각,2026-11-11T09:30:00+09:00
생성 시각,2026-11-11T09:30:02+09:00
행 수,"1,000 / 전체 1,240"
잘림,예 (최대 1,000행)

지점,순매출(원),결제 수
강남점,1250000,42
```

- **머리말**
  - 조회 조건: 화면 API의 `aggregation.condition`·`conditionNotes`나 AI 결과의 `conditions`를 사람이 읽는 문장으로 쓴다. 지점은 요청자가 볼 수 있는 지점 이름만 나온다.
  - 단위·통화: 금액 결과는 원·KRW다. 금액이 아닌 결과(좌석 수, 시간 등)는 열 이름의 단위로 대신하고 통화 행은 `-`다.
  - 기준 시각: 결과의 `asOf`(Asia/Seoul 오프셋 포함)다.
- **표**: 첫 행은 열 이름이다. 단위가 있으면 열 이름 뒤 괄호에 쓴다.
- **최대 1,000행**: 넘으면 앞의 1,000행만 담고, 머리말의 `행 수`·`잘림`으로 알린다.
- **수식 주입 방지(M15-D07)**
  - 문자열 값이 `=`, `+`, `-`, `@`, 탭, 캐리지 리턴으로 시작하면 앞에 작은따옴표(`'`)를 붙인다.
  - 숫자 형식 열(`INTEGER`·`DECIMAL`, 금액·건수)은 숫자 그대로 쓴다. 음수의 `-`는 수식이 아니다.
- **감사**
  - 내보낼 때마다 `audit_events`에 한 행을 남긴다.
  - `entity_type`은 `ai-execution` 또는 `sales-report`, `action`은 `EXPORTED`다.
  - `after_json`에는 조건 요약(분석 ID 또는 `report`, 기간, 지점 ID, 고급 필터), 행 수, 잘림 여부를 담는다.
  - 결과 데이터는 담지 않는다.

### 이미지 내보내기

차트 이미지는 브라우저가 만든다(M15-D02, Frontend #47). 서버 API는 없다. 그림에 조회 조건과 기준 시각을 넣는 것은 화면의 일이다.

## 생성 타입 동기화

Backend의 `openapi.json`이 정본이다. Frontend는 `npm run api:foundation`으로 `src/shared/api/foundation.generated.ts`를 다시 만든다.
