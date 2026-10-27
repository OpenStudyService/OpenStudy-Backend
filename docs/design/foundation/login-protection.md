# 로그인 요청 제한과 인증 사건 감사

Issue [#28](https://github.com/OpenStudyService/OpenStudy-Backend/issues/28)의 구현 기준이다. HTTP 계약은 기존 [openapi.json](openapi.json)의 `POST /auth/login` 429 응답을 그대로 따른다. 이 문서는 계약 밖의 운영 동작, 설정값, 신뢰 경계를 정리한다.

## 요청 제한

로그인 요청마다 두 기준을 함께 검사한다. 하나라도 소진되면 공통 429 `RATE_LIMITED`와 요청 ID를 반환한다.

| 기준 | 키 | 한도 | 시간창 | 성공 시 |
| --- | --- | --- | --- | --- |
| 계정 | 앞뒤 공백 제거·소문자화한 입력 이메일 | 10회 | 15분 | 해당 계정만 초기화 |
| 접속 출처 | 아래 규칙으로 정한 클라이언트 주소 | 30회 | 15분 | 초기화하지 않음 |

- 시간창은 해당 키의 첫 시도부터 고정된다. 만료 시각에 도달하면 키가 제거되고 다시 로그인할 수 있다. 영구 잠금은 없으므로 마지막 관리자도 시간창이 지나면 로그인할 수 있다.
- 두 기준이 모두 남아 있을 때만 시도를 양쪽에 집계한다. 429로 거부된 시도는 어느 쪽에도 더하지 않는다.
- 존재하지 않는 계정도 같은 방식으로 집계하고 같은 429 본문을 받는다. 응답으로 계정 존재 여부를 알 수 없다.
- 출처 집계를 성공 시 초기화하지 않는 이유는, 공격자가 자기 유효 계정으로 한 번 로그인해 다른 계정 대입 집계를 지울 수 없게 하기 위해서다.

### 한도 결정 근거

계정 10회/15분은 기존 구현 값이다. 출처 30회/15분은 이번 구현에서 제안한 값을 2026-10-27 사용자가 확정했다.

- 한 출처가 계정 3개분의 실패 한도를 쓰면 막힌다. 같은 지점 PC나 NAT를 공유하는 직원 몇 명이 비밀번호를 몇 번씩 틀리는 정도는 허용한다.
- 출처 한도가 계정 한도보다 낮으면 같은 출처의 정상 직원이 다른 직원의 실패 때문에 먼저 막힌다. 그래서 계정 한도보다 크게 잡았다.
- 값은 아래 설정으로 바꿀 수 있다. 바꾸면 이 문서와 개발 기록에 근거를 남긴다.

### 저장소 상한과 정리

- 계정·출처 저장소는 각각 최대 10,000개 키를 메모리에 둔다(`capacity`). 매 요청마다 만료된 키를 먼저 정리한다.
- 정리 후에도 가득 차면 시도 횟수가 가장 적은 키(동률이면 먼저 만료될 키)를 축출한다. 이전 구현은 가득 차면 모든 신규 키를 429로 거부해 서로 다른 이메일을 대량으로 보내면 전체 로그인이 막혔다.
- 축출 방식에서는 반복 공격을 받는 키일수록 시도 횟수가 많아 마지막까지 남는다.

### 알려진 한계

- 인스턴스 메모리만 사용한다. 재기동하면 모든 시간창이 초기화되고, 인스턴스를 여러 개 띄우면 인스턴스마다 따로 집계된다. 다중 인스턴스용 외부 저장소는 이 이슈의 제외 범위다.
- 출처 키는 메모리에만 두며 로그·DB에 저장하지 않는다.

## 접속 출처 산정과 신뢰 경계

출처는 `ClientAddressResolver`가 정한다.

1. 서블릿의 직접 연결 주소(TCP peer)를 IP 리터럴로 정규화한다.
2. 직접 연결 주소가 `TRUSTED_PROXIES`에 없으면 그 주소가 출처다. `X-Forwarded-For`는 읽지 않는다.
3. 신뢰 프록시에서 온 요청만 `X-Forwarded-For`를 **오른쪽부터** 읽는다. 신뢰 프록시가 아닌 첫 주소가 출처다.
4. IP 리터럴이 아닌 값(호스트명, 포트 포함 값, 깨진 값)을 만나면 해석을 멈추고 직전의 신뢰 주소를 출처로 쓴다. 헤더 값으로 DNS 조회를 하지 않는다.

클라이언트가 헤더 왼쪽에 임의 주소를 넣어도 프록시가 오른쪽에 실제 주소를 덧붙이므로 결과가 바뀌지 않는다. 신뢰 프록시를 거치지 않고 직접 접속하면 헤더는 무시된다.

### 설정

| 설정 | 기본값 | 설명 |
| --- | --- | --- |
| `TRUSTED_PROXIES` (`openstudy.web.trusted-proxies`) | 빈 값 | 앱 바로 앞 리버스 프록시의 IP 또는 CIDR, 쉼표 구분. 비어 있으면 전달 헤더를 무시한다. 호스트명은 기동 시 거부한다. |
| `server.forward-headers-strategy` | `none` | 반드시 `none`으로 둔다. `native`/`framework`로 바꾸면 컨테이너가 먼저 원격 주소를 바꿔 신뢰 경계 검사가 무의미해진다. |
| `openstudy.auth.login-limit.account.max-attempts` / `window-minutes` | 10 / 15 | 계정 기준 |
| `openstudy.auth.login-limit.source.max-attempts` / `window-minutes` | 30 / 15 | 출처 기준 |
| `openstudy.auth.login-limit.capacity` | 10000 | 기준별 최대 키 수 |

### 운영 프록시 계약 (미확정)

[OpenStudy-Infra#2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2)와 [Backend#29](https://github.com/OpenStudyService/OpenStudy-Backend/issues/29)에서 맞춰야 한다.

- Nginx는 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`처럼 받은 값 오른쪽에 직접 연결 주소를 덧붙여야 한다. 값을 클라이언트 입력으로 통째로 교체하거나 앞에 붙이면 안 된다.
- Gateway를 거친다면 Gateway도 같은 방식으로 덧붙이고, Nginx와 Gateway 주소를 모두 `TRUSTED_PROXIES`에 넣는다.
- 실제 배포 경로에서의 위조 헤더 검증은 아직 수행하지 않았다.

## 인증 사건 감사

로그인 요청 한 건마다 결과에 따라 `audit_events`에 한 행을 남긴다. "시도"를 별도 행으로 두지 않고, 모든 시도가 아래 세 결과 중 하나로 기록된다.

| action | result | actor_employee_id | entity_id (`entity_type='employee'`) | reason |
| --- | --- | --- | --- | --- |
| `LOGIN_SUCCEEDED` | SUCCESS | 인증된 직원 | 인증된 직원 | 없음 |
| `LOGIN_FAILED` | FAILED | 없음 | 이메일이 가리키는 직원, 없으면 비움 | `BAD_CREDENTIALS`, `ACCOUNT_DISABLED`, `ACCOUNT_LOCKED`, `ACCOUNT_EXPIRED`, `AUTHENTICATION_FAILED`, `INVALID_INPUT`, `ERROR` |
| `LOGIN_RATE_LIMITED` | DENIED | 없음 | 계정 기준이면 이메일이 가리키는 직원, 출처 기준이면 비움 | `ACCOUNT_LIMIT`, `SOURCE_LIMIT` |

- 모든 행에 서버가 발급한 `request_id`와 DB 시각 `occurred_at`이 남는다. `X-Request-ID` 응답 헤더와 같은 값이다.
- 작업자(actor)는 인증이 확인된 경우에만 넣는다. 실패·제한 요청의 작업자는 확인할 수 없으므로 비운다. 존재하지 않는 계정은 대상도 비우며 가짜 직원 ID를 만들지 않는다.
- 이메일, 비밀번호, 해시, 세션·CSRF 값, 클라이언트 주소는 행에도 로그에도 남기지 않는다. `before_json`·`after_json`은 비운다.
- 제한 사건은 키·시간창마다 첫 거부만 기록한다. 공격 중 429마다 DB 쓰기가 일어나 부하가 증폭되는 것을 막기 위해서다.
- 감사 쓰기는 트랜잭션 밖에서 바로 커밋한다. 실패한 로그인도 행이 남는다. 감사 쓰기가 실패하면 요청 ID·action·예외 타입만 로그에 남기고 로그인 결과는 바꾸지 않는다.
- 요청 본문 검증(`@Valid`)에서 422로 끝난 요청은 컨트롤러에 도달하지 않아 감사와 제한 집계 대상이 아니다. 비밀번호 72바이트 초과는 컨트롤러에서 검사하므로 `INVALID_INPUT`으로 기록된다.
- 로그아웃 감사, 감사 조회 화면, 보관 기간은 이 이슈 범위가 아니다.
