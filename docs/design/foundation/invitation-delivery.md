# 초대 메일 발송과 장애 복구

Backend [#14](https://github.com/OpenStudyService/OpenStudy-Backend/issues/14)의 구현 계약이다. [#13 관리 API](invitation-management-api.md)가 만든 초대·발송 작업을 재사용한다. 공개 미리보기·수락·승인(#15)과 운영 SMTP 수신 검증은 별도다.

## 실행 설정

기본값은 **발송 비활성화**다. `openstudy.invitation-mail.enabled=true`를 명시하고 SMTP host·자격증명·발신 주소·수락 URL을 제공해야 자동 worker가 실행된다. 설정 예시는 저장소 `.env.example`의 주석을 참고한다. 비밀값은 환경변수나 운영 secret 저장소로 주입한다.

| 설정 | 기본값 | 허용 범위 / 의미 |
| --- | --- | --- |
| `openstudy.invitation-mail.enabled` | false | 명시적으로 활성화할 때만 SMTP·worker·스케줄러 생성 |
| `openstudy.invitation-mail.from` | 없음 | 표시 이름·추가 수신자·줄바꿈 없는 단일 발신 주소 |
| `openstudy.invitation-mail.acceptance-url` | 없음 | 신뢰할 HTTPS `/invitations/accept` URL. 개발 loopback만 HTTP 허용 |
| `openstudy.invitation-mail.batch-size` | 10 | 1~100. 한 tick의 claim/전송 반복 상한 |
| `openstudy.invitation-mail.lease-seconds` | 60 | 1~600. claim 소유권의 유효 기간 |
| `openstudy.invitation-mail.retry-delay-seconds` | 60 | 0~3600. 자동 재시도 간 고정 대기 |
| `openstudy.invitation-mail.max-attempts` | 5 | 1~20. 자동 체인의 누적 claim 시도 한도 |
| `openstudy.invitation-mail.poll-millis` | 1000 | 100~300000. 완료 후 다음 tick 대기 |

이 숫자는 구현의 기술적 기본값이며 사용자 확정 업무 정책으로 취급하지 않는다. 새 revision으로 자동 재시도해도 누적 시도 수는 이어지고, 관리자의 수동 재발급은 새 시도 예산과 새 24시간을 부여한다.

SMTP는 `spring.mail.*` 설정으로 제공한다. `mail.smtp.connectiontimeout/timeout/writetimeout`의 기본값은 각각 5000/10000/10000ms이며 개별 값은 1~30000ms여야 한다. smtps 사용 시 같은 검사를 `mail.smtps.*`에 적용한다. timeout 합계가 lease보다 짧아야 기동한다. 여러 번의 프로토콜 읽기·쓰기가 있으므로 **이 합계가 전체 SMTP 호출의 최대 시간을 보장하지는 않는다.** 운영에서는 제공자의 TLS·인증 설정과 실측 지연을 확인해야 한다.

`mail.debug`, `mail.debug.auth`와 프로토콜 추적은 비활성화한다. JavaMail debug는 기동 시와 전송 직전 거부한다. 메일 본문은 UTF-8 일반 텍스트이며 이름을 헤더에 넣지 않는다. 수신·발신 주소는 단일 주소로 검증하고 링크는 설정한 URL의 `#token=...` fragment로만 만든다. query·기존 fragment·userinfo가 있는 URL은 거부하며 요청 Host 헤더에서 URL을 만들지 않는다.

## 상태 전이와 비밀값

| 단계 | 초대 / 발송 작업 |
| --- | --- |
| 관리 API 생성 | revision 1, token_hash NULL, job 1 PENDING |
| claim | 32바이트 난수 토큰·SHA-256 해시 생성. 해시만 DB에 저장하고 job PROCESSING·worker·lease 설정 |
| SMTP 수락 후 완료 | 현재 revision·worker·lease·해시·TTL을 재검사해 같은 job만 SENT |
| 발송 실패 | 고정 오류 코드 SMTP_FAILED. 이전 작업 종료·해시 폐기·새 revision/PENDING job 예약 |
| lease 만료 / 재시작 | 잃어버린 토큰을 복원하지 않고 이전 작업 SKIPPED·해시 폐기·새 revision 예약 |
| 횟수 소진 / 남은 TTL 부족 | FAILED·해시 폐기. 자동 발송 종료, 필요 시 관리자 수동 재발급 |

원문 토큰은 claim 결과와 SMTP 호출의 짧은 메모리에만 있다. DB·job·outbox·감사에는 원문이나 복호화 가능한 payload를 저장하지 않는다. 원문을 담는 claim 객체의 `toString()`도 수신자·이름·토큰을 제외한다. provider 예외 메시지·cause·stack은 보존하거나 로그에 출력하지 않고 고정 오류 코드로 처리한다.

자동 재시도는 최초 `expires_at`을 연장하지 않는다. 해시·revision·발송 상태 변경은 초대 version과 조회 무효화 신호에 반영되므로 관리 화면은 409일 때 재조회해야 한다. ACCEPTED는 링크 기한이 지났다는 이유로 만료시키지 않는다.

## 잠금·동시성

상태 변경은 `administration_guard → invitation → send job` 순서의 짧은 트랜잭션이다. 가드는 기존 관리자 명령과 branch FK 감사/outbox 저장 사이의 역방향 잠금도 막는다. 후보 탐색은 잠금 없이 제한된 개수만 읽고, 실제 처리에서 현재 행을 잠가 다시 검사한다.

claim·전송 직전 검사·완료는 각각 독립적으로 커밋한다. SMTP를 호출할 때 DB 트랜잭션과 가드를 유지하지 않는다. 상위 트랜잭션에서 worker를 호출하면 claim 전에 거부하여 중단된 상위 트랜잭션의 잠금도 전송 중 남지 않게 한다.

현재 초대 상태·revision·worker 식별자·저장된 lease·기한·토큰 해시가 모두 일치해야 전송 또는 완료를 진행한다. 취소·수락·재발급·lease 회수 뒤 늦게 끝난 worker가 현재 상태를 덮어쓸 수 없다. SMTP 전용 스케줄러를 기존 기본 스케줄러와 분리해 느린 전송이 SSE의 세션·권한 재검사를 막지 않게 한다.

## 전송 보장의 한계와 운영 확인

전송 직전 검사 후에도 SMTP 네트워크와 취소가 경합할 수 있다. 이미 외부로 전달된 메일은 회수할 수 없지만, 취소·수락·재발급으로 이전 링크를 무효화하고 늦은 완료의 저장을 차단한다. 정확히 한 번의 이메일 도착을 보장하지 않는다.

SMTP가 수락했지만 SENT 저장에 실패하면 PROCESSING lease가 남는다. 복구 시 원래 토큰을 재발송하지 않고 새 revision·새 링크를 만들므로 이전 메일이 뒤늦게 도착하더라도 최신 링크만 사용할 수 있다. 메일 수신과 실제 수락·승인 성공은 별개다.

실제 시험 근거는 [개발 기록](../../development-log/2026-10-27-invitation-email-worker.md)을 따른다. 실제 운영 제공자의 자격증명·수신함·TLS·스팸 분류·발신 도메인 설정은 로컬 SMTP 검증으로 대체하지 않는다. #15 구현 후 실제 받은 fragment 링크의 미리보기·수락·승인은 #18에서 함께 확인한다.
