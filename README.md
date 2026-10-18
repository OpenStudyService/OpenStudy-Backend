# OpenStudy-Backend
OpenStudy 스터디카페 백오피스 백엔드 API

## 현재 구성

Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.8.1 기반의 단일 Gradle 프로젝트입니다. 빌드는 Groovy DSL, 애플리케이션 설정은 YAML을 사용합니다. 실행 클래스는 `com.openstudy.OpenstudyBackendApplication`입니다. 현재는 실행 클래스와 기본 컨텍스트 테스트, 20개 업무 모듈의 디렉터리 골격 및 설계 문서가 준비되어 있습니다. 업무 API와 DB 연결·마이그레이션은 후속 구현 범위입니다.

## 디렉토리 구조

```text
openstudy-backend/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── docs/
│   ├── architecture/              초기 구조 설계와 요구사항/WBS 대응
│   ├── db/                        DB 스키마와 ERD 설계 자료
│   ├── design-decisions/          기존 설계 선택 기록
│   ├── development-log/           날짜별 작업 기록
│   └── directory-architecture/    이전 골격 보관본
├── scripts/                      향후 보조 명령
└── src/
    ├── main/
    │   ├── java/com/openstudy/
    │   │   ├── OpenstudyBackendApplication.java
    │   │   ├── bootstrap/        config, owner, seed
    │   │   ├── shared/           error, validation, time, money, pagination
    │   │   ├── platform/         audit, idempotency, outbox, mail, realtime, storage
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
        │   ├── platform/
        │   ├── integration/     http, concurrency, domainlens
        │   └── support/         database, fixture, http, clock
        └── resources/fixtures/
```

디렉터리의 `.gitkeep`과 `package-info.java`는 구조와 책임을 보존합니다. 상세 설명은 [디렉터리 설계](docs/architecture/directory-structure.md), [설계 결정](docs/design-decisions/README.md), [DB 설계 v0.4](docs/db/v0.4/README.md)를 참고하세요. `docs/directory-architecture/`는 기존 구조 제안의 보관본입니다.

## 업무 패키지

| 구분 | 모듈 |
|---|---|
| 인증·조직 | auth, authorization, branch, employee, invitation |
| 협업 | task, chat |
| 고객·이용·거래 | customer, ticket, payment, seat, reservation, locker |
| 운영 관리 | facility, inquiry, notice |
| 근무·집계 | workforce, sales, home |
| 외부 엔진 연동 | ai |

각 모듈에 `api/`, `presentation/`, `application/`, `domain/`, `infrastructure/`를 둡니다. api는 다른 모듈에 공개하는 Java 계약이고 HTTP 컨트롤러·요청/응답 DTO는 presentation에 둡니다. 모듈 내부 JPA 엔티티는 domain에 두고 공개 응답에 직접 노출하지 않습니다.

workforce는 `domain/schedule`, `domain/attendance`, `domain/leave`, `domain/substitution`으로 내부 책임을 구분합니다. 기능 구현이 시작되면 필요한 계층만 채웁니다.

각 모듈의 `package-info.java`는 책임을 설명하고 `.gitkeep`은 빈 디렉터리를 보존합니다. ArchUnit과 업무 구현·시드·초기화 명령은 아직 추가하지 않았습니다. DB SQL은 `docs/db/`의 설계 자료이며 실행용 마이그레이션과 구분합니다.

[기존 구조 설계](docs/architecture/directory-structure.md)의 빌드·Wrapper·실행 클래스 미생성 설명은 초기 상태입니다. 현재 배치는 이 README와 [구조 적용 기록](docs/development-log/2026-10-18-backend-project-directory-layout.md)을 따릅니다. 이전 골격 보관본은 실제 Gradle 소스 경로가 아닙니다.

## 개발과 검증

IntelliJ에서는 저장소 루트를 Gradle 프로젝트로 열고 Project SDK와 Gradle JVM을 JDK 21로 맞춥니다. `.idea/`, `*.iml`, `*.ipr`, `*.iws`, `out/`은 개인 개발 환경 파일이므로 Git에서 제외합니다.

JDK 21을 설치하고 `JAVA_HOME`을 설정한 뒤 저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat build
```

macOS/Linux에서는 `./gradlew build`를 사용합니다. 의존성 다운로드와 Gradle 실행 환경이 필요합니다. 기본 `contextLoads` 테스트에는 JPA 등 자동 구성이 포함되어 있으므로, DB 접속 등 필요한 구성이 준비되어야 합니다. 현재 `application.yaml`에는 애플리케이션 이름만 설정되어 있습니다.

DB 문서의 정적 일관성은 Python으로 확인할 수 있습니다. 이 명령은 스키마·ERD를 재생성하며 실제 DB에는 접속하지 않습니다.

```powershell
python docs/db/v0.4/validate_schema.py
```

공통 설정은 Git에 포함하고, 개인별 DB 접속 정보·메일 비밀번호 등은 환경 변수 또는 Git에서 제외하는 `application-local.yaml`, `application-*-local.yaml` 등에 둡니다. `.env` 파일의 자동 로딩은 별도로 구성해야 합니다. Gradle 캐시·빌드 결과·IDE 설정·로그·업로드 파일은 커밋하지 않습니다.
