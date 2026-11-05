# BE-101 보안 헤더와 운영 HTTPS 보안 최종 확인

- 이슈: [#101 보안 헤더와 운영 HTTPS 보안 최종 확인](https://github.com/OpenStudyService/OpenStudy-Backend/issues/101) · M11 · `type:verification`, `layer:controller`
- 상태: **최종 검증** (보안 헤더 설정은 먼저 진행 가능)
- 선행: #10·#29 (열림), [Infra#2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2) (열림)
- 후속: #104
- 인수 조건 진행: 0 / 4 (2026-11-06 조회)

## 목표

앱 응답에 보안 헤더를 설정하고, 앞선 이슈에서 검증한 HTTPS 쿠키·CSRF·프록시·SSE 결과를 운영 환경 기준으로 최종 확인한다.

## 작업 단계

1. 보안 헤더 설정: `X-Content-Type-Options`, `frame-ancestors`, `Referrer-Policy`, 기본 CSP. (선행과 무관하게 먼저 진행 가능)
2. SSE 응답과 Frontend 화면이 CSP 때문에 깨지지 않는지 확인한다.
3. 인프라 요청 사항(HSTS, TLS 종단, 프록시 전달 헤더)을 정리해 전달하고 회신을 이슈에 기록한다.
4. #10·#29·Infra#2 결과를 운영 HTTPS 기준 체크리스트로 모으고, 통과하지 않은 항목은 미검증으로 분리한다.

## 제외 범위

- 쿠키·CSRF·SSE 구현 (#10·#29·Infra#2)

## 검증

- 헤더 단위 테스트, 운영 HTTPS 응답 헤더 확인, 브라우저 동작 확인

## 참고

- [ADR-M11-001](../db/ADR-M11-001.md) M11-D02·D03·D08
