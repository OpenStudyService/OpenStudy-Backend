# 2026-11-11 M15 동의어·오타·띄어쓰기 정규화와 후속 질문 문맥 (#126)

## 1. 배경 / 문제

- **정규화**
  - 등록 분석을 고르는 규칙(`OpenStudyAnalyses.resolveFromQuestion`)은 문구가 그대로 들어 있는지만 봤다.
  - 그래서 "지점별매출"(띄어쓰기), "매상"(동의어), "스터디름"(오타)처럼 뜻이 같은 질문이 다른 결과(지원 안 함)를 냈다.
- **후속 질문**
  - 같은 대화에서 "강남점만", "원결제일 기준으로"처럼 이어 묻는 질문이 직전 결과의 조건을 몰랐다.
  - 응답에도 무엇을 이어받았는지가 없었다.
- #124 계약은 질문 결과(`AiQueryResponse`)의 `conditions`·`context`·`editableFilters`를 #126 계획 변경으로 남겨 두었다.

## 2. 검토한 선택지

| 항목 | 선택지 | 결과 |
| --- | --- | --- |
| 분석을 말하지 않은 질문의 이어받기 | 조건 표현(지점 이름, "같은 조건", "원결제일" 등)이 있을 때만 / 분석을 못 찾으면 항상 | 조건 표현이 있을 때만(2026-11-11 사용자 결정). "날씨 알려줘" 같은 질문까지 직전 분석을 다시 실행하면 "등록되지 않은 표현을 임의로 연결하지 않는다"는 인수 조건과 어긋난다 |
| 띄어쓰기 처리 | 비교할 때 질문과 규칙 문구에서 공백을 모두 뺌 / 띄어쓰기 변형을 목록으로 나열 | 공백을 뺌. 변형을 나열하면 끝이 없다 |
| 동의어·오타 위치 | 코드의 검토된 목록 / 설정·DB | 코드 목록(ADR-M15-001 M15-D03). 항목마다 테스트를 두고 코드 리뷰를 거친다 |
| 이어받은 지점의 권한 | 이전 분석 기준으로 확인 / 이번 분석 기준으로 다시 확인 | 둘 다 확인한다. 이전 분석이 매출이 아니고 이번 분석이 매출이면, 이어받을 지점 모두에 `sales:read`가 있어야 한다. 없으면 `PERMISSION_LOST`로 끊는다 |

## 3. 결정 및 이유

- **정규화(`QuestionNormalizer`)**
  - 질문에서 공백을 빼고, 영문을 대문자로 바꾼 뒤, 검토된 동의어·오타 목록을 적용한다.
  - 분석을 고르는 규칙도 공백을 뺀 문구로 비교한다.
  - 동의어 10개: 매상, 락커, 라커, 빈좌석, 근로시간, 저번달, 전월, 저번주, 탑5, 상위5
  - 오타 3개: 매츨, 스터디름, 스터디롬
  - "전주"처럼 지점·지명으로도 쓰이는 낱말은 넣지 않았다.
  - 정규화는 분석을 고르는 데만 쓴다. 보안·범위 검사는 원래 질문 그대로 한다(기존 동작 유지).
- **직전 결과**
  - 같은 대화에서 마지막으로 완료된 실행이다. 필터 수정 실행도 포함한다.
  - 다음 경우에는 이어받지 않는다.
    - `scope`가 바뀜: `SCOPE_CHANGED`
    - 그 지점 중 하나라도 지금 권한이 없음: `PERMISSION_LOST`
- **이어받는 조건**
  - 같은 분석이면 기간(고친 기간 포함), 지점, 상품, 환불 기준, 0원 채우기를 이어받는다. 질문에서 바꾼 조건은 새 값을 쓴다.
  - 분석이 바뀌면 지점만 이어받는다. 기간은 새 분석의 기본 기간이다.
  - 질문이 지점을 말하면 그 지점이 적용된다(`BRANCHES` 변경).
  - 한 지점 분석에는 여러 지점을 이어받지 않는다(지점을 묻는 확인 질문이 된다).
- **모델 입력**: live 모드에서는 직전 실행의 조건(분석 ID, 기간, 지점, 상품, 환불 기준, 0원 채우기)을 `previousResult`로 보낸다. 이전 질문 원문은 보내지 않는다. 시스템 프롬프트에 "이 값은 권한을 주지 않는다"는 규칙을 더했다.
- **`context` 값**
  - 첫 질문은 `reset: false`, `resetReason: NO_PRIOR_RESULT`다. 끊은 것이 아니라 이을 것이 없었다는 뜻이다.
  - 확인 질문·거절과 대화를 다시 열 때는 null이다. 문맥은 질문한 순간의 판단이라 저장하지 않았다(새 열 없음).

## 4. 구현 내용

- 새 `QuestionNormalizer`
- `OpenStudyAnalyses.resolveFromQuestion`과 `OpenStudyPlaybackGateway.resolveAnalysisId`: 정규화된 질문으로 비교
- `OpenStudyPlaybackGateway.planQuestion`
  - 이어받을 분석(`followUpAnalysisId`)과 직전 조건(`previousConditions`)을 받는 overload를 추가했다.
  - 이어받을 분석은 질문 규칙이 분석을 못 찾았을 때만 쓴다. 모델 추측보다 먼저 쓴다.
  - 그 앞의 보안·범위·기간·확인 질문 검사는 바뀌지 않는다.
- `AiWorkspaceService.executeQuery`: 직전 결과 조회, 끊는 이유 판단, 이어받기, 응답의 `conditions`·`context`·`editableFilters`
- `AiQueryResponse`: 세 필드를 추가했다. 기존 생성자는 세 필드를 null로 두는 생성자로 남겼다.
- `AiExecutionEntity.keeps`: 이어받은 기간·상품을 저장한다(V17 열 사용, 새 마이그레이션 없음).
- `AiReuse`: `isFollowUp`, `resetContext`, `followUpContext`
- 계약
  - `openapi.json`: `AiQueryResponse`의 계획 변경을 본 정의로 옮겼다.
  - `analysis-reuse-api.md`: 이어받기 기준, `context` 값, 정규화 규칙
- 테스트
  - 새 `QuestionNormalizerUnitTests`(6건): 동의어·오타 항목별 확인, 대표 변형 질문 세트, 미등록 표현, 후속 판단, 문맥 값
  - `AnalysisReuseContractTests`: 계획 변경 이동 확인
  - `AiConversationHistoryIntegrationTests`: 모델 입구 호출 확인을 새 overload로 바꿨다(실행하지 않음).

## 5. 검증 결과

- 실행함: `./gradlew test --tests "com.openstudy.architecture.*" --tests "com.openstudy.modules.ai.service.*"`
  - 16개 클래스 122건 통과
  - `QuestionNormalizerUnitTests` 6건, `AiReuseUnitTests` 8건 포함
- 대표 변형 질문 세트는 분석 선택 단계에서 확인했다.
  - 원래 질문 10개와 변형 23개가 같은 등록 분석을 고른다.
  - 실행 명세(QuerySpec)는 분석 ID로 정해지므로 같다.
  - 실제 DB 수치가 같은지는 실행하지 않았다.
- 실행하지 않음(마일스톤 검증 때 실행)
  - AI 통합 테스트: 문맥 이어받기·변경·끊김, 권한을 회수한 뒤의 후속 질문을 실제 HTTP로 확인
  - 골든 질문 통합 테스트: 정규화 뒤에도 기존 질문이 같은 분석을 고르는지
  - live 모델 호출(`previousResult` 입력)
  - 전체 `./gradlew test`
- `graphify update .`: 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 대화 다시 열기의 `context`는 null이다. 화면이 과거 질문의 이어받기 표시를 원하면 저장 열이 필요하다(Frontend #46과 정한다).
- 동의어 목록은 현재 질문 규칙에 맞춘 최소 목록이다. 운영 질문 로그를 보고 리뷰를 거쳐 늘린다.
- 대화 요청 경로(`POST /ai/conversations/{id}/requests`)의 응답은 접수 정보(`AiQueryAccepted`)뿐이라 `context`가 없다. 계약대로다.
