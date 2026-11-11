# 계정 복구·프로필 사진·감사 조회 API 계약

Backend [#118](https://github.com/OpenStudyService/OpenStudy-Backend/issues/118)의 M14 계약이다. 구현은 이 문서를 기준으로 다음 이슈에서 진행한다.

- 비밀번호 변경·관리자 초기화·잠금 해제: #119
- 프로필 사진: #120
- 감사 로그 검색·상세: #122

Frontend는 같은 [OpenAPI](openapi.json)(`0.1.15-m14-account`)로 연동한다. 화면은 Frontend #43·#45에서 만든다.

2026-11-10: 이 문서의 operation은 모두 설계 계약(`design-target`)이다. 이미 구현된 schema에 생길 변경은 그 schema의 `x-planned-changes`에 적었다. 구현 PR이 이 내용을 본 정의로 옮기고 항목을 지운다.

2026-11-10: 감사 로그 검색·상세(`GET /audit-events`, `GET /audit-events/{id}`)는 #122에서 구현됐다(`implemented`). 새 테이블은 없고 V1 `audit_events`를 읽는다.

2026-11-10: 비밀번호 변경·관리자 초기화·재설정 링크 확인·새 비밀번호 설정·로그인 잠금 해제 operation 5개는 #119에서 구현됐다(`implemented`, V16 `password_reset_tokens`).

2026-11-10: 프로필 사진 operation 3개와 `SessionUser`·`Employee`의 `avatar` 경로는 #120에서 구현됐다(`implemented`). `x-planned-changes`는 본 정의로 옮겨 남은 것이 없다. 이로써 이 문서의 operation은 모두 구현됐다.

- 정책 근거: [ADR-M14-001](../../db/ADR-M14-001.md)(M14-D01~D06, D09~D14)
  - M14-D07·D08(1:1 대화·채팅 스트림)은 [ADR-M10-002](../../db/ADR-M10-002.md)로 범위에서 빠져 이 계약에 없다.
  - M14-D11~D14는 이 계약을 쓰면서 사용자에게 받은 결정이다(2026-11-10).
- 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을 따른다.
- 비밀번호 규칙과 로그인 제한은 각각 [API 계약](api.md)의 인증 절과 [로그인 요청 제한](login-protection.md)을 따른다.

## 제공 범위

### 비밀번호·계정 복구(#119, FR-AUTH-13·14, M14-D01·D02·D05·D06·D11)

| API | 권한 | 동작 |
| --- | --- | --- |
| `POST /me/password` | ACTIVE 본인 | 기존 비밀번호를 확인하고 새 비밀번호로 바꾼다. 현재 세션만 남긴다 |
| `POST /employees/{id}/password-reset` | 대상 직원의 활성 소속 지점에서 `people:manage`가 있는 MANAGER, 또는 OWNER. OWNER 대상은 다른 OWNER만 | 기존 비밀번호를 즉시 무효로 하고, 모든 세션을 끊고, 재설정 링크를 메일로 보낸다 |
| `POST /password-resets/preview` | 익명 허용. 제출한 재설정 토큰 검증 | 링크가 유효한지와 만료 시각 |
| `POST /password-resets/complete` | 익명 허용. 제출한 재설정 토큰 검증 | 새 비밀번호를 정하고 링크를 소비한다 |
| `POST /employees/{id}/login-unlock` | 비밀번호 초기화와 같다 | 그 계정의 로그인 반복 실패 제한을 지운다 |

### 프로필 사진(#120, FR-SET-01·02, M14-D03·D10·D13·D14)

| API | 권한 | 동작 |
| --- | --- | --- |
| `PUT /me/avatar` | ACTIVE 본인 | 사진 업로드 또는 교체 |
| `DELETE /me/avatar` | ACTIVE 본인 | 사진 삭제 |
| `GET /employees/{id}/avatar` | 본인, OWNER, 또는 대상과 활성 소속 지점을 하나 이상 공유하는 ACTIVE 직원 | 사진 파일(앱 경유) |

기존 schema 변경(`x-planned-changes`):

- `SessionUser.avatar`, `Employee.avatar`: 저장소 키 대신 앱 경유 조회 경로(`/api/employees/{id}/avatar?v=<사진 식별자>`)를 담는다. 사진이 없으면 빈 문자열이다.

### 감사 로그(#122, FR-AUD-03·04, M14-D04·D12)

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /audit-events` | OWNER | 조건·기간으로 감사 기록 검색(페이지) |
| `GET /audit-events/{id}` | OWNER | 한 기록의 전후 값·작업자·시각·요청 ID |

## 공통 규칙

- **세션**
  - 본인·관리자·감사 operation은 ACTIVE `WORKSPACE` 세션과 유효한 `X-CSRF-Token`이 필요하다.
  - `POST /password-resets/*`는 세션 없이도 쓸 수 있다. 쓰기 요청이므로 CSRF 토큰(`GET /auth/csrf`)은 필요하다. 다른 계정으로 로그인한 상태여도 요청자의 세션은 바뀌지 않는다.
- **비밀값을 내보내지 않는다**
  - 비밀번호, 비밀번호 해시, 재설정 토큰과 그 해시, 세션 ID, CSRF 토큰은 어떤 응답·감사 기록·로그·멱등 결과에도 남기지 않는다.
  - 요청 DTO에만 비밀번호와 토큰 필드가 있다. 응답 schema에는 이런 필드가 없다(`additionalProperties: false`).
  - 프로필 사진의 저장소 키(S3 객체 키)도 응답에 넣지 않는다.
- **새 비밀번호 규칙**: 기존 규칙과 같다. 8자 이상, UTF-8 72바이트 이하이고 공백을 자동으로 제거하지 않는다. 어기면 422 `VALIDATION_FAILED`와 `fieldErrors.newPassword`다.
- **관리 대상의 범위**
  - 요청자가 볼 수 없는 직원은 404다. 볼 수 있지만 처리 권한이 없으면 403이다.
  - 볼 수 있는 직원의 범위는 직원 상세(`GET /employees/{id}`)와 같다.
- **화면 갱신**: 변경 이벤트는 보내지 않는다. 화면은 명령 응답과 다시 조회로 갱신한다([ADR-COM-001](../../db/ADR-COM-001.md)).

### 추가 오류 코드

| HTTP | code | 의미 | 대상 정보 |
| --- | --- | --- | --- |
| 422 | `CURRENT_PASSWORD_MISMATCH` | 본인 변경에서 기존 비밀번호가 틀림 | `fieldErrors.currentPassword` |
| 422 | `PASSWORD_SAME_AS_CURRENT` | 새 비밀번호가 기존 비밀번호와 같음 | `fieldErrors.newPassword` |
| 403 | `SELF_RESET_FORBIDDEN` | 관리자 초기화의 대상이 요청자 본인. 본인은 `POST /me/password`를 쓴다 | - |
| 503 | `PASSWORD_RESET_MAIL_DISABLED` | 재설정 메일 발송이 꺼져 있어(`openstudy.invitation-mail.enabled`가 `true`가 아님) 초기화를 받지 않음. 아무것도 바뀌지 않는다 | - |
| 422 | `PASSWORD_RESET_LINK_INVALID` | 재설정 토큰이 틀림·만료·사용됨·더 새 링크로 대체됨. 네 경우를 구분하지 않는다 | - |
| 422 | `AVATAR_TYPE_UNSUPPORTED` | 파일 시그니처가 JPG·PNG·WebP가 아님(확장자·Content-Type은 보지 않는다) | `fieldErrors.file` |
| 422 | `AVATAR_TOO_LARGE` | 파일이 5MB(5,242,880바이트)를 넘음 | `fieldErrors.file` |

기존 코드도 쓴다.

- 401: 미인증·만료 세션
- 403: `FORBIDDEN`, 잘못된 CSRF
- 404: `NOT_FOUND`
- 409: `INVALID_STATE`
- 422: `VALIDATION_FAILED`, `PERIOD_TOO_LONG`
- 429: `RATE_LIMITED`

## 본인 비밀번호 변경(FR-AUTH-13, M14-D02)

`POST /me/password` 본문은 `currentPassword`, `newPassword`이고 둘 다 필수다. 그 외 필드는 422 `VALIDATION_FAILED`다. 성공은 204이고 본문이 없다.

판정 순서:

| 순서 | 조건 | 결과 |
| --- | --- | --- |
| 1 | 로그인 계정 기준 제한(`LoginAttemptLimiter`)이 소진됨 | 429 `RATE_LIMITED` |
| 2 | 형식 오류(필수 누락, 72바이트 초과, 새 비밀번호 8자 미만) | 422 `VALIDATION_FAILED` |
| 3 | 기존 비밀번호가 틀림 | 422 `CURRENT_PASSWORD_MISMATCH`. 계정 기준 제한에 한 번 집계한다 |
| 4 | 새 비밀번호가 기존과 같음 | 422 `PASSWORD_SAME_AS_CURRENT` |
| 5 | 통과 | 비밀번호 해시 교체, 다른 세션 삭제, 감사 기록 |

- 기존 비밀번호 확인 실패를 로그인 계정 기준 제한에 집계하는 이유가 있다. 열려 있는 세션을 얻은 사람이 이 API로 기존 비밀번호를 대입해 볼 수 없게 하기 위해서다.
- 같은 사람의 다른 세션(`SPRING_SESSION`에서 같은 직원의 다른 세션)은 모두 지운다. 다른 기기는 다음 API 요청에서 401을 받는다.
- 현재 세션은 그대로 유지된다. 세션 ID와 CSRF 토큰도 바꾸지 않는다.
- 감사: 성공은 `action=PASSWORD_CHANGED`, `result=SUCCESS`다. 기존 비밀번호 불일치는 `result=FAILED`, `reason=CURRENT_PASSWORD_MISMATCH`다. 대상·작업자는 본인이고 `before_json`·`after_json`은 비운다.
- 계정 `version`은 바꾸지 않는다. 비밀번호는 프로필 편집과 충돌하지 않는다.

## 관리자 비밀번호 초기화(FR-AUTH-14, M14-D01·D02·D05·D11)

`POST /employees/{id}/password-reset`은 본문이 없다(빈 JSON 객체도 허용한다). 성공은 202다.

### 권한과 대상

| 경우 | 결과 |
| --- | --- |
| 요청자가 대상을 볼 수 없음 | 404 |
| 대상이 요청자 본인 | 403 `SELF_RESET_FORBIDDEN` |
| 대상이 OWNER이고 요청자가 OWNER가 아님 | 403 `FORBIDDEN` |
| 대상이 OWNER가 아니고, 요청자가 OWNER가 아니며, 대상의 활성 소속 지점 어느 곳에서도 `people:manage`가 있는 MANAGER가 아님 | 403 `FORBIDDEN` |
| 대상 계정 상태가 `활성`이 아님(승인 대기·이용 제한·비활성) | 409 `INVALID_STATE` |
| 위 검사를 모두 통과했지만 재설정 메일 발송이 꺼져 있음 | 503 `PASSWORD_RESET_MAIL_DISABLED`. 비밀번호·세션·링크는 그대로다 |

### 효과(한 트랜잭션)

1. **기존 비밀번호를 즉시 무효로 한다(M14-D11).** 비밀번호 해시를 어떤 입력과도 맞지 않는 값으로 바꾼다. 이후 기존 비밀번호 로그인은 일반 로그인 실패(401, 감사 `BAD_CREDENTIALS`)와 같은 응답을 받는다.
2. 대상의 모든 세션을 지운다(M14-D02). 대상의 다른 기기는 다음 API 요청에서 401을 받는다. SSE는 [ADR-COM-001](../../db/ADR-COM-001.md)에 따라 별도로 끊지 않는다.
3. 대상의 아직 쓰지 않은 재설정 토큰을 모두 무효로 하고, 발송 작업을 새로 예약한다.
4. 감사를 남긴다: `action=PASSWORD_RESET_REQUESTED`, 작업자는 요청자, 대상은 직원.

지점 권한·소속·계정 상태는 바꾸지 않는다. 계정 `version`도 바꾸지 않는다.

### 응답

`PasswordResetRequested`를 돌려준다.

- `employeeId`
- `requestedAt`
- `delivery`: `PENDING`

재설정 링크와 토큰은 응답에 없다. 발송 결과는 이 API로 알 수 없으며, 감사 기록과 운영 로그로 확인한다.

### 발송과 토큰(M14-D01·D05)

- [초대 메일 발송 구조](invitation-delivery.md)(claim·lease·재시도)를 재사용한다.
- 토큰은 claim 때 만든다. 32바이트 난수이고 DB에는 SHA-256 해시만 저장한다.
- 만료는 토큰을 만든 때부터 1시간이다.
- 링크는 설정한 HTTPS 재설정 URL에 fragment(`#token=`)로 붙인다. 화면은 fragment에서 토큰을 읽어 본문으로 보낸다. 서버 로그와 프록시 로그에 토큰이 남지 않게 하기 위해서다.
- 다시 요청하면 이전 링크는 무효가 된다. 받은 메일 중 가장 최근 링크만 쓸 수 있다.
- 발송이 끝내 실패하면 직원은 링크도 기존 비밀번호도 쓸 수 없다. 관리자가 초기화를 다시 요청한다.
- 설정(#119에서 확정)
  - SMTP, 발신 주소, 발송 간격·lease·재시도·시도 한도는 초대 메일 설정(`openstudy.invitation-mail.*`)을 함께 쓴다. 발송도 `openstudy.invitation-mail.enabled=true`일 때만 켜진다.
  - 재설정 URL `openstudy.password-reset.url`은 기본값이 없다. 신뢰할 HTTPS 주소(개발 loopback만 HTTP)이고 경로가 `/password-reset`으로 끝나야 하며, query·fragment·사용자 정보를 넣을 수 없다.
  - 메일이 켜져 있는데 이 URL이 없거나 틀리면 기동이 실패한다. 초기화는 기존 비밀번호를 바로 무효로 하므로, 보낼 수 없는 요청이 직원을 잠그지 않게 하기 위해서다.
  - 메일이 꺼져 있으면(개발·테스트 기본값) 초기화 요청을 503 `PASSWORD_RESET_MAIL_DISABLED`로 거부하고 아무것도 바꾸지 않는다. 받아 두면 기존 비밀번호는 바로 무효가 되는데 링크는 보낼 수 없어, 직원이 잠기기 때문이다(2026-11-11 변경).
  - 발송 결과는 감사 `PASSWORD_RESET_MAIL_SENT`·`PASSWORD_RESET_MAIL_FAILED`(대상 직원)로 남는다.

## 재설정 링크 사용(FR-AUTH-14, M14-D01·D05)

### 링크 확인

`POST /password-resets/preview` 본문은 `token`이다.

- 유효하면 200 `PasswordResetPreview`다.
  - `expiresAt`
  - `emailHint`: 이메일 앞 두 글자와 도메인만 보인다. 예: `ab***@example.com`
- 틀림·만료·사용됨·대체됨은 모두 같은 422 `PASSWORD_RESET_LINK_INVALID`다.

### 새 비밀번호 설정

`POST /password-resets/complete` 본문은 `token`, `newPassword`다. 성공은 204이며 세션은 발급하지 않는다. 화면은 로그인 화면으로 보낸다.

한 트랜잭션에서 다음을 처리한다.

1. 토큰을 잠금 후 다시 검증한다.
2. 비밀번호 해시를 바꾼다.
3. 토큰을 사용됨으로 바꾼다.
4. 대상의 로그인 계정 기준 제한 기록을 지운다.
5. 감사를 남긴다: `action=PASSWORD_RESET_COMPLETED`, 작업자 없음, 대상은 직원.

그 밖의 규칙:

- 대상 계정 상태가 `활성`이 아니게 되었으면 422 `PASSWORD_RESET_LINK_INVALID`다. 상태를 알리지 않는다.
- 두 API 모두 로그인과 같은 접속 출처 기준 제한을 적용한다. 소진되면 429 `RATE_LIMITED`다.

## 로그인 잠금 해제(M14-D06)

`POST /employees/{id}/login-unlock`은 본문이 없다. 권한·대상 판정은 관리자 비밀번호 초기화와 같다.

- 본인 해제도 허용한다(로그인한 상태라면 제한이 없을 것이다).
- 대상 계정 상태는 보지 않는다.

동작과 응답:

- 대상 이메일의 계정 기준 제한 기록만 지운다. 접속 출처 기준 기록은 지우지 않는다.
- 200 `LoginUnlockResult`를 돌려준다.
  - `employeeId`
  - `wasLocked`: 해제 시점에 제한 기록이 있었는지
- 감사: `action=LOGIN_UNLOCKED`, `after_json`에 `{"wasLocked": true|false}`.
- 잠금 상태를 따로 조회하는 API는 없다. 화면은 해제 버튼을 항상 보이고, 결과의 `wasLocked`로 안내한다.

**한계**: 제한기는 인스턴스 메모리에 있다. 인스턴스가 여럿이면 요청을 받은 인스턴스의 기록만 지운다([로그인 요청 제한](login-protection.md) "알려진 한계").

## 프로필 사진(FR-SET-01·02, M14-D03·D10·D13·D14)

### 업로드·교체

`PUT /me/avatar`는 `multipart/form-data`이고 파트는 `file` 하나다. 성공은 200 `CurrentUser`(새 `user.avatar`)다.

판정:

| 순서 | 조건 | 결과 |
| --- | --- | --- |
| 1 | `file` 파트가 없거나 다른 파트가 있음 | 422 `VALIDATION_FAILED` |
| 2 | 5,242,880바이트 초과 | 422 `AVATAR_TOO_LARGE` |
| 3 | 첫 바이트의 시그니처가 JPG(`FF D8 FF`), PNG(`89 50 4E 47 0D 0A 1A 0A`), WebP(`RIFF....WEBP`)가 아님 | 422 `AVATAR_TYPE_UNSUPPORTED` |
| 4 | 통과 | 새 키로 저장, 이전 파일 삭제 |

- 판별은 파일 시그니처로만 한다. 파일 이름의 확장자와 요청의 Content-Type은 판정에 쓰지 않는다.
- 크기 초과도 422로 답해야 한다. 서블릿 multipart 상한은 5MB보다 조금 크게 잡아, 413이나 500이 아니라 이 판정에서 거부되게 한다.
- 저장 키는 매번 새로 만든다(직원 ID와 무작위 식별자). `employees.avatar_url`에 키를 둔다.
- 업로드와 삭제는 계정 `version`을 1 올린다. 요청에 `version`을 받지 않는다. 사진은 본인만 바꾸므로 마지막 요청이 남는다.
- 이전 파일은 DB 커밋 뒤에 지운다. 삭제가 실패해도 응답은 성공이다. 남은 파일은 키가 DB에 없으므로 조회되지 않는다.
- 감사: `action=AVATAR_CHANGED`(업로드) 또는 `AVATAR_REMOVED`(삭제). 전후 값에는 키 대신 사진 유무만 남긴다.

### 삭제

`DELETE /me/avatar`의 성공은 200 `CurrentUser`(`user.avatar`가 빈 문자열)다. 사진이 없어도 200이다.

### 조회(M14-D13·D14)

`GET /employees/{id}/avatar`는 앱 경유로 파일을 돌려준다(M14-D13). 서명 URL은 쓰지 않는다.

- **볼 수 있는 사람(M14-D14)**: 본인, OWNER, 그리고 대상과 활성 소속 지점을 하나 이상 공유하는 ACTIVE 직원이다.
- 볼 수 없거나 사진이 없거나 대상이 없으면 모두 404다. 사진이 있는지 알리지 않는다.
- 응답 본문은 저장한 바이트 그대로다.
  - `Content-Type`: 판별한 형식(`image/jpeg`, `image/png`, `image/webp`)
  - `Cache-Control: private, max-age=86400`
  - `X-Content-Type-Options: nosniff`
- `user.avatar`의 `v` 값은 사진마다 다르다. 사진을 바꾸면 경로가 바뀌므로, 브라우저 캐시가 이전 사진을 보여 주지 않는다.
- 저장소(#120에서 확정): `openstudy.avatar.storage`로 고른다.
  - `s3`(운영): S3 비공개 버킷이다. `openstudy.avatar.s3.bucket`·`openstudy.avatar.s3.region`이 필요하고, 자격증명은 AWS 기본 체인(인스턴스·작업 역할)에서 얻는다. 객체마다 S3 관리형 암호화를 요청한다. 버킷·IAM 요청 내용은 [인프라 요청](../../deployment/avatar-bucket-request.md)에 있다.
  - `directory`(기본, 로컬 개발): `openstudy.avatar.directory`(기본 `./data/avatars`) 아래 파일이다.
  - `memory`(테스트): 메모리에만 둔다.
- 서블릿 multipart 상한은 6MB다. 상한을 넘은 요청도 422 `AVATAR_TOO_LARGE`로 답한다.
- `avatar_url`에 저장 키 형식이 아닌 값(이전 URL 등)이 있으면 사진이 없는 것으로 본다.

## 감사 로그 검색·상세(FR-AUD-03·04, M14-D04·D12)

### 검색

`GET /audit-events`는 OWNER만 쓸 수 있다. 그 외는 403 `FORBIDDEN`이다.

| query | 형식 | 의미 |
| --- | --- | --- |
| `from`, `to` | `YYYY-MM-DD` | Asia/Seoul 날짜. `from` 0시(포함)부터 `to` 다음 날 0시(제외)까지다 |
| `entityType` | 1~80자 | 대상 유형(예: `employee`, `payment`). 정확히 일치 |
| `entityId` | ID | 대상 ID. `entityType`과 함께만 쓸 수 있다 |
| `action` | 1~80자 | 행위(예: `LOGIN_FAILED`). 정확히 일치 |
| `actorEmployeeId` | ID | 작업자 |
| `branchId` | ID | 지점 |
| `result` | `SUCCESS`, `DENIED`, `FAILED` | 결과 |
| `requestId` | 1~128자 | 같은 요청에서 남은 기록 |
| `page`, `pageSize` | 공통 | 기본 20, 최대 100 |

**기간 규칙(M14-D12)**

- `from`·`to`가 모두 없으면 오늘을 포함한 최근 7일(오늘-6일 ~ 오늘)이다.
- 하나만 있으면 다른 쪽을 그 날짜에서 7일 범위로 채운다. `from`만 있으면 `to = from + 6일`, `to`만 있으면 `from = to - 6일`이다.
- 기간이 92일을 넘으면 422 `PERIOD_TOO_LONG`이다.
- `from > to`이면 422 `VALIDATION_FAILED`다.
- 표에 없는 query, `entityType` 없이 쓴 `entityId`, 범위를 벗어난 값은 422 `VALIDATION_FAILED`다.

**결과**

- 최신순(`occurredAt` 내림차순, 같으면 `id` 내림차순)이다.
- 응답 `AuditEventPage`의 항목 `AuditEventListItem`:
  - `id`, `occurredAt`, `action`, `result`, `entityType`, `entityId`, `reason`, `requestId`
  - `actor`: `{id, name}`, 없으면 null
  - `branch`: `{id, name}`, 없으면 null
  - `hasChanges`: 전후 값이 있는지
- 응답의 `period`에는 적용된 `from`·`to`를 담는다.
- 목록에는 전후 값을 넣지 않는다.

### 상세

`GET /audit-events/{id}`는 OWNER만 쓸 수 있고, 없는 기록은 404다.

- 응답 `AuditEventDetail`은 목록 항목의 필드에 `before`, `after`(JSON 객체 또는 null)를 더한다.
- 같은 요청의 다른 기록은 `requestId`로 검색해 찾는다(`GET /audit-events?requestId=`). 이때도 기간 규칙이 적용된다.

### 민감 값 처리

- 감사 기록은 쓸 때 이미 비밀값을 빼고 저장한다(V1 `audit_events` 주석).
- 조회할 때 한 번 더 가린다. 저장 시점의 실수가 화면으로 나가지 않게 하기 위해서다.
  - 키 이름에 `password`, `token`, `secret`, `hash`, `csrf`, `session`, `credential`이 들어가면(대소문자 무시, 중첩 포함) 값을 `"***"`로 바꾼다.
  - 키 이름에 `phone` 또는 `contact`가 들어간 문자열 값은 끝 네 자리만 남긴다(예: `***-****-1234`).
- `reason`은 그대로 보인다. 쓰는 쪽이 비밀값을 넣지 않는다.

## 생성 타입 동기화

Backend의 `openapi.json`이 정본이다. Frontend는 `npm run api:foundation`으로 `src/shared/api/foundation.generated.ts`를 다시 만든다.
