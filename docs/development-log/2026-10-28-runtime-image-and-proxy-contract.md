# 실행 이미지와 운영 프록시·DB 설정 계약 (#29)

## 1. 배경 / 문제

- main `1a34705`에는 실행 이미지, compose, 배포 workflow가 없었다. Infra는 앱 이미지가 없어 ECS 태스크를 만들지 않고 `/api`에 503을 반환한다.
- M1의 남은 인수 조건 대부분이 실제 HTTPS 경로 검증에 묶여 있다. Backend #10은 운영 HTTPS 쿠키(Secure·HttpOnly·SameSite·Path, 삭제) 하나만 남았다.
- 세션 쿠키 설정에는 `secure`가 없었다. `forward-headers-strategy`는 `none`이다. 그래서 TLS를 끝내는 프록시 뒤에서는 앱이 요청을 HTTP로 보고 Secure를 붙이지 않을 것으로 예상했다.

### 재현 (변경 전)

- 변경 전 설정으로 이미지를 만들었다. 로컬 Nginx(TLS 종료, Infra `/api` 위치와 같은 전달 헤더) 뒤에서 실행했다.
- `GET https://localhost:8443/api/auth/csrf` 응답은 `SESSION=…; Path=/; HttpOnly; SameSite=Lax`였다. **Secure가 없음을 확인했다.**

## 2. 검토한 선택지

### Secure 쿠키

- `forward-headers-strategy=native`로 `X-Forwarded-Proto`를 반영한다. 요청이 HTTPS로 보여 Secure가 자동으로 붙는다.
  - 단점: Tomcat RemoteIpValve가 원격 주소까지 바꾼다. `ClientAddressResolver`의 "직접 상대가 신뢰 프록시일 때만 XFF" 설계와 충돌하고, 신뢰 대역을 두 곳에서 관리하게 된다.
- `prod` 프로필에서 `server.servlet.session.cookie.secure=true`로 고정한다.
  - 운영 출처는 HTTPS뿐이다(Nginx가 HTTP를 308 이동). 요청 해석과 무관하게 결정적이다.

### Flyway 실행 주체

- 앱 기동 시 마이그레이션: 앱 계정에 DDL 권한이 필요하다. Infra 기본값 `SPRING_FLYWAY_ENABLED=false`와도 충돌한다.
- Infra의 SSM Flyway 실행기: 이미 TLS 검증·중복 실행 잠금·마이그레이션 계정 분리가 구현돼 있다. SQL 출처만 Backend로 바꾸면 된다.

### Gateway 자리

- Infra `ecs.tf`는 Gateway·Service 이미지를 함께 요구한다. Backend는 단일 앱이라 Gateway 앱이 없다.
- 단일 컨테이너 태스크로 바꾸는 안과 범용 리버스 프록시를 Gateway로 쓰는 안을 비교했다([계약 6절](../deployment/README.md#6-infra-gatewayservice-계약과의-차이)).

### 이미지 빌드 출처

- `git archive HEAD | docker build -`: 커밋된 내용만 컨텍스트가 된다. 이 PC의 Docker Desktop(buildx)에서 `load remote build context` 오류로 실패했다.
- 깨끗한 `git worktree`에서 `docker build .`: 동작했다. 작업 트리에 다른 작업의 미커밋 변경(AuthController)이 있어 이 방법을 문서 절차로 정했다.

## 3. 결정 및 이유

- **Secure는 `prod` 프로필로 고정한다.** `forward-headers-strategy=none`과 `ClientAddressResolver` 설계를 유지한다. 신뢰 대역을 `TRUSTED_PROXIES` 한 곳에서만 관리한다.
- **Flyway는 Infra 실행기 하나가 실행한다.** 앱 `prod`는 `spring.flyway.enabled=false`다. SQL은 이미지 `/app/db/migration`에도 넣어 같은 커밋의 SQL을 꺼낼 수 있게 했다. Infra의 SQL 출처 교체는 Infra #1의 결정을 따른다.
- **`TRUSTED_PROXIES`를 운영 필수로 했다.** 비어 있으면 모든 사용자가 프록시 주소 하나로 로그인 출처 제한(15분 30회)을 함께 쓴다.
- **필수 환경 값 누락은 이름으로 실패시킨다.** 기존에는 `${DB_USERNAME}` 문자열이 그대로 MySQL에 전달되어 접근 거부로만 보였다. `EnvironmentPostProcessor`가 Bean 생성 전에 이름만 보고한다.
- **`prod`와 `local` 동시 활성화를 거부한다.** `local`은 미리보기 샘플 데이터와 앱 내 OWNER 생성을 켠다.
- **RDS CA 신뢰 저장소를 이미지에 넣는다.** Infra 계약이 CA 신뢰와 호스트 이름 검증을 요구한다. RDS CA는 JVM 기본 신뢰 저장소에 없다. JDBC 전용 PKCS12 저장소로 분리해 SMTP 등 다른 TLS 신뢰는 넓히지 않았다.
- **Gateway는 단일 컨테이너 안을 권장하되 이 PR에서 Infra를 바꾸지 않는다.** 결정과 `ecs.tf` 변경은 Infra #2의 범위다.
- graceful shutdown 제한을 20초로 했다. 열린 SSE가 종료를 제한까지 붙잡으므로 ECS 종료 대기 30초 안에 끝나야 한다.

## 4. 구현 내용

- `Dockerfile`: 다이제스트 고정 Temurin 21 JDK에서 `bootJar`, JRE noble에서 실행. 비root(uid 10001), 8080, `curl` health 검사, `-XX:MaxRAMPercentage=65`, OCI 라벨(소스 커밋).
- `.dockerignore`: 허용 목록. wrapper·빌드 스크립트·`src/main`만 포함한다.
- `application-prod.yaml`: Secure 쿠키, Flyway 비활성, graceful shutdown 20초, `TRUSTED_PROXIES` 필수.
- `RequiredDeploymentEnvironment` + `META-INF/spring.factories`, 단위 시험 4개.
- `deploy/local-https/`: TLS Nginx, TLS 필수 MySQL과 앱·마이그레이션 계정 분리, Infra와 같은 Flyway 11.20.2·MariaDB 3.5.9·`verify-full` 설정, `smoke.sh`.
- `.gitattributes`: `*.sh`를 LF로 고정. `.gitignore`: 로컬 인증서·드라이버 디렉터리 제외.
- [운영 계약 문서](../deployment/README.md).

## 5. 검증 결과

### 자동 시험

- 깨끗한 worktree `3728a72`에서 `gradlew.bat test bootJar --no-daemon`: **39개 suite, 255개 통과**, 실패·오류·건너뜀 0, 7분 12초. Java 21 toolchain, Testcontainers MySQL.
- 최종 재실행: 깨끗한 worktree `b311123`에서 같은 명령으로 **39개 suite, 256개 통과**(프로필 조합 시험 1개 추가), 실패·오류·건너뜀 0, 6분 58초, BUILD SUCCESSFUL.
- `RequiredDeploymentEnvironmentTests` 4개 통과(누락·공백 이름 보고, 값 미노출, `prod`+`local` 거부, 다른 프로필 미검사).

### 로컬 컨테이너·로컬 TLS 프록시 검증

이미지는 깨끗한 worktree에서 `docker build --platform linux/amd64`로 만들었다. 아래 항목은 `1943137` 이미지로 확인했다. `b311123`은 프로필 조합 검사만 추가했으므로, 그 이미지로는 쿠키 흐름과 `prod,local` 거부를 다시 확인했다.

| 확인 | 결과 |
| --- | --- |
| 빌드·플랫폼·라벨 | `linux/amd64`, revision/version = 커밋, 약 161MB. RDS 전역 CA 114개, 번들 SHA-256 `90a6f1a7…2f21b` |
| 외부 Flyway | 마이그레이션 계정, `verify-full` TLS로 빈 DB에 V1 적용. 앱 기동 로그에 Flyway 실행 없음 |
| 기동·health | `prod` 활성, 약 7초, Docker health `healthy` |
| OWNER 초기화 | 앱 계정(DML)으로 `Initial OWNER created.`. 다른 비밀번호로 재실행 시 `OWNER already exists; no changes made.`, 기존 비밀번호로 로그인 유지 |
| 쿠키(발급) | `SESSION=…; Path=/; Secure; HttpOnly; SameSite=Lax` (CSRF·로그인) |
| 쿠키(삭제) | 로그아웃 200, 삭제 헤더 2개(아래), 이후 `/api/me` 401 |
| 재기동 후 세션 | 같은 쿠키로 `/api/me` 200 (JDBC 세션) |
| SSE | 프록시 경유 `text/event-stream`, `no-store`, heartbeat 수신. `X-Accel-Buffering`은 Nginx가 소비 |
| 정상 종료 | SSE 연결 중 `docker compose stop` 21초, 종료 코드 143. 20초 제한 후 종료 로그 확인 |
| 필수 값 누락 | `DB_URL`·`DB_USERNAME`·`DB_PASSWORD`·`TRUSTED_PROXIES` 각각 종료 코드 1, `Missing required deployment environment: <이름>` |
| 프로필 조합 | `prod,local` 기동 거부 |
| DB TLS | IP로 접속하면 호스트 이름 불일치로 거부. 이미지의 RDS 저장소로 로컬 CA 서버 접속 시 거부 |
| DB 권한 | 앱 계정 `CREATE TABLE` 거부(1142), TLS 없는 접속 거부(`REQUIRE SSL`) |
| 신뢰 프록시 | 클라이언트 A가 31번째 실패 로그인에서 429. 위조 `X-Forwarded-For: 9.9.9.9`도 429 유지. 다른 IP의 클라이언트 B는 401(제한 없음) |
| 이미지 내용 | `/app`에 jar·RDS 저장소·SQL만 존재. `.env`·키·tfvars 없음(Ubuntu gnupg 공개 CA 1개 제외). 이미지 기록에 비밀값 없음. 미리보기 클래스는 `local` 프로필 전용 |

로그아웃 응답의 삭제 헤더:

```text
Set-Cookie: SESSION=; Expires=Thu, 01 Jan 1970 00:00:10 GMT; Path=/
Set-Cookie: SESSION=; Max-Age=0; Expires=Thu, 1 Jan 1970 00:00:00 GMT; Path=/; Secure; HttpOnly; SameSite=Lax
```

첫 번째는 Spring Security `deleteCookies("SESSION")`다. 요청이 HTTP로 보여 Secure가 없다. 두 번째는 Spring Session이다. HTTPS 응답이므로 두 헤더 모두 쿠키를 만료시킬 것으로 보지만, 실제 브라우저 동작은 확인하지 않았다.

### 수행하지 않은 검증

- 실제 AWS(ECR push, ECS 태스크, RDS, ACME 인증서, 공개 HTTPS) 검증은 하지 않았다. Infra #2 이후 별도로 기록한다.
- 실제 브라우저의 쿠키 저장·삭제, CORS가 켜진 Infra Nginx 경유 동작은 확인하지 않았다.
- 실행 중 DB 연결이 끊긴 뒤의 재연결과 그동안의 health 상태는 확인하지 않았다.
- 실제 RDS CA로의 `VERIFY_IDENTITY` 접속은 확인하지 않았다. 로컬 CA로 같은 URL 옵션의 성공·실패만 확인했다.
- ECS bridge 네트워크에서 앱이 보는 직접 상대 주소(`TRUSTED_PROXIES` 값)는 측정하지 않았다.
- 이미지 빌드의 바이트 단위 재현성은 확인하지 않았다. RDS CA 번들은 빌드 시 내려받으므로 시점에 따라 달라질 수 있다.

## 6. 남은 작업 / 보류 사항

- Infra #2: Gateway 자리 결정과 `ecs.tf` 변경, 이미지 다이제스트 지정, `TRUSTED_PROXIES` 실측, OWNER 초기화 실행 위치, 공개 HTTPS에서 `smoke.sh`와 브라우저 확인.
- Infra #1: Flyway SQL 출처를 Backend 커밋으로 교체, 앱 계정 생성·권한, 새 DB 준비.
- #10·#29는 실제 HTTPS 검증 전까지 완료 처리하지 않는다.
- 로그아웃의 중복 삭제 헤더 정리(Spring Security 삭제 핸들러가 같은 쿠키 직렬화기를 쓰게 할지)는 실제 브라우저 확인 결과를 보고 판단한다.
- 이미지 빌드·push 자동화(CI/CD)는 M11 범위로 남긴다.
