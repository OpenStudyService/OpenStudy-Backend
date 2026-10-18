# Docker 테스트 DB와 초기 DB 통합 검증

## 배경과 결정

사용자가 Git 반영 전 남은 테스트 DB 분리·자동 연동·Flyway/앱 검증을 계속 진행하도록 요청했다. 기존 `chore/configure-flyway-database` 브랜치에서 JDK 21과 Docker를 사용했다. DB 정책과 HTTP 계약은 변경하지 않았으므로 요구사항/WBS v1.2 및 실행 계획 v1.1 배포본은 보존하고 실제 구현·검증 결과를 이번 기록과 별도 검증 보고서 v1.0에 추가했다.

## 구현

- Spring Boot BOM이 관리하는 `spring-boot-testcontainers`, `testcontainers-mysql`을 테스트 의존성으로 추가했다. 실제 Testcontainers 버전은 2.0.5다.
- `MySqlTestConfiguration`의 Spring bean으로 MySQL 8.4.11 이미지를 digest에 고정했다. 임의 포트·테스트 DB/계정·실행 중 생성한 비밀번호를 사용하고 컨테이너 재사용을 껐다. Spring 컨텍스트가 앱 의존 bean 전후로 컨테이너를 시작·종료한다.
- `@MySqlIntegrationTest`는 test 프로필과 공통 설정을 제공한다. `@ServiceConnection`으로 JDBC·Flyway 접속 정보를 생성한다. 기본 테스트 설정과 Gradle test 작업의 URL을 실패용 주소로 지정해 컨테이너 설정이 없을 때 개발 DB로 자동 연결하지 않도록 했다.
- 실제 HTTP health, Flyway 최초 적용·validate·재실행, 세션 저장·조회·삭제, 두 독립 연결의 미커밋 비노출·롤백·커밋 후 노출, 영업시간·참조 삭제 제약을 테스트했다.
- 실행 안내를 현재 구현에 맞게 갱신했다. 버전이 있는 보고서는 `docs/verification/database-initialization-v1.0.md`로 만들고 자체 개정 이력을 기록했다.

## 검증

JDK 21.0.12.1·Gradle 9.8.1·Spring Boot 4.1.1·Docker Engine 27.2.0·MySQL 8.4.11에서 `gradlew build --no-daemon` 성공. 테스트 32개가 모두 통과했고 실패·오류·건너뜀은 0개다. 컨테이너 OS는 Oracle Linux 9.8이므로 Debian 운영 검수와 구분한다.

빈 DB에서 테이블 58개와 Flyway 이력을 생성했고 Flyway 재실행은 0건이며 가상 데이터가 유지됐다. UTC·collation과 실제 컨테이너 URL/DB명을 확인했다. 테스트 종료 후 Testcontainers 라벨의 잔여 컨테이너가 없었고, bootJar에 Testcontainers/테스트 설정이 포함되지 않는 것도 확인했다. Git 공백·제외 규칙과 로컬 설계 스냅샷 보존을 검사했다.

진행 중 테스트 코드의 비공개 JdbcSession 타입 접근과 tag+digest 표기 호환 문제를 수정했다. 최종 테스트는 실제 MySQL을 사용했으며 개발 DB에 기동·마이그레이션·정리 명령을 실행하지 않았다.

## 남은 범위

업무 API/JPA 매핑·실제 인증/CSRF·메일·업무 잠금 경합 및 Debian 운영 검수는 후속 기능 작업이다. 이번 기반 검증으로 해당 기능이나 전체 M1 완료를 선언하지 않는다. 커밋·push는 수행하지 않았다.
