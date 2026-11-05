# BE-092 자연어 분석 API·QuerySpec/ViewSpec 계약과 명세 생성 포트

- 이슈: [#92 자연어 분석 API·QuerySpec/ViewSpec 계약과 명세 생성 포트](https://github.com/OpenStudyService/OpenStudy-Backend/issues/92) · M10 · `type:feature`, `layer:controller`
- 상태: **착수 가능** (M10 전체의 시작점)
- 선행: #86 M9 계약 (완료)
- 후속: #93, #96, #124
- 인수 조건 진행: 0 / 5 (2026-11-06 조회)

## 목표

자연어 분석 API, 서버가 받아들이는 QuerySpec·ViewSpec 스키마, 명세 생성 포트를 먼저 확정해 엔진 일정과 무관하게 서버 구현과 Frontend를 병행할 수 있게 한다.

## 작업 단계

1. `docs/design/foundation/nl-analysis-api.md` 작성: 질문 제출, SSE 상태, 상태 재조회, 취소, 확인 선택, 결과 페이지, 준비 상태 API.
2. 요청 상태(접수·생성·검증·실행·완료·추가 확인·취소·실패)와 SSE 이벤트 구조, 재연결 후 재조회 방법을 정의한다.
3. 실패 유형(입력·IR 검증·지원 불가·조건 확인·권한·예산·조회 오류)별 코드를 정한다.
4. QuerySpec·ViewSpec 버전 스키마: 버전, 필수 필드, 허용 연산·컴포넌트 목록.
5. 결과 응답: 검증된 QuerySpec, 기준 시각, 적용 필터, 지표 정의, 단위, 잘린 범위.
6. 명세 생성 포트 인터페이스(입력: 정제된 질문·승인 메타정보 / 출력: IR·확인 질문·지원 불가)와 테스트용 고정 생성기의 범위·한계를 문서화한다.
7. 표시 DTO와 엔진 응답 사이 버전 변환 계층 위치를 정하고 `openapi.json`에 반영한다.

## 제외 범위

- 실제 엔진 구현 (#97)

## 검증

- 계약 문서·스키마 파일과 `openapi.json` 대조, 계약 테스트

## 참고

- [ADR-M10-001](../db/ADR-M10-001.md), [ADR-M9-001](../db/ADR-M9-001.md)
- DomainLens PRD 2.0의 IR 정의
