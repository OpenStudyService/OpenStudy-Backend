# 운영 HTTPS 보안 요청 사항 (Backend #101)

[Backend #101](https://github.com/OpenStudyService/OpenStudy-Backend/issues/101)에서 인프라 담당에게 전달할 요청과 운영 HTTPS 최종 점검표다. ADR-M11-001에 따라 인프라 작업은 이 문서와 이슈 체크리스트로 요청하고 결과를 기록한다(M11-D02). 앱과 프록시의 책임은 M11-D08을 따른다.

- 앱이 설정하는 헤더: `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Content-Security-Policy`
- 프록시(HTTPS 종단)가 설정하는 헤더: `Strict-Transport-Security`

## 1. 앱이 보내는 보안 헤더 (2026-11-11, #101 구현)

모든 API 응답(성공·오류·`/actuator/health`)에 다음이 붙는다.

| 헤더 | 값 | 이유 |
| --- | --- | --- |
| `X-Content-Type-Options` | `nosniff` | 응답 형식 추측 금지 |
| `X-Frame-Options` | `DENY` | 프레임 삽입 금지(구형 브라우저용) |
| `Content-Security-Policy` | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'` | API는 JSON·이미지·CSV만 돌려준다. 응답이 무엇도 불러오거나 삽입되지 않게 한다 |
| `Referrer-Policy` | `no-referrer` | 주소의 토큰·ID가 다른 곳으로 새지 않게 한다 |

- 앱은 `Strict-Transport-Security`를 보내지 않는다. 프록시와 값이 둘로 나뉘지 않게 하기 위해서다.
- 화면(Frontend 정적 파일)은 Backend가 제공하지 않는다. 화면의 CSP는 그 정적 파일을 제공하는 쪽(Gateway·CDN)에서 정한다. 이 문서의 CSP는 API 응답에만 해당한다.
- 제공 경로를 바꿔 Backend가 HTML을 내려주게 되면, 이 CSP를 다시 정해야 한다.

## 2. 인프라 요청 사항

| # | 요청 | 기대 결과 | 상태 |
| --- | --- | --- | --- |
| 1 | HTTPS 종단(Gateway)에서 HSTS 추가: `Strict-Transport-Security: max-age=31536000` (`includeSubDomains`는 같은 도메인의 다른 HTTP 서비스가 없을 때만) | 공개 HTTPS 응답에 HSTS가 한 번 붙는다 | 요청 전 |
| 2 | HTTP(80) 요청은 HTTPS로 영구 이동(301/308) | 평문 요청이 앱에 닿지 않는다 | 요청 전 |
| 3 | 프록시가 `X-Forwarded-Proto`·`X-Forwarded-For`·`X-Forwarded-Host`를 덮어쓰기(클라이언트 값을 이어 붙이지 않음) | 앱의 출처 판단·Secure 쿠키가 프록시 값만 믿는다(#28, `TRUSTED_PROXIES`) | 요청 전 |
| 4 | ECS bridge에서 앱이 보는 프록시 주소를 실측해 `TRUSTED_PROXIES`에 넣기 | 위조 `X-Forwarded-For`가 무시된다 | 요청 전(#29 남은 항목) |
| 5 | 프록시가 앱의 보안 헤더(1절)를 지우거나 바꾸지 않기 | 공개 응답에 1절 헤더가 그대로 있다 | 요청 전 |
| 6 | TLS 1.2 이상, 약한 암호군 제외 | 공개 주소의 TLS 점검 통과 | 요청 전 |

- 상태 칸은 전달·회신 결과로 고친다. 회신 원문은 #101 이슈 댓글에 남긴다.

## 3. 운영 HTTPS 최종 점검표

공개 HTTPS 주소에서 확인한다. 로컬 TLS 프록시 결과는 [#29 개발 기록](../development-log/2026-10-28-runtime-image-and-proxy-contract.md)에 있고, 여기에서는 운영 결과만 기록한다.

| 항목 | 출처 | 확인 방법 | 결과 |
| --- | --- | --- | --- |
| 1절 보안 헤더 4개 | #101 | `curl -sI https://<host>/api/auth/csrf` | 미검증 |
| HSTS 한 번 | #101 | 같은 응답 | 미검증 |
| HTTP → HTTPS 이동 | #101 | `curl -sI http://<host>/` | 미검증 |
| 세션 쿠키 `Secure; HttpOnly; SameSite=Lax; Path=/` | #10·#29 | 로그인 응답의 `Set-Cookie` | 미검증(로컬 TLS 확인) |
| 로그아웃 쿠키 삭제 | #10·#29 | 로그아웃 응답과 이후 `/api/me` 401, 브라우저 저장소 | 미검증(로컬 TLS 확인) |
| CSRF 토큰 교체 | #10 | 로그인 전후 `/api/auth/csrf` 값 | 미검증(로컬 확인) |
| 위조 `X-Forwarded-For`로 출처 제한 우회 불가 | #28·#29 | 실패 로그인 반복 후 헤더 위조 | 미검증(로컬 TLS 확인) |
| 화면이 API 응답의 CSP와 무관하게 동작 | #101 | 브라우저로 로그인·AI·CSV 다운로드 | 미검증 |

- SSE는 2026-11-07에 제거되어 점검 대상이 아니다(ADR-COM-001).
