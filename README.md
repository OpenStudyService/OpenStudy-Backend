# OpenStudy-Backend
OpenStudy 스터디카페 백오피스 백엔드 API

## 현재 구성

Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.8.1을 사용하는 단일 Gradle 프로젝트입니다. 현재는 실행 클래스와 기본 컨텍스트 테스트, 20개 업무 모듈의 디렉터리 골격 및 설계 문서가 준비되어 있습니다. 업무 API와 DB 연결·마이그레이션은 후속 구현 범위입니다.

```text
src/main/java/com/openstudy/
  bootstrap/   애플리케이션 초기화 위치
  shared/      공통 오류·시간·금액·검증·페이지 처리 위치
  platform/    감사·멱등·메일·실시간·저장소 등 기반 기능 위치
  modules/     업무별 api/application/domain/infrastructure/presentation
src/main/resources/   공통 설정, DB migration·로컬 seed 위치
src/test/             기본 테스트, 모듈·통합·아키텍처 테스트 위치
docs/                 구조·설계 결정·DB 스키마·개발 기록
scripts/              보조 명령 위치
```

디렉터리의 `.gitkeep`과 `package-info.java`는 구조와 책임을 보존합니다. 상세 설명은 [디렉터리 설계](docs/architecture/directory-structure.md), [설계 결정](docs/design-decisions/README.md), [DB 설계 v0.4](docs/db/v0.4/README.md)를 참고하세요. `docs/directory-architecture/`는 기존 구조 제안의 보관본입니다.

## 개발과 검증

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
