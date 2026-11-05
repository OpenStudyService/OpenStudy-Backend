# BE-014 초대 이메일: 운영 SMTP 수신 검증 (잔여)

- 이슈: [#14 초대 이메일 worker·revision·재시도](https://github.com/OpenStudyService/OpenStudy-Backend/issues/14) · M2 · `type:feature`
- 상태: **잔여 검증** (worker 구현 완료, 운영 SMTP 미검증)
- 선행: #13 (완료), 운영 SMTP 제공자 설정
- 후속: #18
- 인수 조건 진행: 3 / 3 (검증 항목 2개 미완료, 2026-11-06 조회)

## 목표

초대 메일 worker·lease·revision·재시도는 PR #36으로 구현과 로컬 SMTP 검증을 마쳤다. 남은 것은 실제 운영 SMTP 제공자로 보낸 메일의 수신 확인이다.

## 작업 단계

1. 운영 SMTP 제공자와 자격증명, 발신 도메인(SPF·DKIM), TLS 설정을 확정한다. 비밀값은 저장소에 넣지 않는다.
2. 운영 설정으로 초대를 발송하고 실제 수신함에서 링크·UTF-8 본문·스팸 분류를 확인한다.
3. 재발송 시 이전 링크가 무효화되는지 실제 메일로 확인한다.
4. 메일 더블 또는 로컬 수신기로 한 재시작·lease 만료 검증 결과와 운영 수신 결과를 구분해 기록한다.

## 제외 범위

- worker 로직 변경 (완료)
- 초대 수락·승인 (#15, 완료)

## 검증

- 운영 수신 결과 기록(수신 시각, 제공자, 스팸 여부)
- 정확히 한 번의 도착은 보장하지 않는다는 기존 제약을 유지한다.

## 참고

- [초대 발송 계약](../design/foundation/invitation-delivery.md)
- [개발 기록](../development-log/2026-10-27-invitation-email-worker.md)
