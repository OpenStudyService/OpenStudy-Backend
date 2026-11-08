# Backend 실행 이미지와 운영 설정 계약

> **2026-11-07 SSE 제거**: Backend는 SSE endpoint를 제공하지 않는다([Backend #170](https://github.com/OpenStudyService/OpenStudy-Backend/issues/170), [ADR-COM-001](../db/ADR-COM-001.md)). SSE buffering·heartbeat·연결 유지·fan-out 인수는 적용하지 않는다. 일반 HTTP·AI 장시간 응답의 timeout·HTTPS·쿠키·CSRF·서버 세션 보안 검증은 유지한다. Frontend 전환은 [Frontend #77](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/77)에서 진행한다.

> **2026-11-09 재검증**: main(V10, SSE 제거, AI·초대 메일 포함)으로 이미지를 다시 빌드했다. 로컬 HTTPS 구성에서 마이그레이션·OWNER 초기화·기동·쿠키·종료를 다시 확인했다. 결과는 [개발 기록](../development-log/2026-11-09-runtime-contract-refresh.md)에 있다. 로그아웃 쿠키 삭제 헤더는 하나로 정리했고(5절), AI·메일·팀 채팅 설정을 3절에 더했다.

[Backend #29](https://github.com/OpenStudyService/OpenStudy-Backend/issues/29)의 Backend 측 계약이다. 이미지 빌드·실행·환경 변수·프록시·DB 계약을 정리하고 Infra에 전달할 값을 모은다.

- 실제 AWS 배포와 공개 HTTPS 검증은 [Infra #2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2)에서 한다.
- DB 이력·실행 주체 인계는 [Infra #1](https://github.com/OpenStudyService/OpenStudy-Infra/issues/1)에서 한다.
- 로컬 검증 결과와 결정 이유는 [개발 기록](../development-log/2026-10-28-runtime-image-and-proxy-contract.md)에 있다.

## 1. 이미지 빌드

빌드 컨텍스트는 작업 트리다. 커밋하지 않은 변경이 이미지에 들어가지 않도록 **깨끗한 체크아웃**에서 빌드한다.

```sh
./gradlew test bootJar                     # Testcontainers 시험은 이미지 빌드 밖에서 실행한다
git worktree add ../openstudy-backend-release <commit>
cd ../openstudy-backend-release
git status --porcelain                     # 출력이 없어야 한다
docker build --platform linux/amd64 \
  --build-arg SOURCE_COMMIT=$(git rev-parse HEAD) \
  -t <account>.dkr.ecr.ap-northeast-2.amazonaws.com/<name_prefix>/service:$(git rev-parse HEAD) .
```

- `linux/amd64`는 Infra 예산 프로필의 `c7i-flex.large`와 같다.
- 기반 이미지는 Temurin 21(Ubuntu noble)의 다이제스트로 고정했다.
- `.dockerignore`는 허용 목록이다. Gradle wrapper, 빌드 스크립트, `src/main`만 컨텍스트에 들어간다.
- RDS CA 번들은 빌드 시 내려받는다. 빌드 로그의 `sha256sum` 줄을 배포 기록에 남긴다.
- 이미지 라벨 `org.opencontainers.image.revision`과 `version`에 소스 커밋이 들어간다.
- 이미지 크기는 약 1GB다(2026-11-09, amd64). 실행 jar가 288MB인데, 대부분 AI 모듈의 ONNX 런타임 등 네이티브 라이브러리다. 첫 배포의 이미지 내려받기 시간과 ECR 저장 용량에 반영한다.
- ECR 저장소는 태그 변경 불가(IMMUTABLE)다. 태그는 커밋 SHA로 한다.
- Infra에는 push 후 레지스트리가 돌려준 **다이제스트**를 전달한다. 형식은 `<registry>/<name_prefix>/service@sha256:<digest>`다. 로컬 이미지 ID는 레지스트리 다이제스트와 다를 수 있다.

## 2. 실행 계약

| 항목 | 값 |
| --- | --- |
| 포트 | 8080 (`SERVER_PORT`, Infra 기본값과 같음) |
| 실행 사용자 | `openstudy`(uid 10001), root 아님 |
| 프로필 | `prod` (이미지 `SPRING_PROFILES_ACTIVE`) |
| 메모리 | `-XX:MaxRAMPercentage=65.0`. 2,048MiB 제한에서 힙 약 1.3GiB, 나머지는 네이티브 메모리 |
| 상태 점검 | `GET /actuator/health`, 인증 없음. DB 연결 정상 시 200. 이미지에 `curl` 포함 |
| 기동 | 로컬 약 8.5초(2026-11-09). ECS `startPeriod` 120초 안 |
| 종료 | SIGTERM → graceful shutdown, 최대 20초. 종료 코드 143. 처리 중 요청이 없으면 약 1.2초 |

- 이미지 `HEALTHCHECK`는 ECS 태스크 정의의 health 명령과 같다.
- 처리 중인 요청(오래 걸리는 AI 조회 포함)은 graceful shutdown을 20초 제한까지 붙잡는다. ECS 기본 종료 대기 30초 안에 끝난다.
- 기동 시 DB에 연결할 수 없거나 스키마가 엔터티와 맞지 않으면 프로세스가 종료 코드 1로 끝난다. ECS가 태스크를 다시 시작한다.

## 3. 환경 변수

| 이름 | 필수 | Infra 주입 위치 | 내용 |
| --- | --- | --- | --- |
| `DB_URL` | 예 | `service_environment` | [7절](#7-db-계정과-tls)의 TLS 검증 URL |
| `DB_USERNAME` | 예 | `service_environment` | 앱 전용 최소 권한 계정 |
| `DB_PASSWORD` | 예 | `service_secret_parameters` | SSM SecureString ARN. 값은 Terraform·Git에 넣지 않는다 |
| `TRUSTED_PROXIES` | 예 | `service_environment` | 앱의 직접 상대 프록시 IP/CIDR, 쉼표 구분. [5절](#5-프록시와-세션-쿠키) |
| `SPRING_FLYWAY_ENABLED` | 아니오 | Infra 기본 `false` | `prod` 프로필도 `false`. `true`로 바꾸지 않는다 |
| `SERVER_PORT` | 아니오 | Infra 기본 `8080` | 이미지 기본값과 같다 |

- `prod`에서 필수 값이 없거나 비어 있으면 Bean 생성 전에 종료한다. 로그에는 `Missing required deployment environment: <이름>`만 남고 값은 남지 않는다.
- `prod`와 `local` 프로필을 함께 켜면 기동을 거부한다. `local`은 미리보기 샘플 데이터와 앱 내 OWNER 생성을 켠다.
- 이미지에는 `.env`·자격증명·OWNER 값이 없다. `.env`를 컨테이너에 마운트하지 않는다.
- 초대 메일 발송은 기본 비활성이다. 운영 SMTP 설정은 이 계약의 범위가 아니다.

### 기본값으로 두는 선택 설정 (2026-11-09 추가)

이미지는 아래 값을 주입하지 않아도 기동한다. 운영에서 바꿀 때만 Infra에 추가한다.

| 이름 | 기본값 | 운영 계약 |
| --- | --- | --- |
| `OPENSTUDY_TEAM_CHAT_ENABLED` | `false` | 팀 채팅은 서비스 범위에서 빠졌다(ADR-M10-002). `true`로 바꾸지 않는다 |
| `OPENSTUDY_AI_MODEL_MODE`, `DOMAINLENS_MODEL_MODE` | `playback` | 저장된 모델 응답을 재생한다. `live`는 `QWEN_API_KEY`가 있을 때만 켜진다 |
| `QWEN_API_KEY` | 없음 | 넣는다면 `service_secret_parameters`(SSM SecureString)로만 넣는다 |
| `QWEN_BASE_URL`, `QWEN_MODEL` | 코드 기본값 | `live`일 때만 쓴다 |
| `DOMAINLENS_EMBEDDING_DIRECTORY` | `.models/koen-e5-tiny-q8` | 이미지에는 임베딩 모델이 **없다**. 기동은 되지만 AI 검색이 `NOT_READY`가 된다(기동 로그 WARN) |
| `OPENSTUDY_AI_FIXED_AS_OF` | 빈 값 | 운영에서는 비워 둔다. 값이 있으면 AI 기준 시각이 고정된다 |
| `OPENSTUDY_AI_RATE_LIMIT_MAX_CONCURRENT`, `..._MAX_PER_MINUTE` | 10, 120 | 사용자별 AI 요청 한도 |
| `OPENSTUDY_INVITATIONMAIL_ENABLED`(`openstudy.invitation-mail.enabled`) | `false` | 켜려면 `SPRING_MAIL_*`, 발신 주소, 수락 URL이 함께 필요하다. 운영 SMTP 결정 전에는 켜지 않는다 |

- **운영 AI는 아직 정해지지 않았다.**
  - 이 이미지 그대로 배포하면 자연어 분석은 재생 모드이고, 임베딩 모델도 없다.
  - 실제 모델 연결, 모델 파일 배포 방식(이미지 포함 또는 볼륨), 키 관리는 [Backend #97](https://github.com/OpenStudyService/OpenStudy-Backend/issues/97)과 사용자 결정에 따른다.
- 설정 가져오기의 `.env`·`.env.qwen.local`은 선택 파일이다. 이미지에는 없고, 컨테이너에 마운트하지 않는다.

## 4. 요청 경로

```text
브라우저 ─HTTPS→ Nginx(TLS 종료, Infra) ─HTTP→ [Gateway 자리, 6절] ─HTTP→ Backend :8080 ─TLS→ RDS
```

Nginx `/api` 위치는 `Host`, `X-Forwarded-For`(`$proxy_add_x_forwarded_for`), `X-Forwarded-Proto`를 보낸다. 앱은 `server.forward-headers-strategy=none`을 유지한다. 그래서 요청을 HTTP로 보고 `X-Forwarded-Proto`는 사용하지 않는다.

## 5. 프록시와 세션 쿠키

### 세션 쿠키

| 속성 | 값 | 근거 |
| --- | --- | --- |
| 이름 | `SESSION` | Spring Session JDBC |
| `Path` | `/` | 컨텍스트 경로 |
| `Secure` | 항상 | `prod` 프로필의 `server.servlet.session.cookie.secure=true` |
| `HttpOnly` | 항상 | 기본 설정 |
| `SameSite` | `Lax` | 기본 설정. SPA와 API가 같은 출처라는 전제 |

- 프록시가 TLS를 끝내므로 요청의 `isSecure()`는 거짓이다. 그래서 Secure를 요청에서 추론하지 않고 프로필로 고정한다.
- 따라서 `prod` 이미지는 HTTPS 출처 뒤에서만 쓴다. Infra Nginx는 HTTP를 HTTPS로 308 이동한다.
- 로그아웃 응답의 삭제용 `Set-Cookie`는 하나이며 위 속성을 모두 갖는다(2026-11-09 정리).
  - 세션이 살아 있으면 Spring Session이 만료 헤더를 쓴다.
  - 세션이 이미 없는 쿠키는 같은 쿠키 직렬화기로 지운다.
  - 이전에는 Spring Security의 `deleteCookies`가 `Secure` 없는 헤더를 하나 더 보냈다.
  - 로컬 HTTPS 결과: `SESSION=; Max-Age=0; Expires=...; Path=/; Secure; HttpOnly; SameSite=Lax` 한 줄. 실제 브라우저의 삭제 동작은 Infra #2에서 확인한다.
- API를 다른 사이트로 분리하면 `SameSite`와 CORS 자격증명 정책을 다시 결정해야 한다.

### TRUSTED_PROXIES

- `ClientAddressResolver`는 직접 상대가 `TRUSTED_PROXIES`에 있을 때만 `X-Forwarded-For`를 읽는다. 오른쪽부터 신뢰하지 않는 첫 주소를 클라이언트로 본다.
- 이 값이 없으면 모든 사용자가 프록시 주소 하나로 묶인다. 로그인 출처 제한(15분 30회)을 모두가 함께 쓰게 되므로 필수로 했다.
- 값은 **앱이 실제로 보는 직접 상대 주소**다. ECS bridge 네트워크에서 이 주소는 구성에 따라 다르다.
  - Gateway 컨테이너가 있으면 그 컨테이너의 bridge IP다.
  - Nginx가 호스트 포트로 Backend에 바로 붙으면 Docker bridge 게이트웨이 주소다.
- 기본 bridge 대역 `172.17.0.0/16`이 후보다. 실제 값은 Infra #2에서 앱 로그나 측정으로 확인한 뒤 정한다.
- 클라이언트가 직접 닿을 수 있는 대역은 넣지 않는다. 현재 보안 그룹은 외부에 80·443만 연다.

### 장시간 요청

- SSE endpoint는 없다. Nginx에 SSE 전용 `proxy_buffering off`를 둘 필요가 없다.
- AI 조회는 일반 JSON 요청이지만 모델 호출 때문에 오래 걸릴 수 있다. Infra Nginx의 `proxy_read_timeout 300s`가 이를 덮는지 운영 값으로 확인한다.
- 화면 갱신용 이벤트 발행이 없어졌으므로 이 경로의 `desired_count=1` 제약은 사라졌다. 여러 태스크 운영 전에는 세션·초대 메일 큐 등 다른 공유 상태를 별도로 확인한다.

## 6. Infra Gateway·Service 계약과의 차이

Infra `ecs.tf`는 Gateway와 Service 이미지 두 개를 함께 요구한다. Gateway가 호스트 8081에서 받아 `http://service:8080`으로 넘기는 구조다.

Backend는 업무 모듈을 단일 앱에 둔다(#29 구현 모듈). 별도 Gateway 앱이 없다. 이 이미지는 **Service 계약**(8080, `curl`, `/actuator/health`, 2,048MiB)을 충족한다.

Gateway 자리를 어떻게 할지는 Infra #2에서 정한다. 이 PR은 Infra를 바꾸지 않는다.

| 선택지 | Infra 변경 | 영향 |
| --- | --- | --- |
| A. 단일 컨테이너 태스크 (권장) | 두 이미지 동시 지정 전제 제거. Service에 `8080→8081` 포트 게시. `dependsOn`·`links` 정리 | 경로가 가장 짧다. TLS·CORS·라우팅은 이미 Nginx가 한다 |
| B. 범용 리버스 프록시를 Gateway로 사용 | `ecs.tf` 유지, Gateway 이미지 별도 관리 | 홉과 관리 대상 이미지가 하나 늘어난다. `X-Forwarded-For`를 이어 붙여야 하고 Gateway IP를 `TRUSTED_PROXIES`에 넣어야 한다 |

A를 권장한다. 지금 Gateway가 할 일이 없고, 추가 홉은 신뢰 프록시 설정만 복잡하게 만든다.

## 7. DB 계정과 TLS

### Flyway 실행 주체

- **Infra의 SSM Flyway 실행기 하나**가 마이그레이션 계정으로 실행한다(`scripts/migrate-database.py`).
- 앱은 운영에서 마이그레이션하지 않는다. `prod`의 `spring.flyway.enabled=false`는 Infra 기본값 `SPRING_FLYWAY_ENABLED=false`와 같다.
- SQL 정본은 이미지 커밋의 `src/main/resources/db/migration`이다. 같은 파일을 이미지 `/app/db/migration`에도 넣었다.

  ```sh
  id=$(docker create <image>) && docker cp "$id":/app/db/migration ./migration && docker rm "$id"
  ```

- Infra의 기존 V1은 Backend 통합 V1과 체크섬이 다르다. 실행기의 SQL 출처 교체와 DB 준비는 Infra #1의 결정을 따른다([통합 V1 전환 기록](../development-log/2026-10-27-consolidated-v1-rollout.md)).
- Infra #1은 2026-10-30에 통합 V1 적용(테이블 66개)과 계정 분리를 검증하고 종료됐다. 그 뒤 V2~V10이 추가됐다(2026-11-09 기준 테이블 73개 + 이력 1개).
  - 이미지에 들어 있는 SQL로 실행기를 돌리면 V2부터 차례로 적용된다.
  - 로컬에서는 Infra와 같은 Flyway CLI·MariaDB 드라이버·마이그레이션 계정으로 V1~V10이 모두 적용됐다.
- 앱은 `ddl-auto=validate`라, 이미지보다 오래된 스키마에서는 기동하지 않는다. 배포 순서(마이그레이션 먼저)를 지킨다.
- 순서: 마이그레이션 → OWNER 초기화(최초 1회) → Service 배포. 앱은 기동 시 엔터티와 스키마를 검증(`ddl-auto=validate`)만 한다.

### 계정

| 계정 | 권한 | 사용처 |
| --- | --- | --- |
| 마이그레이션 | DDL+DML (`ALTER, CREATE, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE`) | Infra Flyway 실행기. 현재 RDS 관리자 시크릿 |
| 앱 | `SELECT, INSERT, UPDATE, DELETE` | Service 태스크, OWNER 초기화 |

```sql
CREATE USER 'openstudy_app'@'%' IDENTIFIED BY '<SSM SecureString 값>' REQUIRE SSL;
GRANT SELECT, INSERT, UPDATE, DELETE ON <database>.* TO 'openstudy_app'@'%';
```

- 접속 호스트는 VPC 대역으로 좁힐 수 있다. 계정 생성은 마이그레이션 SQL에 넣지 않는다.

### TLS

RDS는 `require_secure_transport=1`이다. 앱 `DB_URL`은 이미지에 넣은 RDS CA 저장소로 인증서와 호스트 이름을 검증한다.

```text
jdbc:mysql://<RDS 엔드포인트 호스트 이름>:3306/<database>?sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreUrl=file:/app/rds-truststore.p12&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=openstudy-public-ca
```

- IP가 아니라 RDS 엔드포인트 호스트 이름을 쓴다.
- `/app/rds-truststore.p12`는 RDS 전역 CA 번들의 공개 인증서만 담는다. 저장소 비밀번호는 비밀값이 아니다.
- CA 순환 시 이미지를 다시 빌드한다.

## 8. OWNER 초기화

서버 실행과 분리된 일회성 명령이다. 같은 이미지에서 진입점만 바꾼다.

```sh
docker run --rm \
  -e DB_URL -e DB_USERNAME -e DB_PASSWORD -e OWNER_EMAIL -e OWNER_NAME -e OWNER_PASSWORD \
  --entrypoint java <image> \
  -cp /app/app.jar -Dloader.main=com.openstudy.bootstrap.OwnerBootstrap \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

- 값은 환경 변수로만 받는다. 명령 인자는 거부한다.
- 마이그레이션이 적용되어 있어야 한다(Flyway validate, 대기 중 버전 없음).
- OWNER가 이미 있으면 `OWNER already exists; no changes made.`로 끝난다. 기존 자격증명을 바꾸지 않는다.
- 같은 이메일의 기존 직원은 OWNER로 올리지 않는다.
- 앱 계정(DML)으로 실행할 수 있다.
- AWS에서 어디서 실행할지(SSM으로 EC2에서 `docker run`, 또는 ECS RunTask)는 Infra #2에서 정한다. `OWNER_PASSWORD`를 Terraform 변수·state·셸 기록에 남기지 않는다.

## 9. 로컬 HTTPS 검증

`deploy/local-https/`는 TLS를 끝내는 Nginx, TLS 필수 MySQL, 분리된 DB 계정, Infra와 같은 Flyway 실행 설정을 로컬에 재현한다. 배포 정의가 아니다.

```sh
bash deploy/local-https/prepare.sh       # 로컬 CA·인증서·DB 신뢰 저장소·MariaDB 드라이버(.local/, Git 제외)
# 1절 명령으로 openstudy-backend:local 이미지를 만든다
cd deploy/local-https
docker compose up -d --wait mysql
docker compose --profile tools run --rm migrations   # 이미지의 /app/db/migration 복사
docker compose --profile tools run --rm migrate      # 마이그레이션 계정으로 Flyway
docker compose run --rm --no-deps \
  -e OWNER_EMAIL=owner@example.test -e OWNER_NAME=LocalOwner -e OWNER_PASSWORD=<로컬 비밀번호> \
  --entrypoint java app -cp /app/app.jar -Dloader.main=com.openstudy.bootstrap.OwnerBootstrap \
  org.springframework.boot.loader.launch.PropertiesLauncher
docker compose up -d --wait app proxy
SMOKE_EMAIL=owner@example.test SMOKE_PASSWORD=<로컬 비밀번호> ./smoke.sh https://localhost:8443 --insecure
docker compose --profile tools down -v
```

- Windows Git Bash에서는 컨테이너 경로가 바뀌지 않도록 `MSYS_NO_PATHCONV=1`을 붙여 `docker compose run`을 실행한다.
- `smoke.sh`는 실제 HTTPS 출처에도 쓸 수 있다. 이때는 `--insecure`를 빼고 실행한다.
- 프록시는 `/api`만 넘긴다. `/actuator/**`와 API 문서(`/v3/api-docs`, `/swagger-ui`)는 외부에서 404다.
  - 앱에 직접 붙으면 `/actuator/health`만 익명으로 200(`status`와 그룹 이름만)이다.
  - 나머지 actuator와 API 문서는 401이다. API 문서를 운영에서 끌지는 #101 보안 확인에서 정한다.
- 이 구성이 증명하지 **않는** 것: ACME 인증서, Infra Nginx의 CORS, ECS bridge 네트워크의 실제 상대 주소, 실제 RDS CA, 실제 브라우저의 쿠키 처리.

## 10. Infra에 전달할 값

- [ ] Service 이미지: `<registry>/<name_prefix>/service@sha256:<digest>`, 소스 커밋, RDS CA 번들 SHA-256
  - 2026-11-09 로컬 빌드의 RDS CA 번들 SHA-256: `90a6f1a7978a1b1b096dca6c57511ca48d3b8cf052f8e704baf358f34ea2f21b`. 다운로드 시점에 따라 바뀔 수 있으니 실제 배포 빌드의 값을 쓴다.
- [ ] Flyway 실행기로 V2~V10 적용(운영 RDS가 V1 상태라면)
- [ ] Gateway 자리 결정(6절)과 그에 맞는 `ecs.tf` 변경
- [ ] `service_environment`: `DB_URL`, `DB_USERNAME`, `TRUSTED_PROXIES`(실측 후)
- [ ] `service_secret_parameters`: `DB_PASSWORD` → `/<name_prefix>/apps/` 아래 SSM ARN
- [ ] 앱 계정 생성·권한 부여, Flyway SQL 출처를 Backend 커밋으로 교체(Infra #1)
- [ ] OWNER 초기화 실행 위치와 비밀값 전달 방법
- [ ] 공개 HTTPS에서 `smoke.sh`와 브라우저로 쿠키·로그아웃·CSRF 확인 결과
