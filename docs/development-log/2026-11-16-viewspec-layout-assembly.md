# 2026-11-16 ViewSpec 배치를 결과 목업 방식으로 구성

## 1. 배경 / 문제

- ViewSpec 계약(`view-1.0`)에는 `stack`·`grid`·`split` 배치 트리가 있지만, 서버는 등록 분석 10개와 생성 경로 모두 `stack`(세로 나열)만 만들었다.
- 사용자가 동적 UI의 기준으로 DomainLens 전달 목업(`openstudy-domainlens-handoff/01_DEMO/OpenStudy_DomainLens_v9-6_Final_Stage6.html`)을 지정했다(2026-11-16).
- 목업의 결과 조립 방식
  - KPI 카드가 한 행으로 맨 위에 온다.
  - 차트와 그 수치를 나열한 표가 좌우 split(1.35 : 0.65)으로 놓인다.
  - 넓은 화면(주간 달력·좌석 배치도·업무 목록)과 단독 표는 전체 폭을 쓴다.

## 2. 검토한 선택지

- **분석마다 배치를 직접 작성**: 등록 분석 10개는 가능하지만, 생성 경로는 컴포넌트 구성이 결과마다 달라 같은 규칙을 다시 구현해야 한다.
- **컴포넌트 종류로 배치를 정하는 공통 규칙**: 등록 분석과 생성 경로가 같은 규칙을 쓴다. 이 방법을 택했다.

## 3. 결정 및 이유

- `ViewLayouts.compose`가 컴포넌트 목록을 KPI, 넓은 화면, 차트, 표로 나누어 배치한다.
  - 순서: KPI(여러 개면 `grid`) → 넓은 화면 → 차트와 표를 순서대로 짝지은 `split` → 짝이 없는 나머지.
  - 모든 컴포넌트는 정확히 한 번 들어간다. 기존 계약 검증(`ContractRules.view`: split은 자식 2개, 깊이 4 이하, 누락·중복 없음)을 그대로 통과한다.
- 계약과 컴포넌트 카탈로그는 바꾸지 않았다. 이미 `stack`·`grid`·`split`을 허용한다.

## 4. 구현 내용

- `domainlens/query/ViewLayouts.java`: 공통 배치 규칙.
- `OpenStudyAnalyses`: 등록 분석 10개의 ViewSpec을 `arranged(...)`로 감싸 공통 규칙으로 배치한다. 분석별 수동 배치는 지웠다.
- `PlanCompiler`: 생성 경로의 ViewSpec도 같은 규칙으로 배치한다.
- 예: 지점별 매출(KPI·막대·표) → `stack[KPI, split[막대, 표]]`, 주간 예약(달력·표) → `stack[달력, 표]`.

## 5. 검증 결과

- `ViewLayoutsUnitTests` 4개 통과: 목업 배치, 넓은 화면·단독 표, 생성 경로 컴포넌트, 조합별 계약 검증.
- `./gradlew test --tests "com.openstudy.modules.ai.*" --tests "com.openstudy.domainlens.*" --tests "com.openstudy.modules.sales.AiSalesEquivalenceIntegrationTests"`: 실패 0. 배치 변경으로 깨진 기존 시험은 없었다.
- 실행하지 않은 검증
  - 전체 테스트(`./gradlew test`)
  - 실제 Backend와 연결한 브라우저 확인. 화면 확인은 Frontend 데모 모드로만 했다(Frontend 개발 기록 참고).

## 6. 남은 작업 / 보류 사항

- 목업의 하단 근거 문구(데이터 근거 설명)와 KPI 보조 설명은 ViewSpec에 해당 항목이 없어 서버에서 보내지 않는다.
- 차트와 표가 서로 다른 데이터셋이어도 순서대로 짝짓는다. 현재 분석에서는 같은 데이터셋끼리만 짝지어지지만, 컴포넌트가 늘면 데이터셋 기준 짝짓기를 검토한다.
