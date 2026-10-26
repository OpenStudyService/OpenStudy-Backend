# 로그인 출처별 요청 제한과 인증 사건 감사 (#28)

## 배경 / 문제

[#28](https://github.com/OpenStudyService/OpenStudy-Backend/issues/28)은 로그인 제한이 인스턴스 메모리의 이메일 키만 쓰고, 인증 사건을 작업자·대상·행위·결과로 연결할 기록이 없다는 문제를 다룬다. 착수 전에 main `3484d1c`의 `LoginAttemptLimiter`, `AuthController`, `ApiRequestFilter`, `audit_events` 마이그레이션(V1·V2)을 확인했고, 작업 계획을 [이슈 댓글](https://github.com/OpenStudyService/OpenStudy-Backend/issues/28#issuecomment-6423021850)로 남겼다.

확인 과정에서 이슈 본문에 없던 기존 결함 두 가지를 찾았다.

- 저장소가 10,000건에 도달하면 처음 보는 이메일은 모두 429가 됐다. 서로 다른 이메일 1만 개를 보내면 15분 동안 마지막 관리자를 포함한 모든 사용자가 로그인할 수 없었다.
- `Instant.now()`를 직접 호출해 임계값·만료 시험을 결정적으로 작성할 수 없었다.

`audit_events`는 V2에서 `request_id`, `result`, nullable `entity_id`가 추가되어 있어 새 테이블이나 마이그레이션 없이 재사용할 수 있었다.

같은 시점에 #13(초대 관리)이 별도 작업 트리에서 진행 중이었다. 해당 작업이 `EmployeeAccountService`·`EmployeeAccountRepository`·`openapi.json`을 수정하고 있어 이 작업은 그 파일들을 건드리지 않도록 범위를 잡았다. 작업 중 #13이 PR #34로 main에 병합됐고, 변경 파일이 겹치지 않음을 확인한 뒤 main을 병합했다.

## 검토한 선택지

### 저장소 포화 시 동작

- 지금처럼 신규 키 거부: 전역 잠금이 그대로 남는다.
- 포화 시 신규 키를 집계하지 않고 통과: 공격자가 저장소를 채운 뒤 제한 없이 대입할 수 있다.
- 가장 먼저 만료될 키 축출: 공격받는 키도 오래됐으면 지워질 수 있다.
- **시도 횟수가 가장 적은 키 축출(동률이면 먼저 만료될 키)**: 반복 공격을 받는 키가 마지막까지 남는다. 축출 시 전체 탐색 비용이 들지만 상한 10,000건·포화 시에만 발생한다.

### 출처 산정

- Spring의 `server.forward-headers-strategy=native/framework`: 컨테이너가 원격 주소 자체를 바꿔, 신뢰 경계를 앱에서 검사하기 어렵고 설정 실수 시 모든 요청이 헤더를 신뢰하게 된다.
- **앱에서 직접 해석**: 직접 연결 주소가 설정된 신뢰 프록시일 때만 `X-Forwarded-For`를 오른쪽부터 읽는다. 기본값은 헤더 무시다.

### 감사 대상 연결

- 감사를 `EmployeeAccountService.recordLogin` 옆에 두는 방법: #13과 같은 파일을 수정하게 된다.
- **auth 모듈의 전용 서비스·저장소**: 기존 공개 서비스 `EmployeeAccountService.byEmail`로 대상만 조회하고, `audit_events` 쓰기는 auth 모듈 안에서 끝낸다.

## 결정 및 이유

- 계정 10회/15분은 기존 값 그대로 유지했다. 출처 기준은 **30회/15분을 임시값**으로 두고 설정으로 노출했다. 사용자 확정 정책이 아니며 이슈에서 확정을 요청한다. 근거는 [설계 문서](../design/foundation/login-protection.md#임시값과-선택-근거)에 적었다.
- 두 기준을 모두 통과할 때만 양쪽에 집계한다. 거부된 요청은 비밀번호 검증까지 가지 않으므로 대입 시도가 아니다. 이를 집계하면 계정 제한에 걸린 직원이 재시도할 때 같은 출처의 다른 직원 한도까지 깎인다.
- 로그인 성공은 계정 집계만 초기화한다. 출처 집계까지 초기화하면 공격자가 자기 유효 계정으로 한 번 로그인해 다른 계정 대입 집계를 지울 수 있다.
- 감사는 요청 한 건당 결과 한 행(`LOGIN_SUCCEEDED`/`LOGIN_FAILED`/`LOGIN_RATE_LIMITED`)으로 기록한다. 이슈의 "시도"는 별도 행 대신 모든 시도가 세 결과 중 하나로 남는 것으로 충족한다. 시도 행을 따로 두면 행 수가 두 배가 되고 결과 행과 다시 연결해야 한다.
- 작업자는 인증이 확인된 경우에만 넣는다. 실패·제한 요청은 대상만 실제 조회 결과로 연결하고, 없는 계정은 대상도 비운다.
- 제한 사건은 키·시간창마다 첫 거부만 기록한다. 공격 중 429마다 DB 쓰기가 일어나면 제한 자체가 DB 부하를 증폭시킨다.
- 감사 쓰기 실패는 로그인 결과를 바꾸지 않는다. 감사 저장소 장애가 전체 로그인 장애로 번지지 않게 하기 위해서이며, 실패는 요청 ID·action·예외 타입으로 로그에 남긴다.

## 구현 내용

- `LoginAttemptLimiter`: `Clock` 주입, 계정·출처 두 시간창, 포화 시 최소 시도 키 축출, 설정값 검증, 첫 거부 표시. 공통 429 본문으로 매핑되는 `LoginRateLimitedException`이 기준(계정/출처)을 감사용으로만 담는다.
- `ClientAddressResolver`(common/web): IP 리터럴만 인정해 헤더 값으로 DNS 조회를 하지 않는다. `TRUSTED_PROXIES`는 IP/CIDR만 받고 호스트명은 기동 시 거부한다.
- `LoginAuditService`·`LoginAuditRepository`(auth): 결과별 한 행, 실패 사유 코드, 이메일·주소·비밀값 미저장.
- `AuthController.login`: 제한 → 검증·인증 → 성공 처리 순서는 그대로 두고 각 결과에 감사를 붙였다.
- 설정: `application.yaml`에 한도·시간창·상한과 `TRUSTED_PROXIES`, `server.forward-headers-strategy: none`을 명시했다. `.env.example`에 `TRUSTED_PROXIES` 설명을 추가했다.
- 시험 설정: 모든 통합 시험이 127.0.0.1 하나에서 로그인하므로 `application-test.yaml`에서 출처 한도를 높였다. `AuthHttpTests`는 운영 기본값(30)으로 되돌려 출처 제한을 시험한다.
- 기존 `ProfileIntegrationTests`가 직원 기준 감사 행 전체를 세고 있어, 로그인 감사 행을 제외하도록 조건만 좁혔다.
- [로그인 보호 설계 문서](../design/foundation/login-protection.md)를 추가했다. `openapi.json`은 로그인 429를 이미 선언하고 있어 바꾸지 않았다.

## 검증 결과

### 실행 환경과 명령

- Gradle 9.8.1 wrapper, 로컬 JDK 17.0.2 런처. 기존 @MySqlIntegrationTest 구성(Testcontainers MySQL `mysql@sha256:24989179…`, 사용자 DB 미사용)을 그대로 썼다.
- 1단계 후 `gradlew test --tests com.openstudy.modules.auth.*`: 30개 통과.
- 2단계 첫 실행에서 `AuthHttpTests`의 출처 제한 시험 2개가 실패했다. `src/test/resources/application.properties`가 모든 시험에 test 프로필을 켜서, 통합 시험용으로 높인 출처 한도가 WebMvc 시험에도 적용됐기 때문이다. `AuthHttpTests`에 운영 기본값(30)을 명시해 해결했다. 실패를 통과로 계산하지 않는다.
- 3단계 첫 실행에서 `ProfileIntegrationTests` 5개가 실패했다. 직원 기준 감사 행을 전부 세는 기존 조건에 로그인 감사 행이 잡혔다. 집계에서 로그인 action만 제외했다.
- 수정 후 main 병합 전 `gradlew test`: 150개, 실패·오류·건너뜀 0.
- PR #34(#13 초대 관리)가 main에 병합된 뒤 `origin/main`을 병합했다. 충돌 없음. 병합 결과에서 `gradlew test bootJar`: **166개, 실패·오류·건너뜀 0, bootJar 성공**(초대 관리 시험 16개 포함).
- `git diff --check origin/main...HEAD` 통과.

### 인수 조건별 확인

| 조건 | 확인 방법과 결과 |
| --- | --- |
| 같은 출처에서 계정을 바꾸는 반복 요청 제한 | 단위 시험(30회 후 SOURCE 거부, 다른 출처는 허용), HTTP 시험(서로 다른 이메일 30회 후 31번째 429) |
| 같은 계정 반복 요청이 계정 보호를 우회하지 못함 | 출처를 매번 바꿔 10회 실패 후 11번째 429(단위·HTTP) |
| 신뢰 경계 밖 전달 헤더 위조 차단 | HTTP 시험: 신뢰 프록시 미설정 상태에서 매 요청 `X-Forwarded-For`를 바꿔도 31번째 429. 해석기 단위 시험: 비신뢰 peer, 오른쪽부터 해석, 왼쪽 주소 주입, 호스트명·포트·깨진 값, IPv6 정규화, 설정 호스트명 거부 |
| 안전한 공통 429·요청 ID·계정 존재 비노출 | HTTP 시험: 없는 계정과 있는 계정(올바른 비밀번호)의 429 code·message 동일, requestId가 응답 헤더와 같음 |
| 시간창 경과 후 로그인·마지막 관리자 영구 잠금 없음 | 제어 가능한 Clock으로 만료 1ns 전 거부·만료 시각 허용, 출처 시간창 만료 후 허용. 영구 잠금 코드 없음 |
| 사건별 시각·요청 ID·행위·결과·작업자/대상 연결 | MySQL 통합 시험: 성공(작업자=대상=직원), 비밀번호 오류(대상만), 정지 계정(ACCOUNT_DISABLED), 72바이트 초과(INVALID_INPUT), 계정 제한(DENIED, 대상 연결) |
| 없는 계정·익명 실패에 가짜 ID·잘못된 FK 없음 | 없는 계정 실패 행의 actor·entity·branch 모두 NULL |
| 비밀값·이메일·IP 원문 미기록 | 통합 시험에서 감사 행과 캡처한 전체 로그에 비밀번호·오답·이메일·해시·세션 쿠키·CSRF 값이 없음. 클라이언트 주소는 행에서만 확인(컨텍스트 기동 로그에 컨테이너 주소가 섞일 수 있어 로그 검사에서는 제외) |
| 한도·시간창·저장소 한도·정리·성공 초기화 범위·재기동 한계 | 기본값 고정 시험, 설정값 검증, 포화 시 축출·공격 키 유지, 만료 정리, 성공 시 계정만 초기화·출처 유지, 동시 64요청 중 정확히 10개 허용. 재기동 한계는 문서로만 명시(시험 대상 아님) |
| 제한 사건 DB 증폭 방지 | 같은 시간창의 두 번째 429는 감사 행 없음 |

### 수행하지 않은 검증

- 실제 Nginx·Gateway 경로에서의 전달 헤더 동작과 위조 시험: Infra#2 배포 전이라 수행하지 않았다. 로컬 시험은 MockMvc의 원격 주소 지정과 단위 시험으로만 확인했다.
- 다중 인스턴스·재기동 후 동작: 범위 밖이며 시험하지 않았다.
- 감사 쓰기 실패 시 로그인 결과 유지: 코드 경로만 있고 DB 장애를 주입하는 시험은 작성하지 않았다.

## 남은 작업 / 보류 사항

- 출처 한도 30회/15분은 임시값이다. 확정이 필요하다.
- 운영 프록시(Nginx·Gateway)의 `X-Forwarded-For` 덧붙이기 방식과 `TRUSTED_PROXIES` 값은 [Infra#2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2)·[#29](https://github.com/OpenStudyService/OpenStudy-Backend/issues/29)에서 맞춰야 한다. 실제 배포 경로의 헤더 위조 시험은 수행하지 않았다.
- 메모리 저장소라 재기동 시 시간창이 초기화되고 다중 인스턴스에서는 따로 집계된다. 외부 저장소는 이슈 제외 범위다.
- 로그아웃 감사, 감사 조회 화면, 감사 보관 기간은 범위 밖이다.
- 429 응답에 `Retry-After`는 넣지 않았다. OpenAPI에서 선택 헤더이며 다른 429 응답도 아직 보내지 않는다.
