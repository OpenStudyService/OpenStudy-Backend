# OpenStudy 백엔드

첨부 요구사항 v1.1과 WBS v1.1을 바탕으로 만든 백엔드 디렉토리 골격이다. 단일 Gradle 빌드 안에서 기능별 패키지로 모듈을 구분한다. 기본 패키지 `com.openstudy`는 이번 구조 작업의 명명 기준이며 향후 조직의 네임스페이스에 맞춰 변경할 수 있다.

현재 포함된 것은 패키지 디렉토리, 책임 설명, 리소스/테스트 위치와 구조 문서다. 실행 클래스·업무 코드·SQL·빌드 설정은 아직 없다. `package-info.java`는 패키지 설명이며 `.gitkeep`은 빈 디렉토리 보존용이다.

```text
openstudy-backend/
├── docs/
│   ├── architecture/directory-structure.md
│   ├── development-log/
│   └── design-decisions/                 기존 설계 기록
├── scripts/                             향후 실행/초기화 보조 명령
└── src/
    ├── main/
    │   ├── java/com/openstudy/
    │   │   ├── bootstrap/               구성, 최초 사장님 명령, 시드
    │   │   ├── shared/                  오류, 검증, 시간, 금액, 페이지
    │   │   ├── platform/                감사, 멱등, outbox, 메일, SSE, 파일
    │   │   └── modules/                 20개 업무 모듈
    │   └── resources/db/
    │       ├── migration/               업무/세션 DDL의 단일 순서
    │       └── seed/local/              로컬 시연 데이터
    └── test/
        ├── java/com/openstudy/
        │   ├── architecture/            향후 ArchUnit 검사
        │   ├── modules/                 업무 모듈별 테스트
        │   ├── platform/                공통 기반 테스트
        │   ├── integration/             HTTP, 다중 연결 경합, 실엔진
        │   └── support/                 DB 격리, fixture, HTTP, Clock
        └── resources/fixtures/
```

[전체 모듈과 요구사항/WBS 대응](docs/architecture/directory-structure.md)을 먼저 읽는다. 모듈의 `api/`는 다른 모듈에 공개하는 Java 계약이고 HTTP 엔드포인트는 `presentation/`에 둔다.

## 후속 빌드 작업

WBS의 BE-002에서 실제 호환성을 검증한 뒤 `build.gradle`(Groovy DSL), `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/`와 실행 클래스를 추가한다. JDK·Spring Boot·Gradle Wrapper의 버전은 이번 작업에서 고정하지 않았다. 아직 `gradlew` 실행과 서버 기동을 할 수 없다. BE-003의 패키지/ArchUnit 작업 중 이번에는 디렉토리와 경계 규칙만 반영했다.

업무 DB는 MySQL 8.4(Debian)/InnoDB, 인증은 HttpOnly 쿠키·CSRF와 Spring Session JDBC를 기준으로 한다. 실제 DB·SMTP·DomainLens 연결은 후속 검증 대상이다.

공통 요구사항/API 정본은 현재 상위 `../docs/`에 있다. 별도 `openstudy-docs` 저장소로의 이동은 후속 작업이며, 이 디렉토리에는 백엔드 전용 설계와 개발 기록을 둔다.
