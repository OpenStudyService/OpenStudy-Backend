# 2026-11-11 API 보안 헤더와 운영 HTTPS 요청 사항 (#101)

## 1. 배경 / 문제

- NFR-SEC-11: 앱 응답에 보안 헤더가 있어야 한다. ADR-M11-001 M11-D08이 책임을 나눴다.
  - 앱: `X-Content-Type-Options`, 프레임 금지, `Referrer-Policy`, 기본 CSP
  - HTTPS 종단(프록시): HSTS
- 지금은 Spring Security 기본값만 적용되어 있었다.
  - 적용되던 것: `nosniff`, `X-Frame-Options: DENY`, HTTPS 요청의 HSTS
  - 없던 것: CSP, `Referrer-Policy`
- 운영 HTTPS 점검과 인프라 요청은 기록할 곳이 없었다(M11-D02: Backend 이슈와 문서로 요청).

## 2. 검토한 선택지

| 항목 | 선택지 | 결과 |
| --- | --- | --- |
| CSP 범위 | API 응답만을 위한 `default-src 'none'` / 화면용 정책까지 앱에서 | API용만. Backend는 JSON·이미지·CSV만 돌려주고 HTML을 제공하지 않는다. 화면 CSP는 정적 파일을 내려주는 쪽에서 정한다 |
| HSTS | 앱 기본 HSTS 유지 / 앱에서 끄고 프록시에만 | 프록시에만(M11-D08). 앱도 프록시 뒤에서 HTTPS로 판단하면 HSTS를 붙이므로, 두 곳의 값이 달라질 수 있다. 한 곳이 맡는다 |
| Referrer-Policy | `no-referrer` / `strict-origin-when-cross-origin` | `no-referrer`. API 응답에서 다른 곳으로 이동할 일이 없고, 초대 미리보기가 이미 같은 값을 쓴다 |

## 3. 결정 및 이유

- CSP: `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`
  - 이미지(`<img>`)와 CSV 다운로드는 응답의 CSP와 무관하게 화면에서 쓸 수 있다.
  - SSE는 2026-11-07에 제거되어 충돌 확인 대상이 아니다(ADR-COM-001).
- 앱 HSTS는 끄고, HSTS·HTTP 이동·전달 헤더·TLS 설정은 인프라 요청으로 정리했다.
- #10·#29의 남은 운영 HTTPS 항목은 같은 점검표에 모았다. 결과 칸은 운영 확인 전까지 "미검증"이다(M11-D03: 같은 검증을 새로 만들지 않고 참조).

## 4. 구현 내용

- `SecurityConfiguration`: 응답 헤더 설정(프레임 금지, `Referrer-Policy`, CSP, 앱 HSTS 끔)
- 새 `SecurityHeadersTests`
  - 성공·인증 실패·없는 경로·health 응답 모두에 헤더 4개가 있고 HSTS는 없다.
  - 프록시가 HTTPS로 표시한 요청에도 앱은 HSTS를 보내지 않고, CSP는 한 번만 붙는다.
- 새 `docs/deployment/https-security-request.md`: 앱 헤더 표, 인프라 요청 6개, 운영 HTTPS 최종 점검표
- `docs/deployment/README.md`: 위 문서 링크

## 5. 검증 결과

- 실행함
  - `SecurityHeadersTests` 2건 통과
  - `InvitationAcceptanceIntegrationTests` 7건 통과: 미리보기 응답의 `Referrer-Policy`가 계속 하나다.
  - `./gradlew fastTest`(공통 보안 설정 변경이라 AGENTS.md 기준): 118개 클래스 1,157건 통과, 실패 0건, 건너뜀 26건(기존과 같음), 10분 16초
    - `@Tag("slow")` 마이그레이션 시험은 빠졌다. 마이그레이션은 바뀌지 않았다.
- 실행하지 않음
  - 운영 HTTPS 응답 헤더, HSTS, 브라우저 동작: 인프라 배포(Infra #2)와 요청 회신이 필요하다.
  - 테스트 설정은 전달 헤더를 프록시 값으로 처리하지 않을 수 있다. 그래서 "HTTPS 요청에도 HSTS 없음" 시험은 앱 설정을 끈 것을 확인할 뿐이고, 실제 프록시 경로 확인을 대신하지 않는다.
- `graphify update .`: 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 인프라 담당에게 요청 사항(문서 2절)을 전달하고, 회신을 #101 댓글과 문서 상태 칸에 기록한다(사용자 진행).
- 운영 HTTPS 점검표(문서 3절) 실행 후 #101·#10·#29의 남은 항목을 갱신한다.
- 화면 정적 파일의 CSP는 Frontend 제공 위치(Gateway·CDN)에서 따로 정한다.
