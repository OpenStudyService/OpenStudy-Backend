# BE-097 DomainLens 엔진 실제 연동과 대표 질문 수치 동등성 검증

- 이슈: [#97 DomainLens 엔진 실제 연동과 대표 질문 수치 동등성 검증](https://github.com/OpenStudyService/OpenStudy-Backend/issues/97) · M10 · `type:feature`, `layer:service`
- 상태: **선행 대기** (외부 의존: DomainLens starter 제공)
- 선행: #93~#96 (열림), DomainLens starter
- 후속: #104, #124 (M10 완료 기준)
- 인수 조건 진행: 0 / 5 (2026-11-06 조회)

## 목표

DomainLens starter를 명세 생성 포트에 연결하고, 실제 모델·MySQL로 대표 질문과 추가 조합이 정형 API와 같은 수치를 내는지 검증한다. M10 마일스톤 인수 조건 1을 여기서 확인한다.

## 작업 단계

1. DomainLens starter 의존을 추가하고 명세 생성 포트를 구현한다.
2. 의미 카탈로그(#93)를 엔진 등록 형식으로 변환한다.
3. 엔진 응답 ↔ 표시 DTO 버전 변환 계층을 구현한다.
4. 실제 모델 ID를 확정한다. 엔진 IR은 #94 검증을 통과해야만 실행하고, 엔진이 만든 수치는 쓰지 않는다.
5. 대표 질문과 추가 조합(지표·지점·기간·상품)을 실행해 정형 API와 수치·단위를 대조하는 표를 만든다.
6. 단계별 지연 p95를 측정하고 환경을 기록한 뒤, 빈도·예산 제한값을 사용자에게 제안한다.
7. 사용한 Spring Boot·JPA·MySQL·DomainLens·React·QuerySpec/ViewSpec 버전을 문서화한다.

## 제외 범위

- DomainLens starter 개발

## 검증

- 실제 모델·Testcontainers MySQL 통합 테스트, 수치 대조 표, 지연 측정 기록
- 고정 예문·MSW 응답이 아닌 실제 엔진 호출 근거를 개발 기록에 남긴다.

## 참고

- [ADR-M10-001](../db/ADR-M10-001.md) M10-D01·D02
- DomainLens `compatibility.md`
