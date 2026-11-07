# AI 실행 접근 본인 확인 (2026-11-07)

## 1. 배경 / 문제

#170(SSE 제거, PR #175) 작업 중 AI 실행 ID로 접근하는 경로에 본인 확인이 없다는 것을 확인했다.

- `POST /api/ai/requests/{id}/cancel`, `POST /api/requests/{id}/cancel`: 인증 정보를 받지 않았다. 다른 직원의 실행 ID를 알면 상태를 `CANCELED`로 바꿀 수 있었고, 없는 ID에도 200을 돌려줬다.
- `GET /api/executions/{id}`, `GET /api/ai/executions/{id}`: 실행에 기록된 지점 권한만 다시 확인했다. 같은 지점 권한이 있는 다른 직원은 남의 질문·명세·응답을 읽을 수 있었다.
- `POST /api/ai/records`(작업 기록 생성), `POST /api/ai/approvals`(CSV 반출 승인 요청): 본문의 `executionId`로 남의 실행을 찾아 내 기록·반출 요청으로 만들 수 있었다.

대화 API(#168, PR #172)는 다른 직원의 대화에 404를 준다. 실행은 대화의 일부이므로 같은 규칙이 맞다.

## 2. 검토한 선택지

- **OWNER 예외를 둘지**: 작업 기록·반출 승인·진단 로그 목록은 OWNER가 전체를 본다. 그러나 ADR-M10-002 M10-D12는 "대화는 만든 직원 본인만 본다"로 정했고 예외가 없다. 실행 결과는 대화 내용 그 자체다. OWNER의 진단 필요는 기존 진단 로그(`/api/ai/traces`) 경로가 맡는다.
- **거부 응답 403 vs 404**: 403은 실행의 존재를 드러낸다. 대화 API와 같이 404로 맞췄다.
- **없는 ID 응답**: 기존에는 조회가 422(`NOT_FOUND`), 기록·반출 요청이 422(`VALIDATION`), 취소가 200이었다. 남의 실행과 없는 실행이 같은 응답이어야 존재가 드러나지 않으므로 모두 404로 통일했다.

## 3. 결정 및 이유

- 실행 ID로 접근하는 네 경로(조회·취소·작업 기록 생성·반출 승인 요청)는 실행한 직원 본인만 허용한다. OWNER도 예외가 아니다(M10-D12).
- 본인이 아니거나 없는 실행은 모두 404 `NOT_FOUND`다.
- 정책이 ADR에 이미 있어 사용자에게 따로 묻지 않았다.

## 4. 구현 내용

- `AiWorkspaceService.ownExecution(employeeId, id)`: 실행 ID 또는 요청 ID로 찾고 `employeeId`가 다르면 404. 네 경로가 이것을 쓴다.
- `cancelRequest`가 `Authentication`을 받는다(컨트롤러 시그니처 변경). 취소는 본인 실행일 때만 상태를 바꾼다.
- 지점 권한 재확인(`verifyBranchReaccess`)은 그대로 둔다. 본인이어도 권한이 회수되면 거부된다.
- SSE 경로(`/api/ai/requests/{id}/events`)는 PR #175로 이미 지워졌다. 작업 중 #175가 main에 병합되어 그 위로 다시 올렸다.

## 5. 검증 결과

- 새 `AiExecutionOwnershipIntegrationTests`(`@MySqlIntegrationTest`, HTTP): 같은 지점·같은 권한의 다른 직원과 OWNER가 실행 ID·요청 ID로 조회(두 경로)·취소(두 경로)·작업 기록 생성·반출 요청을 하면 모두 404, 실행 상태는 `READY` 그대로, 기록·반출 행이 생기지 않음. 본인은 조회·기록 생성·취소가 200이고 상태가 `CANCELED`가 됨. 없는 ID는 404 — 통과.
- `AiConversationHistoryIntegrationTests`(#176 이후 5개) 통과.
- `OpenStudyAiLifecycleAndNegativeCasesTests`는 로컬 AI 픽스처 조건(`@EnabledIf`)으로 건너뛰어 이번에 실행되지 않았다.
- 전체 `./gradlew test`(최종 기준 `7aaaa1a` 위, 1시간 25분): 989개 중 160개 실패, 25개 건너뜀. 실패는 모두 예약·좌석·매출·출결 통합 테스트의 Spring 컨텍스트 로딩 오류(`IllegalStateException`, `UnsatisfiedDependencyException`)였고 AI 테스트는 없었다.
- 실패한 11개 클래스(160개)를 따로 다시 돌리면 모두 통과했다. 긴 전체 실행에서 컨텍스트가 여럿 쌓일 때 생기는 환경 문제로 보이며 이번 변경과 관련 없다고 판단했다. 같은 시점 main에서 전체 실행을 비교하지는 않았다(미검증).

## 6. 남은 작업 / 보류 사항

- `GET /api/ai/records/{id}`(작업 기록 열기)는 기록 작성자를 확인하지 않고 지점 권한만 다시 본다. 목록은 OWNER가 전체를 보도록 되어 있어 기록의 공개 범위는 따로 정해야 한다.
- `GET /api/ai/approvals/{id}/download`(CSV 다운로드)는 승인 요청자·승인자 확인 없이 지점 권한만 본다. 같은 이유로 정책 결정이 필요하다.
- 작업 중 AI 결과 미저장 변경(#173이 #176으로 main에 병합)이 들어와 그 위로 다시 올렸다. `getExecution` 설명 주석 자리에서 충돌이 나서 두 쪽을 모두 살렸다. 조회는 이제 본인 확인 뒤 저장된 분석을 다시 실행한다.
- Frontend가 취소·조회의 422/200 응답에 기대고 있는지 확인이 필요하다.
