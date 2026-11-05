# BE-010 인증 보완: 운영 HTTPS 세션 쿠키 검증 (잔여)

- 이슈: [#10 인증 보완·승인 제한 세션·운영 사장님 초기화](https://github.com/OpenStudyService/OpenStudy-Backend/issues/10) · M1 · `type:feature`
- 상태: **잔여 검증** (구현 완료, 운영 환경 검증 대기)
- 선행: #29 (열림), [Infra#2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2) (열림)
- 후속: #18, #101
- 인수 조건 진행: 5 / 6 (2026-11-06 조회)

## 목표

로그인 세션과 OWNER 초기화 구현은 PR #20·#22·#32로 끝났다. 남은 것은 실제 신뢰 프록시 뒤 HTTPS 경로에서 세션 쿠키 속성과 쿠키 삭제가 의도대로 동작하는지 확인하는 일이다.

## 작업 단계

1. #29에서 운영 실행 이미지와 쿠키 설정(Secure·HttpOnly·SameSite·Path)이 준비됐는지 확인한다.
2. Infra#2 배포 경로(Nginx·Gateway)에서 로그인 → 응답 `Set-Cookie` 속성을 확인한다.
3. 로그아웃 시 쿠키 삭제, 세션 ID·CSRF 교체가 운영 경로에서도 동작하는지 확인한다.
4. 결과를 개발 기록에 남기고 이슈 인수 조건을 갱신한다. 실패 항목은 미검증으로 분리한다.

## 제외 범위

- 세션 만료·제한 세션 로직 재구현 (완료)
- 로그인 출처별 제한 (#28)

## 검증

- 운영 HTTPS 응답 헤더·브라우저 쿠키 저장소 확인 기록
- 로컬 결과를 운영 검증 성공으로 기록하지 않는다.

## 참고

- [2026-10-26 통합 기록](../development-log/2026-10-26-workspace-events.md)
- [인증 문서](../authentication.md)
