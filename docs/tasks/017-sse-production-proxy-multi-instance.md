# BE-017 SSE: 운영 프록시 연결 유지와 다중 인스턴스 전달 (잔여)

- 이슈: [#17 기반 lookups·커밋 후 SSE·권한 회수](https://github.com/OpenStudyService/OpenStudy-Backend/issues/17) · M3 · `type:feature`
- 상태: **잔여 검증** (단일 인스턴스 구현 완료)
- 선행: #29 (열림), [Infra#2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2) (열림)
- 후속: #18, #101
- 인수 조건 진행: 2 / 2 (검증 항목 1개 미완료 + 다중 인스턴스 설계, 2026-11-06 조회)

## 목표

lookups API와 커밋 후 SSE, 권한·세션 회수는 PR #32로 단일 인스턴스에서 검증했다. 남은 것은 운영 TLS/프록시 경로의 SSE 연결 유지 검증과, 다중 ECS task로 배포할 때의 이벤트 전달 설계다.

## 작업 단계

1. 운영 프록시의 SSE 계약을 #29와 함께 확정한다: buffering 비활성화, `Content-Type`·`no-store`·`X-Accel-Buffering` 헤더, heartbeat 간격과 idle timeout.
2. 운영 HTTPS 경로에서 heartbeat 연결 유지, 재연결 resync, 연결 중 권한·세션 회수를 확인한다.
3. 배포 task 수를 확인한다. 1개를 넘으면 outbox 기반 fan-out 방식(예: DB 폴링, 메시지 브로커)을 설계하고 결정 기록을 남긴다.
4. 다중 인스턴스 전달을 구현하는 경우 별도 이슈로 분리하고, 이 이슈에는 설계·검증 결과만 연결한다.

## 제외 범위

- 이벤트 재생 보장
- lookups·회수 로직 재구현 (완료)

## 검증

- 운영 경로 SSE 연결 유지 시간·heartbeat 수신 기록
- 단일 인스턴스 결과를 다중 인스턴스 전달 성공으로 기록하지 않는다.

## 참고

- [workspace API 계약](../design/foundation/workspace-api.md)
- [개발 기록](../development-log/2026-10-26-workspace-events.md)
