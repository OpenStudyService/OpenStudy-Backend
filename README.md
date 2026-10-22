# OpenStudy-Backend
OpenStudy 스터디카페 백오피스 백엔드 API

## 현재 구성

Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.8.1 기반의 단일 Gradle 프로젝트입니다. 빌드는 Groovy DSL, 애플리케이션 설정은 YAML을 사용합니다. 실행 클래스는 `com.openstudy.OpenstudyBackendApplication`입니다. 20개 업무 모듈의 골격, DB 연결 설정과 Flyway 초기 마이그레이션을 준비했고 Docker 전용 MySQL에서 앱 기동·마이그레이션·세션 저장·관련 제약을 검증했습니다. 업무 API 구현과 운영 환경 검수는 후속 범위입니다.

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

각 모듈은 다음 구조를 사용합니다. 클래스 이름은 배치 예시이며 아직 업무 클래스는 구현하지 않았습니다.

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

공통 설정은 Git에 포함하고, 개인별 DB 접속 정보·메일 비밀번호 등은 환경 변수 또는 Git에서 제외하는 `application-local.yaml`, `application-*-local.yaml` 등에 둡니다. `.env` 파일의 자동 로딩은 별도로 구성해야 합니다. Gradle 캐시·빌드 결과·IDE 설정·로그·업로드 파일은 커밋하지 않습니다.
