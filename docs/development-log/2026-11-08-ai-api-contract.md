# 2026-11-08 자연어 분석(AI 채팅) API 계약 (#92)

## 1. 배경 / 문제

- AI 기능은 DomainLens 이식 코드(#169)와 #172·#178·#180·#182로 구현됐지만 API 계약(`openapi.json`)에는 하나도 없었다.
- #92 본문이 예정한 `nl-analysis-api.md`도 없었다. 2026-11-06 후속 정책 변경 기록도 "존재하지 않는 문서를 구현 완료 근거로 삼지 않는다"고 남겼다.
- Frontend #71(대화 목록 화면)과 #32(실제 API 연결)가 근거로 삼을 계약이 필요했다.
- 서버에는 AI 엔드포인트가 20개 있었다. 같은 기능의 옛 경로(`/api/conversations` 등)와 새 경로(`/api/ai/*`)가 함께 있고, 업무 기록·CSV 반출 승인·진단 로그·품질 평가 같은 이식된 부가 기능도 있었다. Frontend는 두 종류의 경로를 섞어 쓰고 있었다.

## 2. 검토한 선택지

사용자에게 두 가지를 물었다(2026-11-08).

- **계약 범위**
  - AI 채팅 핵심 10개만 정식 계약(선택)
  - 20개 전부: 부가 기능의 권한 기준을 이번에 함께 정해야 해서 범위가 커진다.
- **옛 경로**
  - 계약은 `/api/ai/*`만, 옛 경로는 서버에 유지(선택): Codex가 작업 중인 Frontend가 깨지지 않는다.
  - 이번에 옛 경로 삭제: Frontend 수정이 함께 필요하다.

## 3. 결정 및 이유

- 정식 계약은 준비 상태, 대화 생성·목록·다시 열기·제목 변경·삭제, 질문(전체 응답·접수 응답), 취소, 실행 다시 조회의 10개다. 모두 이미 구현돼 있어 `implemented`로 표시했다.
- 옛 경로는 문서에 "폐지 예정"으로만 남기고 OpenAPI에는 넣지 않았다.
- 부가 기능 4종은 문서에 목록만 남기고, M15(#124·#127)와 운영 범위에서 계약한다.
- SSE·상태 polling은 ADR-COM-001로 대체됐으므로 계약에 없다. #92 인수 조건의 SSE 항목은 적용하지 않는다고 문서에 적었다.
- 옛 형식 결과(`legacyExecution`, 실행 다시 조회의 `rows` 등)는 `deprecated`로 표시했다. 매출 행은 #182 기준(매출 질문, `sales:read` 지점만)을 적었다.
- 계약 문서와 실제 코드가 어긋나지 않도록 계약 테스트가 컨트롤러 매핑을 직접 읽어 대조하게 했다.
- OpenAPI 버전을 `0.1.12-m10-ai-chat`으로 올렸다. 스키마(DB) 변경은 없다.

## 4. 구현 내용

- `docs/design/foundation/nl-analysis-api.md`(신규)
  - 범위, 공통 규칙(권한·`scope`·빈도 제한·ID·본인 범위), 질문 처리 순서, 응답 상태, 실패 코드
  - 대화 규칙(M10-D12·D13), 다시 열기 사유 코드(`ANALYSIS_UNAVAILABLE`, `REOPEN_NOT_EXECUTABLE`, 권한 판정 코드)
  - QuerySpec·ViewSpec(허용 컴포넌트 9종)·ResultEnvelope 구조, 상한(M10-D03), 옛 형식 결과
  - 명세 생성 포트(`ModelGateway`·`PlanningGateway`)와 고정 생성기(`playback`)의 범위·한계
- `openapi.json`: `ai` 태그, operation 10개, 스키마 22개(`AiQueryResponse`, `AiQuerySpec`, `AiViewSpec`, `AiResultEnvelope`, `AiLegacyExecution` 등), `info.description`·버전
- `README.md`: 2026-11-08 항목
- `AiContractTests`(신규, 6개)
  - 10개 operation이 `ai` 태그로 `implemented`이고 문서에 있으며, 쓰기 operation은 CSRF를 요구한다.
  - 옛 경로와 부가 기능은 계약에 없다.
  - 계약의 모든 경로를 `AiWorkspaceController`가 실제로 제공한다(어노테이션 대조).
  - 모든 오류 코드와 다시 열기 사유 코드가 문서에 있다.
  - 옛 형식 결과가 `deprecated`이고 매출 행의 권한 기준이 적혀 있다.
  - 스트림 응답이 없다.
- `AggregationContractTests`: 버전 고정 단언을 "M9 이후 버전"으로 바꿨다. "자연어 경로 없음" 단언은 `/ai/*`가 M10 `ai` 태그에만 있는지로 바꿨다.
- **로컬 샘플 `GET /api/home` 제거**: Frontend #31(PR #81)이 `/api/home/summary`로 바꿨기 때문이다.
  - `LocalPreviewController`에서 핸들러와 `HOME` 목록을 지웠다.
  - `LocalPreviewIntegrationTests`의 홈 검사를 `/api/local-preview/lookups` 검사와 "`/api/home`은 404" 검사로 바꿨다.
  - `AuthenticationIntegrationTests`의 "기본 프로필에 샘플 없음" 확인 경로를 `/api/local-preview/lookups`로 바꿨다.
  - `aggregation-api.md`에 2026-11-08 기록을 더했다.

## 5. 검증 결과

- 아키텍처·계약 테스트 10개 클래스, `LocalPreviewIntegrationTests`, `AuthenticationIntegrationTests`, `AiConversationHistoryIntegrationTests` 통과
- 전체 `./gradlew test`: 1010개 중 실패 0, 건너뜀 25(로컬 전용 AI 테스트), 18분 1초
- **미검증**
  - Frontend 생성 타입(`foundation.generated.ts`) 재생성 결과. Frontend 작업에서 확인한다.
  - 문서의 상태·오류 코드 표는 코드(`OpenStudyPlaybackGateway`, `OpenStudyUserContextResolver`, `AiWorkspaceService`)를 읽어 만들었다. 모든 코드를 HTTP로 재현하지는 않았다(입력·변경 의도·권한·다시 열기 사유는 기존 테스트가 있다).

## 6. 남은 작업 / 보류 사항

- 옛 경로와 옛 형식 결과 제거(Frontend가 `/api/ai/*`·`envelope`로 옮긴 뒤)
- 부가 기능(업무 기록·CSV 반출·진단 로그·품질 평가) 계약과 권한 기준(M15·운영)
- M10 #93~#97: 이식 코드에 해당 기능이 들어 있지만, 인수 조건별 검증은 하지 않았다. 각 이슈를 닫기 전에 따로 대조해야 한다.
  - #96의 SSE·재연결 항목은 ADR-COM-001로 대체됐다.
  - #96의 "같은 클라이언트 요청 키의 중복 제출" 방지는 현재 질문 요청에 요청 키가 없다.
- 질문 처리(`executeQuery`)는 계획 단계의 권한 판정 뒤에 같은 판정을 한 번 더 부르지만 결과의 허용 여부를 보지 않는다. 계획 단계에서 이미 막으므로 우회는 아니다. 정리 대상으로만 남긴다.
