# 자연어 분석(AI 채팅) API 계약

Backend [#92](https://github.com/OpenStudyService/OpenStudy-Backend/issues/92)의 M10 계약이다. 정책 근거는 [ADR-M10-001](../../db/ADR-M10-001.md)(M10-D01~D08)과 [ADR-M10-002](../../db/ADR-M10-002.md)(M10-D09~D13), 상태 전달은 [ADR-COM-001](../../db/ADR-COM-001.md)이다. 공통 요청·응답·오류 규칙은 [API 계약](api.md)을 따르고, 수치는 [정형 집계 계약](aggregation-api.md)의 지표 정의(M10-D05)를 쓴다.

2026-11-08: 이 문서는 **이미 구현된 서버 동작을 계약으로 정리**한 것이다. AI 기능은 DomainLens에서 이식한 코드(#169)와 #172(대화 기록)·#178(실행 본인 확인)·#180(다시 열기 사유 코드)·#182(매출 행 권한)로 구현됐다. OpenAPI의 operation은 모두 `implemented`다.

## 범위

### 정식 계약(10개, `tags: ai`)

| operation | 경로 | 설명 |
| --- | --- | --- |
| `getAiReadiness` | `GET /ai/catalog/readiness` | 의미 카탈로그·모델 모드 준비 상태 |
| `createAiConversation` | `POST /ai/conversations` | 빈 대화 생성 |
| `listAiConversations` | `GET /ai/conversations` | 본인 대화 목록(마지막 질문 시각 순) |
| `reopenAiConversation` | `GET /ai/conversations/{conversationId}` | 대화 다시 열기(현재 권한으로 재검증·재실행) |
| `renameAiConversation` | `PATCH /ai/conversations/{conversationId}` | 제목 변경 |
| `deleteAiConversation` | `DELETE /ai/conversations/{conversationId}` | 대화와 저장 질문 삭제 |
| `askAiQuestion` | `POST /ai/queries` | 질문 제출·결과 반환(전체 응답) |
| `submitAiConversationRequest` | `POST /ai/conversations/{conversationId}/requests` | 대화에 질문 제출(접수 결과만 반환) |
| `cancelAiRequest` | `POST /ai/requests/{requestId}/cancel` | 본인 요청 취소 표시 |
| `getAiExecution` | `GET /ai/executions/{executionId}` | 저장된 실행을 현재 권한으로 다시 조회 |

### 계약에 넣지 않은 것(2026-11-08 사용자 결정)

- **옛 경로**: `/api/conversations`, `/api/conversations/{id}/requests`, `/api/executions/{id}`, `/api/catalog/readiness`, `/api/requests/{id}/cancel`은 `/api/ai/*`와 같은 동작의 이식 경로였다. Frontend가 `/api/ai/*`로 옮긴 뒤(Frontend #84) 2026-11-08에 서버에서 지웠다. 이제 404다.
- **이식된 부가 기능**: 업무 기록(`/api/ai/records`), 진단 로그(`/api/ai/traces`), 품질 평가(`/api/ai/evaluation`)는 서버에 있지만 이 계약 범위가 아니다. 권한 기준이 확정되지 않았다. M15(#124·#127)와 운영 범위에서 계약한다.
  - CSV 반출 승인(`/api/ai/approvals`)은 2026-11-11 서버에서 지웠다(#214, ADR-M15-001 M15-D09). M15 CSV 내보내기는 승인 없이 내려준다.
- **SSE·상태 polling**: ADR-COM-001 COM-D08에 따라 없다. 질문은 한 번의 요청·응답으로 끝나고, 화면은 사용자가 요청할 때 다시 조회한다.

## 공통 규칙

- 인증: 로그인 세션(`WORKSPACE`). 쓰기 요청(POST·PATCH·DELETE)은 CSRF 토큰이 필요하다.
- 권한: 모든 operation은 요청자가 한 지점 이상에서 `ai:read`를 가져야 한다. 매출 질문은 대상 지점 **모두**에서 `sales:read`도 필요하다(M10-D06).
- `scope`: `all` 또는 지점 ID. 지점 ID면 그 지점에 `ai:read`가 있어야 한다. 숫자가 아니면 422 `VALIDATION_FAILED`.
  - OWNER가 아닌 직원이 `all`로 지점을 지정하지 않은 질문을 하면 403 `AUTHORIZATION_SCOPE_CHECK`이다. 소속 지점을 고르거나 질문에 지점명을 넣어야 한다.
  - 질문에 권한 없는 지점이 들어 있으면 일부만 계산하지 않고 거절한다(403 `BRANCH_SCOPE_DENIED`, M9-D04).
- 빈도 제한: 직원별 분당 요청 수와 동시 요청 수를 설정값(`openstudy.ai.rate-limit.max-per-minute`, `max-concurrent`)으로 제한한다(M10-D02). 초과하면 429 `RATE_LIMITED`와 `Retry-After: 60`이다. 값은 실제 지연·비용 측정 후 확정한다.
- 대화·요청·실행 ID는 문자열이다(`conv-…`, `req-…`, `exec-…`).
- 다른 직원의 대화·요청·실행은 OWNER를 포함해 존재하지 않는 것과 같은 404 `NOT_FOUND`다(M10-D12, #178).
- 저장된 실행을 다시 조회할 때 권한이 없어졌으면 403 `FORBIDDEN`이다.

## 질문 처리

`POST /ai/queries`는 질문 하나를 받아 결과까지 한 번에 돌려준다. 처리 순서는 다음과 같다.

1. **입력 검사**: 빈 질문(400 `QUESTION_EMPTY`), 500자 초과(400 `QUESTION_LENGTH_EXCEEDED`)
2. **안전 검사**(모델 호출 전): HTML·스크립트, SQL·JPQL, 변경 의도, 개인정보·인증정보·허용되지 않은 원천
3. **명세 생성**: 등록된 업무 용어·지표·예제만 모델에 보낸다(M10-D07). 결과는 조회 명세(QuerySpec) 또는 확인 질문 또는 지원 불가다.
4. **권한·범위 검사**: `ai:read`, 지점 범위, 매출이면 `sales:read`
5. **실행**: 서버가 검증한 QuerySpec만 등록된 분석으로 실행한다. 모델이 만든 SQL·JPQL·스크립트는 실행하지 않는다.
6. **결과**: 결과 봉투(ResultEnvelope)와 화면 명세(ViewSpec)를 돌려주고, 질문·명세·상태·기준 시각만 저장한다(결과 데이터는 저장하지 않음, M10-D12).

`conversationId`를 보내면 그 대화에 질문을 더하고, 없으면 새 대화를 만든다. 남의 대화 ID는 404다.

`POST /ai/conversations/{conversationId}/requests`는 같은 처리를 하고 접수 결과(`requestId`, `executionId`, `conversationId`, `status`)만 돌려준다. 실패하면 HTTP 상태와 공통 오류 형식(`code`, `message`)으로 돌려준다. 결과는 `GET /ai/executions/{executionId}`로 조회한다.

### 응답 상태(`status`)

| 상태 | HTTP | 의미 |
| --- | --- | --- |
| `COMPLETED` | 200 | 실행해 결과를 돌려줌 |
| `CLARIFICATION` | 200 | 조건 확인 필요. `clarificationOptions` 중 하나를 `clarificationChoice`로 다시 보낸다(재실행 때 현재 권한 다시 확인) |
| `UNSUPPORTED` | 200 | 업무 도메인 밖 질문 |
| `INVALID` | 400 | 입력·기간 오류 |
| `DENIED` | 400·403 | 안전 정책·권한·범위 거절 |
| `RATE_LIMITED` | 429 | 빈도 제한 |
| `NOT_READY` | 503 | 카탈로그가 준비되지 않음(`AI_NOT_READY`). 질문을 실행하지 않는다 |

### 실패 코드(`errorCode`)

| 분류 | 코드 | HTTP |
| --- | --- | --- |
| 입력 | `QUESTION_EMPTY`, `QUESTION_LENGTH_EXCEEDED` | 400 |
| 기간 | `DATE_RANGE_LIMIT_EXCEEDED`(366일 초과) | 400 |
| 안전 정책 | `REJECT_ARBITRARY_HTML`, `REJECT_RAW_SQL`, `UNSUPPORTED_MODIFICATION_INTENT` | 400 |
| 개인정보·원천 | `PII_OR_SCOPE_DENIED`, `PII_MINIMIZATION`, `CREDENTIAL_ACCESS_DENIED`, `FORBIDDEN_SOURCE_DENIED` | 403 |
| 권한·범위 | `AI_PERMISSION_DENIED`, `BRANCH_SCOPE_DENIED`, `SALES_PERMISSION_DENIED`, `AUTHORIZATION_SCOPE_CHECK` | 403 |
| 범위 입력 | `VALIDATION_FAILED` | 422 |
| 지원 불가 | `OUT_OF_DOMAIN` | 200(`UNSUPPORTED`) |
| 조건 확인 | `CLARIFICATION_REQUIRED`, `CLARIFICATION_OR_POLICY_PENDING`, `BRANCH_REQUIRED` | 200(`CLARIFICATION`) |
| 빈도 | `RATE_LIMITED` | 429 |
| 준비 상태 | `AI_NOT_READY` | 503 |

### 지점 선택(ADR-M10-003 M10-D17)

좌석 혼잡도·실시간 빈자리처럼 한 지점만 보는 분석을 여러 지점 범위에서 물으면 `CLARIFICATION`(`BRANCH_REQUIRED`)을 돌려준다.

- `clarificationOptions`는 요청 범위 안의 지점마다 하나다. `id`는 `branch:{지점 ID}:{분석 ID}`, `label`은 지점 이름, `targetScope`는 지점 ID다.
- 그 `id`를 `clarificationChoice`로 다시 보내면 범위를 그 지점으로 좁혀 실행한다. 범위는 다시 검사하므로 권한 밖 지점의 `id`를 만들어 보내면 403이다.
- 범위가 한 지점이면 묻지 않고 바로 실행한다.

### 지점 이름과 기준 시각

- 질문 속 지점은 실제 지점 이름(전체 이름, 또는 "점"으로 끝나면 그 앞부분)으로 찾는다. 권한 없는 지점이면 그 이름을 응답에 담지 않고 거절한다.
- 기준 시각은 실제 현재 시각이다. `openstudy.ai.fixed-as-of`를 설정했을 때만 고정 시각을 쓴다(playback 재현·테스트, M10-D15).

변경 의도 질문(예: "예약 전부 취소해줘")은 실행하지 않고 400 `UNSUPPORTED_MODIFICATION_INTENT`로 끝난다. 질문은 `DENIED` 상태로만 저장되고 결과는 없다.

### 준비 상태(#93, ADR-M10-003)

`GET /ai/catalog/readiness`는 `ready`, `reasons`, `warnings`를 돌려준다.

- 다음이 모두 맞아야 `ready: true`다. 하나라도 어긋나면 카탈로그 전체가 준비 안 됨이다. 일부만 활성화하지 않는다.
  - 카탈로그의 모든 지표가 등록 분석과 연결된다. 등록 분석은 M9 공통 집계 서비스로 계산한다(M10-D05).
  - 모든 등록 분석에 연결된 지표가 있다.
  - 지표의 원천이 카탈로그에 있다.
  - 금지 원천(고객, 세션, 초대, 문의, 팀 채팅 테이블)이 카탈로그에 없다.
- 준비 안 됨이면 질문은 503 `AI_NOT_READY`(`status: NOT_READY`)로 끝난다. 다시 열기의 각 질문은 `REFUSED`·`AI_NOT_READY`이고, 실행 다시 조회는 503이다. 준비 상태는 언제든 다시 조회할 수 있다.
- 임베딩 모델이 없으면 의미 검색 후보만 빠지므로 `warnings`에 적고 준비 상태는 바꾸지 않는다.

### 중복 제출(#96)

질문 요청에 `clientRequestKey`(영문·숫자와 `.:_-`, 1~100자)를 보낼 수 있다.

- 같은 직원이 같은 키로 다시 보내면 새 실행을 만들지 않는다. 처음 저장된 질문을 현재 권한으로 다시 실행해 돌려준다. 이때 `executionId`가 같고 `behavior`는 `REPLAY`이며, 모델을 호출하지 않는다.
- 동시에 보내 경합해도 하나만 저장되고, 다른 요청은 저장된 질문을 받는다.
- 키는 직원마다 따로다. 형식이 틀리면 422 `VALIDATION_FAILED`다.

### 취소

`POST /ai/requests/{requestId}/cancel`은 본인 요청(요청 ID 또는 실행 ID)을 `CANCELED`로 표시한다. 질문 처리는 동기식이라 응답이 돌아온 뒤의 취소는 저장 상태만 바꾼다. 남의 요청은 404다.

## 대화(ADR-M10-002 M10-D12·D13)

- 대화는 만든 직원 본인만 조회·수정·삭제한다.
- 저장하는 것: 질문, 검증된 QuerySpec·ViewSpec, 처리 상태, 기준 시각, 질문의 부가 조건(환불 기준, 0원 채우기). **결과 행·합계·건수는 저장하지 않는다.**
- 목록은 마지막 질문 시각(`lastAskedAt`) 내림차순이다. 제목 변경은 순서를 바꾸지 않는다.
- 제목은 1~200자다. 벗어나면 422 `VALIDATION_FAILED`.
- 삭제하면 대화와 저장 질문이 함께 지워져 목록·다시 열기에서 빠진다.
- 진단 로그의 질문 원문은 30일 뒤 매일 지운다(M10-D04, `openstudy.ai.trace-retention.cron`, 기본 매일 03:30 Asia/Seoul). 요청 ID·단계·범위·판정 기록은 남는다.
- 자동 보관 기간은 #99 결정에 포함한다. 확정 전에는 사용자 삭제만 있다.

### 다시 열기

`GET /ai/conversations/{conversationId}?scope=`는 저장된 질문을 차례로(`turns`) 돌려준다. `scope`를 생략하면 대화의 범위를 쓴다.

- 실행됐던 질문은 저장된 명세를 **현재 권한·지점 범위**로 다시 검증하고 **새 기준 시각**으로 다시 실행한다. **모델을 호출하지 않는다.**
- 확인 질문·거절로 끝났던 질문은 결과 없이 당시 상태만 돌려준다.
- 실행할 수 없으면 결과 대신 사유를 돌려준다(`status: REFUSED`, `envelope: null`). 한 질문이 실행되지 않아도 나머지 질문은 그대로 다시 실행된다.

| 사유 코드 | 경우 |
| --- | --- |
| 권한 판정 코드(`AI_PERMISSION_DENIED`, `BRANCH_SCOPE_DENIED`, `SALES_PERMISSION_DENIED`, `AUTHORIZATION_SCOPE_CHECK`) | 권한 회수, 지점 소속 해제 |
| `ANALYSIS_UNAVAILABLE` | 의미 카탈로그가 그 분석을 더 제공하지 않음(#180) |
| `REOPEN_NOT_EXECUTABLE` | 그 밖의 이유로 지금 실행할 수 없음 |
| `AI_NOT_READY` | 카탈로그가 준비되지 않음 |

### 실행 다시 조회

`GET /ai/executions/{executionId}`는 저장된 실행을 원래 지점 범위로 다시 실행해 돌려준다. 결과는 저장본이 아니다.

- 권한이 없어졌으면 403이다.
- 실행된 질문이 아니면 422 `NOT_EXECUTABLE`이다.
- 카탈로그에서 빠진 분석은 422 `ANALYSIS_UNAVAILABLE`이다.

## 결과 구조

### QuerySpec

서버가 검증한 조회 명세다. `mode`는 `DETAIL`·`AGGREGATE`·`REGISTERED_ANALYSIS` 중 하나다. 현재 서버는 등록된 분석(`REGISTERED_ANALYSIS`, POC-001~010)만 실행한다.

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `id`, `source`, `grain`, `mode` | 예 | 명세 ID, 원천, 집계 단위, 실행 방식 |
| `dimensions`, `metrics` | 예 | `{field, alias}` 목록 |
| `filter` | 아니오 | 필터 |
| `orderBy` | 예 | `{alias, direction(ASC·DESC)}` 목록 |
| `pagination`, `limit` | 예 | `{page, size}`, 최대 행 수 |
| `analysisId` | 아니오 | 등록 분석 ID |

### ViewSpec

화면 명세다. `catalogVersion`은 `openstudy-ui-1.0.0`이고 컴포넌트 버전은 `1.0.0`이다.

- 허용 컴포넌트: `KPI`, `BAR_CHART`, `RANKED_BAR_CHART`, `LINE_CHART`, `DATA_TABLE`, `HEATMAP`, `SEAT_MAP`, `WEEK_CALENDAR`, `TASK_LIST`
- 각 컴포넌트: `id`, `componentRef{id, version}`, `datasetId`, `bindings`, `options`, `events`

### ResultEnvelope

서버가 만든 결과다. 모델 출력의 권한·대상은 들어가지 않는다.

- `datasets`: `TypedDataset{id, columns[{alias, type, unit, precision}], rows, totalElements, page, size, snapshotAt, asOf}`
- 값 형식(`type`): `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `DATE`, `TIMESTAMP`
- `asOf`(기준 시각), `snapshotVersion`, `policyVersion`, `warnings`, `notice`(사용자 안내)

### 상한(M10-D03)

- 한 결과 최대 1,000행, 표 페이지 50행, 차트 상위 20개 항목, 기간 366일 이하
- 상한에 걸리면 `truncation{isTruncated, warningCode(ROW_LIMIT_CLAMPED), message}`로 알린다.

### 옛 형식 결과(삭제됨)

옛 경로와 옛 형식 결과는 2026-11-08에 지웠다. 질문 응답의 `legacyExecution`과, 실행 다시 조회의 `rows`·`viewSpec`·`start`·`end`·`currency`·`timeZone`은 첫 AI 화면이 쓰던 형식이었다. 결과는 `envelope`(데이터셋·기준 시각)와 `viewSpec`으로만 받는다. 실행 다시 조회는 저장된 질문의 조건(`refundBasis`)과 상태를 함께 준다.

## 명세 생성 포트(M10-D01)

- 모델 호출은 `com.openstudy.domainlens.api.spi.ModelGateway`(질문 → IR 문서)와 `PlanningGateway` 뒤에 있다. 엔진이 무엇을 만들든 서버가 다시 검증하므로 실행 경계는 바뀌지 않는다.
- 입력: 정제된 질문과 등록된 의미 노드 ID. 출력: IR 문서(실행 가능 / 확인 필요 / 지원 불가).
- 모드(`openstudy.ai.model-mode`, `domainlens.model.mode`)
  - `playback`(기본): 녹화된 응답을 재생하는 **고정 생성기**다. 테스트와 개발에서 쓴다. 등록된 대표 질문(POC-001~010)과 부정 사례(NEG-001~018)만 재현하고, 그 밖의 질문은 규칙 기반 판정으로 처리한다. 실제 모델 품질은 검증하지 않는다.
  - `live`: 실제 모델(Qwen)을 부른다. API 키가 필요하다.
- 모델은 등록된 분석 ID와 정해진 부가 조건만 고를 수 있다(등록 분석 방식, [ADR-M10-003](../../db/ADR-M10-003.md) M10-D14). 범용 IR 생성·검증·실행은 연결돼 있지 않고 #97에서 다시 판단한다.
- 질문 처리에서 모델로 들어가는 입구는 하나다. 다시 열기와 실행 다시 조회는 이 입구를 거치지 않는다(#180 테스트로 확인).

## 요구사항 연결

- FR-NL-01·02·13·15·16·20·36·37, FR-VIZ-13·14, FR-AICHAT-01~08, NFR-OPS-07
- 상태 전달(SSE 이벤트·재연결)은 ADR-COM-001로 대체됐다. #92 인수 조건 중 SSE 항목은 적용하지 않는다.

## 남은 작업

- 업무 기록·CSV 반출·진단 로그·품질 평가 계약(M15·운영)
- 빈도 제한 값 확정(M10-D02)
- 실제 엔진 연동과 호환성 검증(#97, NFR-OPS-07)
