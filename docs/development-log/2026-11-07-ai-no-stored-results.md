# 2026-11-07 AI 조회 결과 미저장(M10-D12 정합)

## 1. 배경 / 문제

- 이식된 DomainLens 백엔드(#169)는 AI 조회 결과 자체를 DB에 저장했다.
  - `ai_executions.envelope_json`·`legacy_execution_json`: 결과 데이터셋과 화면용 결과
  - `ai_work_records.snapshot_json`: 작업 기록의 결과 사본
- ADR-M10-002 M10-D12는 "질문·검증된 QuerySpec/ViewSpec·처리 상태·기준 시각만 저장하고 결과 데이터는 저장하지 않는다"이다.
  - 권한이 회수된 뒤에도 과거 수치가 남아 보이는 일을 막는다.
  - 결과 행이 보존·파기 대상이 되지 않게 한다.
- 대화 기록 PR(OpenStudyService/OpenStudy-Backend#172)에서 이 차이를 후속으로 남겼고, 사용자가 정책대로 제거하기로 했다.

## 2. 검토한 선택지

- 정책대로 제거 / 이슈로만 남기고 유지: 사용자가 제거를 골랐다.

## 3. 결정 및 이유

- 결과 칼럼 세 개를 지운다(V8). 이미 저장된 값도 함께 사라진다.
- 결과가 필요한 곳은 대화 다시 열기(#172)와 같은 방식으로 다시 실행한다.
  - 저장된 분석 ID를 현재 권한으로 다시 확인한다(`verifyBranchReaccess`).
  - 같은 지점 범위로 다시 실행하고, 새 기준 시각을 쓴다.
- 결과가 필요한 곳은 세 군데다.
  - `GET /api/executions/{id}`
  - 작업 기록 열기(`GET /api/ai/records/{id}`)
  - CSV 반출: 승인 요청의 행 수 계산과 승인된 CSV 다운로드. CSV는 다운로드 시점의 데이터가 된다.

## 4. 구현 내용

- `V8__ai_no_stored_results.sql`
  - `ai_executions`에서 `envelope_json`·`legacy_execution_json`을 지운다.
  - `ai_work_records`에서 `snapshot_json`을 지운다.
- 엔티티·저장 경로에서 결과 필드를 지웠다(`AiExecutionEntity`, `AiWorkRecordEntity`, `persistExecutionAndTrace`).
- `AiWorkspaceService.rerunSaved`
  - 실행된 질문(READY)을 현재 권한으로 다시 확인한 뒤 같은 지점 범위로 다시 실행한다.
  - 실행된 적 없는 질문(확인 필요·거부)은 422(`NOT_EXECUTABLE`)로 응답한다.
- 작업 기록을 열 때 원본 질문이 지워졌거나 다시 실행할 수 없으면, 결과 대신 `snapshotUnavailableReason`을 돌려준다.
- 마이그레이션 테스트의 최신 버전 기대값을 V8로 바꿨다. 테이블 수(73)는 그대로다.

## 5. 검증 결과

- 신규 통합 테스트(`noResultIsStoredAndEveryResultIsRunAgainUnderTheCurrentPermissions`)를 추가했다.
  - 결과 칼럼이 없다.
  - 실행 재조회와 작업 기록 열기가 결과를 다시 실행해 돌려준다.
  - 대화를 지우면 작업 기록에 사유만 남는다.
  - 매출 권한이 없는 직원의 실행 재조회는 403이다.
- 영향받는 테스트(대화 기록·마이그레이션·DomainLens) 통과
- 전체 `./gradlew test`(88개 스위트): 1001개 중 978개 통과, 23개 건너뜀(로컬 전용 AI 테스트), 실패 0
- **미검증**: CSV 반출 승인·다운로드 흐름의 HTTP 테스트(기존에도 로컬 전용 테스트에만 있었다)

## 6. 남은 작업 / 보류 사항

- 같은 질문이라도 다시 열 때마다 새 기준 시각으로 조회하므로, 작업 기록·CSV의 수치가 처음 조회 때와 다를 수 있다. 정책이 의도한 동작이다.
- 원래 질문의 부가 조건(원결제일 기준 환불, 0원 채우기)은 저장되지 않아 기본값으로 다시 실행한다(#172와 같음).
- 마이그레이션 버전은 병합 직전에 main의 최대 버전 + 1로 맞춘다(V8).
