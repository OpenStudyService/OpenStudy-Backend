# 2026-11-08 AI 응답의 매출 수치 노출 수정

## 1. 배경 / 문제

- #92(AI API 계약)를 쓰려고 응답 구조를 읽다가 발견했다.
- AI 질문 응답(`POST /api/ai/queries`의 `legacyExecution`)과 실행 상세(`GET /api/executions/{id}`)에는 첫 AI 화면이 쓰던 옛 형식 결과가 들어간다.
- 이 옛 형식의 `rows`는 질문 종류와 상관없이 대상 지점의 이번 달 매출(승인액·환불액·순매출)을 계산해 담았다(`buildLegacyExecutionContract`).
- 매출이 아닌 질문(좌석·이용권·업무 등)은 `ai:read`만 확인하고 `sales:read`는 확인하지 않는다. 그래서 매출 조회 권한이 없는 직원이 이용권 질문을 하면 응답에 그 지점의 매출이 들어갔다.
- M10-D06("매출 질문은 `ai:read`와 대상 지점 모두의 `sales:read`가 필요하다")과 M9-D07의 지점별 매출 권한 기준에 어긋난다.
- 테스트로 재현했다: `ai:read`만 있는 직원의 이용권 질문 실행 상세에 `{"branchId":"2","approved":0,"refunds":0,"net":0}`이 나왔다.

## 2. 검토한 선택지

- 옛 형식 `rows`를 모든 질문에서 빼기: 가장 단순하지만, 첫 AI 화면의 매출 질문 표시(합계·지점 표)가 깨진다.
- 매출 질문일 때만 채우기: 매출 질문은 이미 대상 지점 모두의 `sales:read`를 확인했으므로 새 권한 판단이 필요 없다.

## 3. 결정 및 이유

- 매출 질문일 때만 `rows`를 채운다. 그 외 질문은 빈 목록이다.
- 방어적으로, 매출 질문이어도 요청자가 `sales:read`를 가진 지점만 계산한다. 정상 흐름에서는 대상 지점과 같다. 다만 판단이 어긋나도 권한 밖 지점의 매출이 나가지 않는다.
- 대상 지점이 비면 계산하지 않는다. 매출 집계는 빈 지점 목록을 "전체 지점"으로 해석하기 때문이다.

## 4. 구현 내용

- `AiWorkspaceService.buildLegacyExecutionContract`: 요청자와 매출 질문 여부를 받는다. `rows` 계산은 새 `legacyRows`로 분리했다.
  - 질문 처리: `"sales".equals(plan.intentDomain())`
  - 실행 상세: `"sales".equals(entity.getIntentDomain())`
- `AiConversationHistoryIntegrationTests`
  - `aQuestionAboutSomethingElseCarriesNoSalesFigures`: `ai:read`만 있는 직원의 이용권 질문은 질문 응답과 실행 상세 모두 `rows`가 비어 있고 매출 필드가 없다.
  - `aSalesQuestionStillCarriesItsBranchRows`: 매출 권한이 있는 직원의 매출 질문은 지점 행이 그대로 나온다.

## 5. 검증 결과

- 수정 전: 신규 테스트가 매출 행을 받아 실패(재현 확인)
- 수정 후: AI 모듈 테스트(대화 11개, 실행 본인 확인 2개) 통과, 모듈 경계·영속성 접근 테스트 통과
  - 로컬 전용 AI 테스트 23개는 건너뛰었다. 이 중 옛 형식 `rows`를 확인하는 곳(`OpenStudyAiLifecycleAndNegativeCasesTests`)은 OWNER의 매출 질문이라 동작이 바뀌지 않는다.
- 전체 `./gradlew test`: 1004개 중 실패 0, 건너뜀 25(로컬 전용 AI 테스트), 18분 20초
- **미검증**: Frontend 화면 확인. 첫 AI 화면은 매출이 아닌 질문을 결과 데이터셋으로 그리므로 영향이 없을 것으로 판단했다.

## 6. 남은 작업 / 보류 사항

- #92 계약에서 옛 형식 결과(`legacyExecution`, 실행 상세의 `rows`)를 폐지 예정으로 표시한다. Frontend가 결과 데이터셋만 쓰게 되면 제거한다.
- 운영 환경에서 노출이 실제로 일어났는지는 확인하지 않았다.
