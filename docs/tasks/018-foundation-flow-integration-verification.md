# BE-018 1차 기반 흐름의 DB·HTTP·브라우저 통합 검증

- 이슈: [#18 1차 기반 흐름의 DB·HTTP·브라우저 검증](https://github.com/OpenStudyService/OpenStudy-Backend/issues/18) · M3 · `type:verification`
- 상태: **최종 검증** (부분 결과 누적 중)
- 선행: #10·#14·#17·#29 잔여 검증 (열림), #7~#16·#27·#28 (구현 완료), Frontend #4~#7
- 후속: #104
- 인수 조건 진행: 0 / 3 (2026-11-06 조회)

## 목표

M1~M3 기능을 하나의 순차 시나리오로 연결해 빈 DB부터 재기동·만료까지 실제 DB·HTTP·브라우저·메일 더블에서 검증한다. 마일스톤 종료 판단의 근거가 된다.

## 작업 단계

1. 정상 시나리오 자동화: 빈 DB migration → OWNER 초기화 → 로그인 → 2지점 등록 → 신규 직원 초대(메일 더블) → 수락·승인 → 허용 지점 진입 → 추가 지점 초대 → 권한 회수 → 상품 등록·수정·중지·이력 → 로그아웃·재기동·만료.
2. 공격·경합 시나리오: scope/body 조작, 타 지점 total·이력·SSE 누출, STAFF의 관리자 API 호출, 이중 수락·승인, 마지막 관리자 동시 제거, 같은 멱등 키·다른 입력, 낡은 version, 구매 snapshot 변경 시도.
3. 실제 두 브라우저로 초대·회수 흐름을 확인한다(Frontend 대응 PR 연결).
4. 운영 프록시의 쿠키·CSRF·SSE 결과는 #10·#17·#29 결과를 링크로 연결한다.
5. 기존 부분 결과(닉네임 흐름, lookups·SSE 회수)와 합쳐 통과·실패·미검증 표를 개발 기록에 정리한다.

## 제외 범위

- M4 이후 업무 흐름 (#104)

## 검증

- Testcontainers MySQL 버전·이미지, migration 전후 비교, `./gradlew test bootJar` 결과
- 메일 더블 수신과 운영 SMTP 수신(#14)을 구분한다.

## 참고

- [조직 통합 검증 기록](../development-log/2026-10-25-organization-integration-verification.md)
- [workspace 이벤트 기록](../development-log/2026-10-26-workspace-events.md)
