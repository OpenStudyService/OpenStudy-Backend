# 2026-11-08 옛 AI 경로·옛 형식 결과 삭제

## 1. 배경 / 문제

- DomainLens 이식(#169) 때 AI API는 같은 기능을 두 벌의 경로로 제공했다.
  - 옛 경로: `/api/conversations`, `/api/conversations/{id}/requests`, `/api/executions/{id}`, `/api/catalog/readiness`, `/api/requests/{id}/cancel`
  - 새 경로: `/api/ai/*`
- 첫 AI 화면이 쓰던 옛 형식 결과도 함께 남아 있었다. 질문 응답의 `legacyExecution`과, 실행 다시 조회의 `rows`·`viewSpec`·`start`·`end`·`currency`·`timeZone`이다.
- #92 계약(2026-11-08 사용자 결정)은 `/api/ai/*`만 정식 경로로 두고, 옛 경로와 옛 형식은 Frontend가 옮긴 뒤 지우기로 했다.
- Frontend #84(업무 AI 채팅 계약 정리)가 병합돼 옮기기가 끝났다.
  - Frontend main(`8209d56`)에서 옛 경로 호출은 0건이다.
  - `legacyExecution`은 다시 생성할 타입 파일에만 남아 있다.
- 옛 형식 결과는 매출 질문이 아니어도 매출 행을 만들던 곳이었다(#182). 지우면 그 경로 자체가 사라진다.

## 2. 검토한 선택지

- 옛 경로를 남기고 계속 `deprecated` 표시만 유지: 화면이 더 쓰지 않는데 같은 기능의 진입점이 두 벌로 남고, 권한 검사도 두 곳을 지켜야 한다.
- **옛 경로와 옛 형식을 지우기(선택)**: Frontend #84가 남긴 삭제 목록 그대로 지운다.
- 실행 다시 조회의 `refundBasis`는 Frontend #84가 옛 형식 목록에 넣었지만 남겼다. 옛 화면 형식이 아니라 저장된 질문의 조건(원결제일 기준 여부, #168)이고, 다시 열기 조건 보존 테스트가 이 값을 확인한다.

## 3. 결정 및 이유

- 옛 경로 5개는 매핑을 지워 404가 된다.
- 질문 응답에서 `legacyExecution`을 지웠다.
- 실행 다시 조회는 `id`, `requestId`, `conversationId`, `status`, `question`, `pocCode`, `analysisId`, `intentDomain`, `resultCategory`, `refundBasis`, `envelope`, `querySpec`만 준다.
- 옛 형식 매출 행을 만들던 코드(`buildLegacyExecutionContract`, `legacyRows`)를 지웠다.

## 4. 구현 내용

- `AiWorkspaceController`: 매핑을 `/api/ai/*` 하나씩으로 줄였다.
- `AiWorkspaceService`
  - `AiQueryResponse`에서 `legacyExecution`을 지웠다.
  - 실행 다시 조회 응답을 위 필드로 정리했다.
  - 옛 형식 생성 코드를 지웠다.
- 계약
  - `openapi.json`: `AiLegacyExecution` 스키마와 `legacyExecution` 속성을 지웠고, `AiExecution`을 새 필드로 정리했다.
  - `nl-analysis-api.md`: 옛 경로·옛 형식을 "2026-11-08 삭제"로 바꿨다.
- 테스트
  - `AiExecutionOwnershipIntegrationTests`
    - 새 경로로 바꿨다.
    - 옛 경로 5개가 작성자 본인에게도 404임을 확인한다.
  - `AiConversationHistoryIntegrationTests`
    - 실행 다시 조회를 새 경로로 바꿨다.
    - 매출 아닌 질문에 매출 형태의 값이 없음을 확인한다.
    - 매출 질문은 `envelope`의 `branchSales` 데이터셋으로 지점 매출을 받는지 확인한다.
  - `AiContractTests`: 옛 형식이 계약에서 사라졌는지 확인한다.
  - 로컬 전용 `OpenStudyAiLifecycleAndNegativeCasesTests`: 옛 형식 대신 `envelope`를 확인하도록 바꿨다.

## 5. 검증 결과

- AI 모듈·아키텍처·계약·매출 동등성·워크스페이스 테스트 통과
- 전체 `./gradlew test`: 1029개 중 실패 0, 건너뜀 25(로컬 전용 AI 테스트). 32분 20초가 걸렸는데, 다른 세션(#89)의 테스트와 동시에 돌았기 때문으로 보인다(단독 실행은 약 16분).
- **미검증**
  - Frontend는 `foundation.generated.ts`를 다시 생성해야 `AiLegacyExecution` 타입이 사라진다(동작에는 영향 없음).
  - 운영 환경

## 6. 남은 작업 / 보류 사항

- Frontend 생성 타입 재생성(다음 Frontend 작업에 포함)
- M10에서 열린 이슈는 #97(실제 엔진·모델 연동 검증)뿐이다.
