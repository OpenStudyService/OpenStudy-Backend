# 2026-11-10 본인 비밀번호 변경·관리자 초기화·로그인 잠금 해제 (#119)

## 1. 배경 / 문제

- 직원이 본인 비밀번호를 바꾸고(FR-AUTH-13), 관리자가 비밀번호를 보지 않고 직원 계정을 복구할 수 있어야 한다(FR-AUTH-14).
- 계약과 정책은 #118(PR #208)에서 확정됐다.
  - 계약: `account-collab-api.md`
  - 정책: ADR-M14-001 M14-D01·D02·D05·D06·D11
- 결정 요약
  - 초기화하면 기존 비밀번호는 즉시 무효가 된다.
  - 재설정 링크는 메일로 보낸다. 1시간 동안 한 번만 쓸 수 있고, 토큰은 해시만 저장한다.
  - 잠금 해제는 로그인 반복 실패 제한 기록을 지우는 것이다.

## 2. 검토한 선택지

- **모듈 위치**
  - 기존 모듈 의존 관계는 `auth → employee`, `invitation → auth·employee`다.
  - `employee`나 `auth`에 넣으면 로그인 제한기(auth)·직원(employee)·메일 설정(invitation)을 서로 참조해 순환이 생긴다.
  - 그래서 새 모듈 `modules/account`를 두었다. 다른 모듈은 `account`를 참조하지 않는다.
- **메일 발송**
  - 초대 발송 서비스를 일반화해 재사용하는 방법이 있었다. 그러나 초대 고유의 revision·만료 규칙과 얽혀 있어 바꾸는 범위가 크다.
  - 그래서 같은 원칙을 따르는 작은 발송 흐름을 따로 두었다.
    - claim 때 토큰 생성, 해시만 저장
    - SMTP는 트랜잭션 밖에서 호출
    - lease 만료나 발송 실패 시 해시를 지우고 다음 claim에서 새 토큰 생성
  - 설정(SMTP·발신 주소·배치·lease·재시도·시도 한도)과 SMTP 검증은 초대 메일 것을 그대로 쓴다.
- **기존 비밀번호 무효화 방법**
  - 해시 칼럼에 BCrypt가 아닌 값을 넣으면 로그인 때 인코더가 경고 로그를 남긴다.
  - 그래서 아무도 모르는 32바이트 난수의 BCrypt 해시로 바꿨다. 로그인 실패는 일반 `BAD_CREDENTIALS`와 같다.

## 3. 결정 및 이유

- **본인 변경의 실패 기록**
  - 기존 비밀번호가 틀리면 감사(FAILED)와 로그인 계정 기준 제한 집계를 남긴 뒤 422를 돌려준다.
  - 예외로 트랜잭션을 되돌리면 감사 행이 사라진다. 그래서 명령은 결과만 돌려주고, 커밋 뒤에 오류를 던진다.
- **제한 순서**
  - 계정 기준 제한이 소진됐으면 형식 검사보다 먼저 429를 돌려준다(계약의 판정 순서).
  - 계정 기준 제한은 로그인 제한기에 새 메서드를 더해 같이 쓴다: 소진 확인, 실패 집계, 계정만 지우기, 출처만 집계.
- **재설정 링크 API 두 개**는 로그인과 같은 접속 출처 기준 제한을 받는다.
- **링크가 유효한 상태**: `SENDING`과 `SENT` 둘 다 링크로 인정한다.
  - SMTP가 수락한 직후 `SENT` 기록 전에 직원이 링크를 누를 수 있기 때문이다.
  - 기록이 실패하면 lease가 끝난 뒤 새 토큰이 이전 링크를 대체한다. 초대와 같은 방식이다.
- **메일 설정**
  - 재설정 메일은 `openstudy.invitation-mail.enabled=true`일 때만 켜진다.
  - 이때 `openstudy.password-reset.url`이 없으면 기동이 실패한다. 초기화가 기존 비밀번호를 바로 무효로 하므로, 보낼 수 없는 요청이 직원을 잠그면 안 된다.
- **관리 대상 판정**은 직원 상세(`GET /employees/{id}`)의 가시성과 같다.
  - OWNER는 전원을 본다.
  - 관리자는 자기가 `people:manage`를 가진 지점에 소속(상태 무관)이 있는 직원을 본다.
  - 처리하려면 그 지점 소속이 ACTIVE여야 한다.

## 4. 구현 내용

- **마이그레이션 `V16__password_reset_tokens.sql`**
  - 번호는 병합 직전에 main 최대 + 1로 맞춘다.
  - 상태: `PENDING`·`SENDING`·`SENT`·`USED`·`REVOKED`·`FAILED`
  - `token_hash`·`expires_at`은 `SENDING`·`SENT`일 때만 있다(CHECK 제약).
- **`modules/account`**
  - `repository/AccountCredentialRepository`: 계정 조회, 비밀번호 해시 교체, 세션 삭제(현재 세션 제외 가능), 재설정 행 상태 전이
  - `service/AccountCredentialService`: 본인 변경, 관리자 초기화, 잠금 해제, 링크 확인·사용
  - `service/AccountTargets`: 계약 순서의 권한 판정(404 → 본인 403 → OWNER 대상 403 → 관리 권한 403)
  - `service/Passwords`: 비밀번호·토큰 규칙, 이메일 힌트
  - `service/PasswordResetDelivery`, `PasswordResetWorker`, `PasswordResetMailConfiguration`, `adapter/SmtpPasswordResetMailSender`: 발송
  - `controller/AccountCredentialController`: 새 API 5개
- **공통 변경**
  - `ApiException.forbidden(code, message)`: 코드가 있는 403(`SELF_RESET_FORBIDDEN`)
  - `LoginAttemptLimiter`: 계정만·출처만 다루는 메서드 4개
  - `InvitationMailConfiguration.validateTransport`: public으로 바꿔 재사용
  - 세션 없이 쓰는 `/api/password-resets/*`를 보안 설정·승인 전용 세션 필터·권한 인터셉터에 등록했다.
  - `/api/me/password`는 지점 범위 검사에서 뺐다(`/api/me/profile`과 같다).
- **계약**: 다섯 operation을 `implemented`로 바꿨다. 설정 이름과 규칙을 계약 문서와 `.env.example`에 반영했다.

## 5. 검증 결과

- 실행함
  - `./gradlew test --tests "com.openstudy.modules.account.*" --tests "com.openstudy.architecture.*"`: 통과
  - `AccountCredentialUnitTests` 15건
    - 비밀번호 규칙(8자·72바이트·공백 유지), 본문 필드 검사, 토큰 형식·해시, 이메일 힌트
    - 권한 판정 순서 전체
    - 본인 변경: 성공(해시 교체·다른 세션 삭제·감사), 기존 비밀번호 불일치(감사 FAILED·집계·422), 반복 실패 뒤 429, 같은 비밀번호·형식 오류
    - 초기화: 기존 비밀번호 무효·전체 세션 삭제·이전 링크 무효·발송 예약·감사, 비활성 계정 409
    - 잠금 해제(`wasLocked`)
    - 링크 확인: 만료·사용됨·형식 오류
    - 링크 사용: 비밀번호 설정·소비·제한 해제, 비활성 계정
    - 출처 제한 429
  - `PasswordResetDeliveryUnitTests` 7건
    - claim의 토큰·해시·1시간 만료
    - 비활성 계정 REVOKED, 시도 소진 FAILED와 감사
    - lease 만료 재시도
    - claim한 쪽만 완료할 수 있음
    - 실패 재시도와 소진
    - worker 한 바퀴
    - 재설정 URL·발신 주소 검증
- 실행하지 않음
  - 실제 MySQL에서의 마이그레이션 적용과 SQL(CHECK 제약 포함), `SPRING_SESSION` 삭제
  - HTTP 경로(권한별 응답, CSRF, 세션 없는 링크 API), 실제 SMTP 발송, 전체 `./gradlew test`, `slowTest`
  - 사용자 지시(2026-11-10)에 따라 통합·전체 테스트는 마일스톤 검증 때 실행한다. 마이그레이션을 추가했으므로 그때 `./gradlew slowTest`도 실행해야 한다.
  - #119 인수 조건의 "HTTP·DB 통합 테스트"도 그때 확인한다.
- `docs/db/schema-full.sql`·`erd.mmd`는 Flyway 테스트가 만드는 산출물이라 이번에 다시 만들지 않았다.
- `graphify update .`는 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- **마일스톤 검증 때 통합 테스트로 확인할 것**
  - 기존 비밀번호 불일치, 정책 위반
  - 세션 종료(현재 세션 유지), 권한별 초기화
  - 토큰 만료·재사용·재발급
  - 잠금 해제 후 바로 로그인
  - 감사·로그에 원문이 없는지
  - V16 적용(`slowTest`)
- **권한 인터셉터가 먼저 막는 경우**
  - `/api/employees/**` 경로는 기존 권한 인터셉터가 `people:manage` 범위가 전혀 없는 직원(일반 STAFF)을 먼저 403으로 막는다.
  - 계약상 "볼 수 없는 직원은 404"이지만, 이 경우는 기존 직원 API와 같게 403이 된다.
- **운영 배포 전**: 메일을 켠 환경에는 `openstudy.password-reset.url`을 넣어야 한다. 없으면 기동이 실패한다. Frontend #43의 재설정 화면 경로(`/password-reset`)와 맞춰야 한다.
- **잠금 해제의 한계**: 인스턴스 메모리 기준이라, 다중 인스턴스에서는 요청을 받은 인스턴스에만 반영된다(계약에 기록됨).
