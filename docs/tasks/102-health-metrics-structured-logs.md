# BE-102 상태 점검·요청 지표·구조화 로그

- 이슈: [#102 상태 점검·요청 지표·구조화 로그](https://github.com/OpenStudyService/OpenStudy-Backend/issues/102) · M11 · `type:feature`, `layer:service`
- 상태: **선행 대기** (앱 내부 구현은 먼저 진행 가능)
- 선행: #29 (열림)
- 후속: #104
- 인수 조건 진행: 0 / 5 (2026-11-06 조회)

## 목표

서비스 상태와 주요 요청의 성공·실패·처리량·오류율·지연을 CloudWatch에서 확인할 수 있게 한다.

## 작업 단계

1. Actuator health를 liveness·readiness로 나누고 DB 상태를 readiness에 포함한다. 인증 없이 상태만 노출하고 상세는 숨긴다.
2. Micrometer로 주요 API 그룹별 요청 수·오류 수·지연 지표를 수집한다.
3. JSON 한 줄 로그: 요청 ID·사용자 ID·지점·상태 코드·지연을 담고 비밀번호·세션·고객 연락처는 제외한다.
4. 자연어 분석 요청은 M10 진단 로그와 같은 요청 ID로 연결한다(#96 이후 확인).
5. 인프라 요청 사항(CloudWatch 로그 그룹·지표 수집·경보 후보)을 전달하고 회신을 기록한다.

## 제외 범위

- 별도 모니터링 서버

## 검증

- 통합 테스트: health 응답·DB 장애 시 readiness 실패, 지표 수집, 로그 형식, 민감 정보 제외
- 운영 CloudWatch 확인 결과는 인프라 회신으로 별도 기록

## 참고

- [ADR-M11-001](../db/ADR-M11-001.md) M11-D02·D04
