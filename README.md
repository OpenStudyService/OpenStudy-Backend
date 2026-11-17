# OpenStudy Backend

스터디카페 지점 운영 백오피스 **OpenStudy**의 API 서버입니다.
사장님·지점 관리자·직원이 한 화면에서 고객·이용권·결제, 좌석·룸·사물함, 업무·채팅, 근무·근태, 매출, AI 자연어 분석을 처리할 수 있도록 업무 규칙과 데이터를 책임집니다.

> 프로젝트 전체 소개와 화면은 [OpenStudy 대표 저장소](https://github.com/OpenStudyService/OpenStudy)를 참고하세요.
> 화면은 [OpenStudy-Frontend](https://github.com/OpenStudyService/OpenStudy-Frontend), AWS 인프라는 [OpenStudy-Infra](https://github.com/OpenStudyService/OpenStudy-Infra)에서 관리합니다.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.1 (Web MVC, Validation, Mail, Actuator) |
| 데이터 | MySQL 8.4, Spring Data JPA, Flyway(V1~V22) |
| 인증·보안 | Spring Security, Spring Session JDBC(HttpOnly 쿠키 세션), CSRF 토큰 |
| AI | Spring AI(OpenAI 호환 Qwen), ONNX Runtime 로컬 임베딩(`koen-e5-tiny-q8`) |
| 파일 | AWS SDK S3(프로필 사진) |
| API 문서 | springdoc-openapi, [기반 OpenAPI 릴리스](docs/releases/foundation-0.1.2/README.md) |
| 테스트 | JUnit 5, Testcontainers(MySQL 8.4), 아키텍처 경계 테스트 |
| 빌드·배포 | Gradle 9.8, Docker 이미지, `prod` 프로필 |

## 구현 기능

SRS 61개 요구사항을 마일스톤 M1~M15로 나누어 GitHub 이슈·PR 단위로 구현했습니다.

| 영역 | 모듈 | 주요 기능 |
|---|---|---|
| 인증·조직 | auth, account, authorization, branch, employee, invitation | 로그인·세션·CSRF, 지점·직원 관리, 역할·권한 프리셋, 메일 초대와 관리자 승인 |
| 고객·거래 | customer, ticket, payment, passhistory, customerhistory | 고객 원장·병합, 이용권 상품·요율, Mock 결제·환불, 이용권 사용·고객 이력 |
| 공간 | seat, reservation, locker | 좌석 배치·입퇴실·이용권 차감, 스터디룸 예약, 사물함 유료 대여 |
| 운영 | facility, inquiry, notice | 시설 점검, 고객 문의, 공지 |
| 협업 | task, chat | 업무·체크리스트·담당자, 채널·DM·스레드, 업무와 대화 연결 |
| 인력 | workforce | 주간 근무표, 출퇴근·휴게, 근태 정정, 휴가, 대체근무 |
| 집계 | home, sales | 운영 홈 카드, 지점별 매출(환불 처리일·원거래일 기준) |
| AI | ai | 자연어 질문 → 조회 계획 → 권한 범위 재검증 → 표·차트 결과 |
| 공통 | audit, retention, workspace | 감사 기록, 개인정보 보존 기간 처리, 로컬 미리보기 데이터 |

## 아키텍처와 설계 포인트

### 모듈형 단일 애플리케이션

```text
src/main/java/com/openstudy/
├── config/            Spring·보안 설정
├── common/            예외 처리 등 업무 비종속 공통 기능
└── modules/<업무>/
    ├── controller/    요청 처리·입력 검증
    ├── service/       업무 흐름·권한·트랜잭션
    ├── repository/    DB 접근
    ├── entity/        JPA 매핑·상태 변경 규칙
    └── dto/           요청·응답 DTO (엔티티를 직접 노출하지 않음)
```

- 모듈 사이에는 공개한 service 진입점과 DTO만 사용하고, 다른 모듈의 entity·repository 접근과 순환 의존을 금지합니다. 아키텍처 테스트로 경계를 검사합니다.
- 상세: [디렉터리 설계](docs/architecture/directory-structure.md), [권한 정책](docs/architecture/authorization-policies.md), [설계 결정](docs/design-decisions/README.md)

### 데이터 정합성

- **멱등 키와 버전 검사**: 결제·환불·예약·입퇴실 같은 민감 명령은 같은 요청이 두 번 처리되지 않도록 멱등 키를 저장하고, 수정은 버전 충돌(409)을 반환합니다.
- **스키마는 Flyway가 정본**: 빈 DB용 통합 V1부터 V22까지 마이그레이션으로 관리합니다. [DB 관리 안내](docs/db/README.md)
- **실제 MySQL로 테스트**: 통합 테스트는 Testcontainers의 전용 MySQL 8.4에서 실행하며 개발 DB로 대체하지 않습니다.

### 인증과 권한

- 프론트엔드 로그인 폼 → `/api/auth/login` → JDBC 쿠키 세션. 기본 로그인 화면과 HTTP Basic은 비활성화했습니다.
- 서버가 지점 범위와 역할(OWNER·MANAGER·STAFF)을 매 요청 검사합니다. [기본 로그인 안내](docs/authentication.md)

### AI 자연어 분석

- 질문을 등록 분석(키워드 규칙 → LLM 선택) 또는 생성 경로(LLM SemanticPlan → 서버 컴파일)로 처리하고, 결과는 QuerySpec·ViewSpec으로 표·차트를 구성합니다.
- **조회 계획 캐시**: 같은 의미의 질문은 저장된 계획을 재사용해 LLM 호출을 줄입니다. 결과 데이터는 저장하지 않고 요청자 권한으로 다시 실행합니다. [설계 기록](docs/development-log/2026-11-16-ai-plan-cache.md)
- 모델 응답 기본값은 저장된 응답 재생(`playback`)이며, 키를 설정하면 실제 Qwen(`live`)을 호출합니다.

## 실행하기

### 준비물

- JDK 21 (`JAVA_HOME` 설정)
- Docker 엔진 (테스트용 MySQL 컨테이너)
- 로컬 서버 실행 시 MySQL 8.4

### 빌드와 테스트

```powershell
.\gradlew.bat build       # macOS/Linux: ./gradlew build
```

| 명령 | 범위 |
|---|---|
| `./gradlew test` | 전체 테스트 |
| `./gradlew fastTest` | `@Tag("slow")`(스키마 재생성 마이그레이션·OWNER 초기화)를 제외한 테스트 |
| `./gradlew slowTest` | 마이그레이션·OWNER 초기화 테스트만 |
| `./gradlew test --tests "com.openstudy.modules.<모듈>.*"` | 특정 모듈 |

테스트는 여러 JVM에서 병렬 실행합니다. 메모리가 부족하면 `-PtestForks=1`을 붙입니다.

### 로컬 서버

```powershell
Copy-Item .env.example .env
# .env의 DB_URL, DB_USERNAME, DB_PASSWORD를 로컬 DB에 맞게 수정
.\gradlew.bat bootRun
```

- `.env`는 Java properties 형식이며 Git에 포함하지 않습니다. OS 환경 변수가 `.env` 값보다 우선합니다.
- 빈 DB의 첫 관리자 계정은 `local` 프로필에서 `.env`의 `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD`로 만듭니다.
- 프론트엔드는 `npm run dev:live`로 실행해 이 서버에 연결합니다.
- `local` 프로필은 [로컬 워크스페이스 샘플](docs/local-preview.md)을 제공합니다.

<details>
<summary>AI 채팅 로컬 실행</summary>

- 로컬 임베딩 모델(약 40MB)은 Git에 포함하지 않습니다. `.models/koen-e5-tiny-q8/`에 `manifest.json`, `model_quint8_x86_64.onnx`, `tokenizer.json`을 넣습니다. 모델이 없으면 서버는 기동하고 AI 검색만 비활성화됩니다.
- 실제 Qwen을 호출하려면 Git에서 제외되는 `.env.qwen.local`에 `OPENSTUDY_AI_MODEL_MODE=live`, `DOMAINLENS_MODEL_MODE=live`, `QWEN_API_KEY`를 넣습니다. 키가 없으면 `playback`으로 동작합니다.
- `/api/ai/**`도 로그인 세션과 CSRF 토큰이 필요하며, 요청 본문의 `actor`는 무시하고 로그인한 직원 기준으로 조회합니다.
- `com.openstudy.modules.ai` 통합 테스트 4종은 모델·시드된 docker-compose MySQL(127.0.0.1:3307)·기대값 파일이 있을 때 `OPENSTUDY_AI_LOCAL_TESTS=true`, `OPENSTUDY_AI_TEST_DB_PASSWORD`를 지정해 실행합니다. 조건이 없으면 건너뜁니다.

</details>

<details>
<summary>운영 사장님 최초 초기화</summary>

서버 부팅과 분리된 명령으로 최초 OWNER를 만듭니다. 마이그레이션이 모두 적용된 스키마에만 연결하며 마이그레이션을 자동 적용하지 않습니다.

```powershell
.\gradlew.bat ownerBootstrap --no-daemon
```

| 환경 변수 | 용도 |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 대상 MySQL 접속 정보 |
| `OWNER_EMAIL` | 최초 사장님 이메일(공백 제거·소문자 정규화) |
| `OWNER_NAME` | 이름, 1~100자 |
| `OWNER_PASSWORD` | 8자 이상, UTF-8 72바이트 이하. BCrypt 저장 |

- 이 명령은 `.env`와 `application*.yaml`을 읽지 않으므로 실행 환경에 직접 주입합니다. 비밀번호를 CLI 인자로 넘기지 않습니다.
- 기존 OWNER가 있으면 아무것도 바꾸지 않고 성공 종료합니다. 종료 코드: 성공 0, 입력·계정 충돌 2, DB·스키마 실패 1.
- 실행 이미지에서의 사용법은 [운영 계약 8절](docs/deployment/README.md#8-owner-초기화)을 따릅니다.

</details>

### 배포

배포용 컨테이너 이미지(`Dockerfile`)와 `prod` 프로필의 환경 변수·프록시·DB 계약은 [운영 계약](docs/deployment/README.md)을 따릅니다. 로컬 TLS 프록시 검증 구성은 `deploy/local-https/`에 있습니다.

## 프로젝트 현황

- M1~M10 핵심 업무와 M12~M15 확장 기능을 구현했습니다.
- 남은 작업은 주로 **M11 릴리스 검증·운영 준비**입니다: 전체 업무 회귀·동시성 재검증, 백업·복구 검증, 상태 점검·지표·로그, 운영 HTTPS 보안 확인, 개인정보 보존 기간 확정, DomainLens 실엔진 수치 동등성 검증.
- 최신 상태는 [마일스톤](https://github.com/OpenStudyService/OpenStudy-Backend/milestones)과 [열린 이슈](https://github.com/OpenStudyService/OpenStudy-Backend/issues)를 확인하세요.

## 문서

| 문서 | 내용 |
|---|---|
| [docs/design/foundation](docs/design/foundation/README.md) | 1차 DB·API 설계 |
| [docs/releases/foundation-0.1.2](docs/releases/foundation-0.1.2/README.md) | 기반 스키마·OpenAPI·ERD 릴리스 |
| [docs/db](docs/db/README.md) | DB 관리·Flyway 변경 절차 |
| [docs/verification](docs/verification/database-initialization-v1.0.md) | DB 초기화 검증 |
| [docs/privacy](docs/privacy/personal-information-handling.md) | 개인정보 처리 |
| [docs/development-log](docs/development-log/) | 날짜별 작업·의사결정 기록 |

## 개발 환경 참고

- IntelliJ에서는 저장소 루트를 Gradle 프로젝트로 열고 Project SDK와 Gradle JVM을 JDK 21로 맞춥니다. 실행 구성의 Working directory는 이 폴더로 지정합니다.
- `.idea/`, `*.iml`, `out/`, `.env`, `application-*-local.yaml`, 빌드 결과·로그·업로드 파일은 커밋하지 않습니다.
