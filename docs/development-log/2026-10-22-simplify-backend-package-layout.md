# 백엔드 패키지를 익숙한 Spring Boot 계층 구조로 변경

작업일: 2026-10-22 KST. 작업 브랜치: `refactor/simplify-backend-package-layout`.

## 배경과 문제

사용자가 현재의 api/presentation/application/domain/infrastructure 구조는 익숙한 Spring Boot 프로젝트 구조와 달라 코드를 탐색하고 유지보수하기 어렵다고 지적했다. 설명과 대안을 검토한 뒤 config/common/modules 및 모듈별 controller/service/repository/entity/dto(request·response) 구성을 제시했고, 사용자가 해당 구조로 실제 변경을 요청했다.

## 검토한 선택지

- 기존 계층을 유지하면서 Controller·Service 등 하위 디렉토리를 추가하는 방법.
- 업무별 모듈은 유지하고 내부 계층 이름과 공통 패키지를 단순하게 바꾸는 방법.

사용자는 두 번째 구성을 선택했다. 기존 M1-02의 단일 빌드·기능별 모듈화와 M1-04의 JPA 업무 엔티티·별도 DTO 결정은 유지한다.

## 결정과 이유

Controller·Service·Repository·Entity·DTO를 폴더 이름으로 직접 드러내 처음 보는 개발자도 코드 위치를 찾기 쉽게 한다. 별도 api 계층은 제거하고 모듈 간 계약은 명시적으로 공개한 service 진입점과 DTO에 둔다. 다른 모듈의 Entity/Repository 직접 접근과 순환 의존은 계속 금지한다. Java public Service 전체를 자동으로 공개하지 않으며 실제 공개 클래스·메서드·DTO는 해당 기능 구현 시 명시한다.

공통 설정은 config, 업무에 종속되지 않는 공통 기능은 common에 둔다. 현재 common/exception만 준비하고 나머지 기술별 하위 패키지는 실제 기능을 구현할 때 추가한다. 업무 모듈 수와 소유 책임은 유지한다.

## 반영 내용

- 실제 src/main/java/com/openstudy의 20개 모듈을 controller/service/repository/entity/dto/request/dto/response 구조로 변경했다. package-info.java의 모듈 간 접근 안내도 갱신했다.
- 비어 있던 api/presentation/application/domain/infrastructure, bootstrap/shared/platform 및 workforce/domain 하위 골격을 제거했다. 삭제 전 각 경로가 src 안에 있는지, 내부 파일이 비어 있는 .gitkeep뿐인지 확인했다.
- 최상위 config와 common/exception을 만들고 빈 테스트 platform 경로는 common으로 바꿨다. 최초 사장님 업무와 시드의 후속 구현 위치는 설계 문서에 명시했다.
- README, 현재 디렉토리 설계, 설계 결정 안내·기록표, 실행 계획 v1.1의 경로, 후속 아키텍처 검사 안내를 동기화했다.
- 이전 골격 보관본 README에 현재 구조가 아니라는 안내를 추가했다. 기존 보관 소스와 과거 개발 기록, 실행 계획 v1.0은 보존했다.
- 업무 API·SQL·DB 접속·보안 설정·빌드 의존성은 변경하지 않았다. 업무 클래스나 ArchUnit 테스트를 새로 구현하지 않았다.

## 검증 결과

- 20개 업무 모듈 모두 다섯 계층 폴더와 dto/request·dto/response를 가진다. 각 모듈의 말단 보존 폴더는 6개로 총 120개다.
- 실제 main Java 최상위 디렉토리가 config/common/modules인지, 이전 계층 디렉토리가 남아 있지 않은지, 20개 package-info.java의 패키지 선언이 경로와 일치하는지 확인했다.
- 기존 실행·테스트 Java, 설정·SQL 등 리소스, Gradle 빌드·Wrapper 파일 16개가 HEAD와 동일함을 Git의 줄바꿈 정규화를 적용한 해시로 확인했다.
- JDK Eclipse Temurin 21.0.12.1에서 `gradlew.bat build testClasses -x test --offline --no-daemon` 성공. 운영 코드 컴파일·bootJar/JAR 패키징과 테스트 소스 컴파일을 확인했다.
- Docker Linux 엔진이 실행 중이지 않아 기존 MySQL/Testcontainers 통합 테스트는 이번에 실행하지 않았다. 위 명령은 test 작업을 제외한 빌드이며 기존 32개 테스트의 재통과를 의미하지 않는다.
- 변경 문서의 상대 링크와 git diff --check를 확인했다.

## 남은 작업

ArchUnit 의존성과 실행 가능한 경계 검사는 아직 없다. BE-003에서 새 계층과 명시적 공개 Service/DTO 경계에 맞게 구현한다. 실제 업무 코드와 공통 기능은 각 후속 Task에서 추가한다. 통합 테스트 재실행에는 Docker 엔진이 필요하다.
