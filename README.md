# OpenStudy-Backend
OpenStudy 스터디카페 백오피스 백엔드 API

## 현재 구성

Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.8.1 기반의 단일 Gradle 프로젝트입니다. 빌드는 Groovy DSL, 애플리케이션 설정은 YAML을 사용합니다. 실행 클래스는 `com.openstudy.OpenstudyBackendApplication`입니다. 20개 업무 모듈의 골격, DB 연결 설정과 Flyway 초기 마이그레이션을 준비했고 Docker 전용 MySQL에서 앱 기동·마이그레이션·세션 저장·관련 제약을 검증했습니다. 기본 로그인·로그아웃·현재 사용자 조회와 JDBC 세션 인증을 구현했으며 나머지 업무 API와 운영 환경 검수는 후속 범위입니다.

## 디렉토리 구조

```text
openstudy-backend/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── docs/
│   ├── architecture/              현재 구조 설계와 요구사항/WBS 대응
│   ├── db/                        DB 스키마와 ERD 설계 자료
│   ├── design-decisions/          기존 설계 선택 기록
│   ├── development-log/           날짜별 작업 기록
│   └── directory-architecture/    이전 골격 보관본
├── scripts/                      향후 보조 명령
└── src/
    ├── main/
    │   ├── java/com/openstudy/
    │   │   ├── OpenstudyBackendApplication.java
    │   │   ├── config/           Spring 설정
    │   │   ├── common/exception/ 공통 예외와 HTTP 오류 처리
    │   │   └── modules/          20개 업무 모듈
    │   └── resources/
    │       ├── application.yaml
    │       └── db/
    │           ├── migration/
    │           └── seed/local/
    └── test/
        ├── java/com/openstudy/
        │   ├── OpenstudyBackendApplicationTests.java
        │   ├── architecture/
        │   ├── modules/          업무별 테스트
        │   ├── common/
        │   ├── integration/     http, concurrency, domainlens
        │   └── support/         database, fixture, http, clock
        └── resources/fixtures/
```

디렉터리의 `.gitkeep`과 `package-info.java`는 구조와 책임을 보존합니다. 상세 설명은 [디렉터리 설계](docs/architecture/directory-structure.md), [설계 결정](docs/design-decisions/README.md), [DB 관리 안내](docs/db/README.md)를 참고하세요. `docs/directory-architecture/`는 기존 구조 제안의 보관본입니다.

## 업무 패키지

| 구분 | 모듈 |
|---|---|
| 인증·조직 | auth, authorization, branch, employee, invitation |
| 협업 | task, chat |
| 고객·이용·거래 | customer, ticket, payment, seat, reservation, locker |
| 운영 관리 | facility, inquiry, notice |
| 근무·집계 | workforce, sales, home |
| 외부 엔진 연동 | ai |

각 모듈은 다음 구조를 사용합니다. 아래 reservation 클래스 이름은 배치 예시입니다. 현재 auth와 인증에 필요한 employee·branch 조회를 구현했습니다.

```text
modules/reservation/
├── package-info.java
├── controller/                 ReservationController.java
├── service/                    ReservationService.java
├── repository/                 ReservationRepository.java
├── entity/                     Reservation.java
└── dto/
    ├── request/                CreateReservationRequest.java
    └── response/               ReservationResponse.java
```

Controller는 요청 처리와 입력 검증, Service는 업무 흐름·권한·트랜잭션, Repository는 DB 접근, Entity는 JPA 매핑과 상태 변경 규칙을 담당합니다. 요청·응답 DTO를 엔티티와 분리하고 엔티티를 HTTP 응답에 직접 노출하지 않습니다. Spring Data JPA 저장소 인터페이스와 필요한 사용자 정의 구현은 모두 repository에 둡니다.

모듈 간에는 명시적으로 공개한 service 진입점과 DTO만 사용합니다. 다른 모듈의 entity·repository에 직접 접근하지 않으며 모듈 순환 의존을 금지합니다. Java public이라는 이유만으로 모든 Service 메서드를 다른 모듈에 공개하지 않습니다. 호출 진입점과 DTO는 해당 기능 구현 시 문서와 경계 검사 대상으로 명시합니다.

workforce는 근무표·근태·휴가·대체근무를 하나의 업무 모듈로 유지합니다. 세부 패키지는 실제 구현량에 따라 필요한 계층에 추가합니다. 공통 설정은 config, 업무에 종속되지 않는 공통 기능은 common에 두며 common/exception 외의 세부 폴더는 기능 구현 시 추가합니다.

각 모듈의 package-info.java는 책임을 설명하고 .gitkeep은 빈 디렉터리를 보존합니다. ArchUnit과 업무 구현·시드·초기화 명령은 아직 추가하지 않았습니다. 실행용 스키마 SQL은 src/main/resources/db/migration/에서 관리합니다.

현재 구조는 [디렉터리 설계](docs/architecture/directory-structure.md)와 [2026-10-22 변경 기록](docs/development-log/2026-10-22-simplify-backend-package-layout.md)을 따릅니다. docs/directory-architecture/와 과거 개발 기록은 이전 구조의 보관 자료이며 실제 Gradle 소스 경로가 아닙니다.

## 개발과 검증

IntelliJ에서는 저장소 루트를 Gradle 프로젝트로 열고 Project SDK와 Gradle JVM을 JDK 21로 맞춥니다. `.idea/`, `*.iml`, `*.ipr`, `*.iws`, `out/`은 개인 개발 환경 파일이므로 Git에서 제외합니다.

JDK 21을 설치하고 `JAVA_HOME`을 설정한 뒤 저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat build
```

macOS/Linux에서는 `./gradlew build`를 사용합니다. JDK 21과 실행 중인 Docker 엔진이 필요하며 첫 실행에는 의존성과 테스트 이미지 다운로드가 필요합니다. `build`는 Testcontainers로 전용 MySQL을 만들고 테스트 종료 시 정리합니다. 테스트를 위해 개발 DB나 `DB_USERNAME`·`DB_PASSWORD`를 준비할 필요는 없습니다. 테스트만 실행하려면 `./gradlew test` 또는 `.\gradlew.bat test`를 사용합니다.

테스트 DB는 digest로 고정한 MySQL 8.4.11/InnoDB입니다. `@MySqlIntegrationTest`가 test 프로필과 Spring 관리 컨테이너를 연결하며 `@ServiceConnection`이 JDBC와 Flyway 접속 정보를 제공합니다. 컨테이너 설정이 빠진 테스트는 접속 불가 주소로 실패하고 개발 DB로 자동 대체하지 않습니다. IntelliJ에서도 통합 테스트에는 이 공통 애너테이션을 사용합니다. 운영 대상 Debian 검수와 도메인별 업무·경합 검증은 별도입니다.

[DB 초기화 검증 v1.0](docs/verification/database-initialization-v1.0.md)에 실제 실행 환경과 32개 테스트 결과를 기록했습니다. 배포용 bootJar에는 Testcontainers와 테스트 설정이 포함되지 않습니다. 일반 앱 실행(`bootRun`)은 [DB 관리 안내](docs/db/README.md)에 따라 개발 DB·계정과 환경 변수를 따로 준비합니다.

DB·계정 수동 준비와 Flyway 변경 절차는 [DB 관리 안내](docs/db/README.md)를 따릅니다. v0.4·v0.5 설계 폴더와 ZIP은 로컬 참고용으로만 보존하고 커밋하지 않습니다. SQL 정본은 Flyway 마이그레이션이며 최초 적용 전 전용 MySQL 8.4 DB에서 검증해야 합니다.

### 로컬 서버 실행

`openstudy-backend` 폴더에서 `.env.example`을 `.env`로 복사하고 실제 MySQL 계정과 비밀번호를 입력합니다. `.env`가 이미 있다면 복사로 덮어쓰지 말고 기존 파일을 수정합니다.

```powershell
Copy-Item .env.example .env
# .env의 DB_URL, DB_USERNAME, DB_PASSWORD를 로컬 DB에 맞게 수정
.\gradlew.bat bootRun
```

Spring Boot가 실행 작업 디렉터리의 `.env`를 Java properties 형식으로 읽습니다. 값에 따옴표나 `export`를 사용하지 않고, 실제 역슬래시는 `\\`로 이스케이프합니다. IntelliJ에서는 백엔드 실행 구성의 Working directory를 `openstudy-backend` 폴더의 절대 경로로 지정합니다. 별도 프로필은 필요하지 않습니다. 이 설정은 Spring 설정으로 로딩되며 OS 프로세스의 환경 변수를 생성하지는 않습니다.

`.env`는 선택 사항이며 OS 환경 변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 파일의 같은 이름 값보다 우선합니다. 운영에서는 `.env` 없이 ECS가 동일한 이름으로 주입하도록 설정합니다. DB 주소는 RDS 접속 주소로, 계정과 비밀번호는 운영용 값으로 지정합니다. 세 값이 모두 제공되어야 DB에 연결할 수 있습니다.

공통 설정과 `.env.example`은 Git에 포함하고, 실제 비밀값을 담은 `.env`는 Git과 배포 이미지에 포함하지 않습니다. `.env`는 이미 Git에서 제외되어 있습니다. 개인별 설정은 Git에서 제외하는 `application-local.yaml`, `application-*-local.yaml` 등에도 둘 수 있습니다. Gradle 캐시·빌드 결과·IDE 설정·로그·업로드 파일은 커밋하지 않습니다.

### 로그인

프론트엔드 로그인 폼의 이메일·비밀번호로 `/api/auth/login`을 호출하고 JDBC 쿠키 세션을 사용합니다. 기본 Spring Security 로그인 화면과 HTTP Basic은 비활성화했습니다. `/api/auth/csrf`, `/api/me`, `/api/auth/logout`과 세션 만료·계정 상태 검사도 구현했습니다.

빈 DB에서 사용할 최초 관리자 계정 생성은 `local` 프로필에서 `.env`의 `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD`로 명시적으로 요청합니다. 기존 OWNER의 비밀번호는 덮어쓰지 않습니다. 프론트엔드는 `npm run dev:live`로 실행합니다. 상세 설정·API·검증 결과와 미구현 범위는 [기본 로그인 안내](docs/authentication.md)를 참고하세요.

`local` 프로필에서는 [로컬 워크스페이스 샘플](docs/local-preview.md)도 기본 제공하여 로그인 후 홈·기본 목록을 볼 수 있습니다. 지점이 없으면 샘플 지점 두 개만 준비하고, 나머지 샘플은 조회 전용 가상 데이터로 반환합니다. 실제 업무 API가 준비되면 `openstudy.local-preview.enabled=false`로 비활성화합니다.
