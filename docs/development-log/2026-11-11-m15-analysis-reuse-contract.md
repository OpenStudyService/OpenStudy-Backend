# 2026-11-11 M15 분석 필터 수정·최근 질의·후속 질문·재조회·CSV 내보내기 계약 (#124)

## 1. 배경 / 문제

- M15(분석 사용성·재사용 확장)의 API 설계가 없었다. Backend #125~#127과 Frontend #46·#47이 같은 계약으로 병행하려면 먼저 확정해야 했다.
- ADR-M15-001(2026-10-29)은 범용 흐름을 전제로 썼다.
  - 범용 QuerySpec을 고치고, 세션에 실행 계획을 30분 두고, 진단 로그에서 최근 질의를 읽는다.
- 그 뒤 M10이 크게 바뀌었다.
  - ADR-M10-003(2026-11-08): 등록 분석 10개 중 하나를 고르는 방식. 기간은 분석마다 고정이고 질문에서 임의 기간을 읽지 않는다.
  - ADR-M10-002·COM-001: 대화 저장, 결과 미저장, SSE 제거
  - DomainLens에서 이식된 계약 밖 기능(반출 승인 등)이 서버에 남아 있다.
- 그래서 M15 결정을 지금 구조에 맞게 다시 적용해야 했다.

## 2. 검토한 선택지 (2026-11-11 사용자 결정)

| 항목 | 선택지 | 결과 |
| --- | --- | --- |
| 필터 수정 적용 | 분석별 수정 가능 조건(기간 직접 지정·지점·상품) / 지점과 기간 프리셋만 | 분석별 수정 가능 조건 |
| 실행 계획 재사용 | 저장 실행 재실행으로 대체 / 30분 세션 계획 유지 | 저장 실행 재실행 |
| CSV와 이식된 반출 승인 | 승인 없이 즉시 / OWNER 승인 후 | 승인 없이 즉시(M15-D09) |
| CSV 인코딩 | UTF-8 + BOM / UTF-8 BOM 없음 | UTF-8 + BOM(M15-D10) |

## 3. 결정 및 이유

- **필터 수정**
  - "등록 분석의 조건을 바꿔 다시 실행"으로 정의했다.
  - 분석마다 기간 최대 일수, 한 지점 여부, 상품 조건 여부를 정해 응답(`editableFilters`)으로 알려 준다.
  - 실시간 분석(빈자리, 급한 업무)은 기간을 고칠 수 없다. 상품은 이용권 분석(POC-003·006)만 고칠 수 있다.
  - 최대 일수(366·92·31)는 구현 기준이다. #125에서 비용을 측정해 낮출 수 있게 문서에 적었다.
- **실행 계획 재사용**
  - 서버에는 이미 저장된 실행을 모델 없이 현재 권한으로 다시 실행하는 기능(`getAiExecution`)이 있다.
  - 그래서 세션·30분 저장소(다중 인스턴스 공유 문제가 있다)를 두지 않고, 페이지·정렬·상세를 그 위에 query로 더했다.
  - "계획 만료" 오류는 없어졌다. 이슈 인수 조건의 그 항목은 이 결정으로 대체된다.
- **최근 질의**
  - 진단 로그가 아니라 대화에 저장된 질문을 30일 조건으로 읽는다.
  - 진단 로그 원문은 30일 뒤 지워지지만, 대화 질문은 사용자가 지울 때까지 남는다.
  - 재실행은 기존 `getAiExecution`을 쓴다. 새 operation을 만들지 않았다.
- **후속 질문**
  - 모델에는 이전 질문 원문이 아니라 직전 실행의 조건을 보낸다.
  - `scope`가 바뀌거나 이어받을 지점의 권한이 없어지면 문맥을 끊고 사유를 돌려준다.
  - 질문에서 임의 기간을 읽지 않는다는 M10 규칙은 그대로다.
- **CSV**
  - 화면 API와 같은 query로 다시 계산한다.
  - M9 집계는 `GET /sales/exports/{report}` 하나로, 자연어 결과는 `GET /ai/executions/{id}/export`로 둔다.
  - 수식 주입 방지는 문자열 값에만 적용한다. 숫자 열의 음수는 그대로 둔다(작은따옴표를 붙이면 숫자가 깨진다).
  - 탭·캐리지 리턴 시작 값도 함께 막았다(OWASP 권고).
- **기존 operation 변경 방식**: M13과 같이 `x-planned-changes`로 남겼다. 구현된 응답과 계약 필드를 비교하는 검사가 미리 깨지지 않게 하기 위해서다.

## 4. 구현 내용

- `docs/design/foundation/analysis-reuse-api.md`: 계약 문서
- `docs/design/foundation/openapi.json`(`0.1.16-m15-analysis-reuse`)
  - 새 operation 4개(`design-target`): `refineAiExecution`, `listAiRecentQueries`, `exportAiExecution`, `exportSalesReport`
  - 새 schema: `AiPeriod`, `AiConditions`, `AiEditableFilters`, `AiContext`, `AiRefinement`, `AiRecentQuery`, `AiRecentQueryList`
  - 계획 변경: `getAiExecution`의 query, `AiQueryResponse`·`AiExecution`·`AiConversationTurn`의 새 필드
- `docs/db/ADR-M15-001.md`: M15-D09·D10, 변경 이력(D01·D04·D05 적용 방식 변경)
- `docs/design/foundation/README.md`: M15 계약 안내
- 계약 테스트
  - 새 `AnalysisReuseContractTests`: 인수 조건 4개와 버전
  - `AiContractTests`: `ai` 태그 operation이 정확히 10개라는 검사에서 M15 operation을 뺐다.
  - `AccountContractTests`: 버전 고정을 "15 이상"으로 풀었다.

## 5. 검증 결과

- 실행함: `./gradlew test --tests "com.openstudy.architecture.*"` 통과
  - `AnalysisReuseContractTests` 5건
  - `AiContractTests` 7건
  - 그 밖의 계약·모듈 경계 테스트
- 실행하지 않음: 전체 `./gradlew test`. 문서·계약과 계약 테스트만 바뀌었고, 메인 코드는 바뀌지 않았다.
- `graphify update .`는 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- **#125**: 분석별 최대 일수 측정·확정. 필터 수정 시 등록 분석 실행기에 기간·지점·상품 매개변수 추가.
- **#126**: 직전 실행 조건을 모델 입력에 넣는 방식. 정규화 규칙과 대표 변형 질문 세트.
- **#127**: 머리말 문장의 정확한 표기. 보고서별 열 이름.
- **별도 이슈로 만들 것**: 이식된 반출 승인(`/api/ai/approvals`) 제거(M15-D09)
- **확인할 의존성**: #125~#127 본문의 의존성(#94~#96)은 ADR-M10-003으로 해석이 바뀌었으니 착수 전에 확인한다.
