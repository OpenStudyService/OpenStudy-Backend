# 2026-11-13 AI 생성 결과의 context와 CSV 내보내기 정리

## 1. 배경 / 문제

#122 브라우저 확인(실제 Qwen, 확인용 DB)에서 생성 결과의 문제를 찾았고, 사용자 요청으로 최신 `main`(550fc90) 기준으로 남은 것만 처리했다.

| 문제 | 최신 main 상태 | 처리 |
|---|---|---|
| 1. 생성 답변의 `context`가 빈 객체 `{}` | 미반영. 수정 커밋 `9e2bb65`는 push 전이었다. | 이 브랜치에 cherry-pick(`6019d64`). 원래 브랜치 `fix/ai-generated-answer-context`는 그대로 보존 |
| 2. CSV 열 이름이 `d0`, `m0(KRW)` | 미반영 | 이 브랜치에서 수정 |
| 3. CSV 조회 조건에 `환불 기준 처리일` | 미반영 | 이 브랜치에서 수정 |
| 4. 화면의 데이터셋 이름 `q0` | 프론트엔드 미반영 | 프론트엔드 별도 브랜치·PR(`fix/generated-result-dataset-name`) |

## 2. 검토한 선택지

- **CSV 열 이름**
  - 엔진 컴파일러가 별칭을 사람이 읽는 이름으로 짓는다: 별칭은 정렬·페이지 조회의 키로도 쓰여 바꾸면 영향이 크다.
  - CSV를 만들 때 저장된 쿼리의 선택 항목으로 이름을 찾는다(선택). 지표는 이름표, 항목은 카탈로그 이름(`SemanticField.name`)을 쓴다.
- **CSV 조회 조건**: 등록 분석용 문장(분석 ID·데이터셋 ID·환불 기준) 대신 생성 결과용 문장을 따로 만든다(선택).

## 3. 결정 및 이유

- 환불 기준은 등록 매출 분석의 조건이다. 생성 결과는 승인 결제 지표라 해당하지 않는다. 그래서 응답 조건(`conditions.refundBasis`)과 CSV 조건에서 모두 뺐다.
- CSV 기간은 다시 실행한 실제 기간(상대 기간은 지금 시각으로 다시 계산)을 한국 날짜로 쓴다.

## 4. 구현 내용

- `AiWorkspaceService`
  - 생성 답변의 context: 이전 결과를 잇지 않는 기본값(`resetContext(NO_PRIOR_RESULT)`). 9e2bb65 cherry-pick
  - CSV 내보내기: 생성 결과면 열 이름을 선택 항목 이름으로 바꾸고("지점명", "결제 승인액"), 조회 조건을 "AI 생성 조회 · 지표 … · 묶음 … · 기간 YYYY-MM-DD~YYYY-MM-DD (Asia/Seoul) · 지점 …"으로 쓴다. 감사 기록의 기간도 같은 값이다.
  - `conditionsOf`: 분석 ID가 없으면(생성 결과) 환불 기준 없음
- `AiGenerationService`: 지금 실행할 쿼리(`current`), 표시 이름(`displayName`, `columnNames`), 기간 문구(`periodText`)

## 5. 검증 결과

- `AiGenerationPathIntegrationTests`
  - 생성 답변 context 형식(유지·변경 목록, 초기화 아님)
  - CSV 머리글 "지점명,결제 승인액(KRW)"(`d0,m0` 없음), 조건에 지표·묶음·이번 달 기간이 있고 환불 기준·데이터셋 ID가 없음
  - 결과 조회 응답의 `conditions.refundBasis`가 없음
- `./gradlew test --tests "com.openstudy.modules.ai.*" --tests "com.openstudy.domainlens.*" --tests "com.openstudy.common.export.*"`: 119개 중 실패 0, 건너뜀 25(기존 opt-in)
- 전체 테스트 결과는 PR에 적는다.
- 실행하지 않은 검증: 브라우저 확인

## 6. 남은 작업 / 보류 사항

- 등록 분석 CSV의 열 이름도 영문 별칭(`approved_krw` 등)이다. 이번 범위(생성 결과)가 아니다.
- 표시 이름을 응답에도 실어 보내면 프론트엔드 이름표(`FIELD_LABELS`)를 지울 수 있다. 지표 이름표는 지금 백엔드(`AiGenerationService.METRIC_NAMES`)와 프론트엔드에 각각 있다.
