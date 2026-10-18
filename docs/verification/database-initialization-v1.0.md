# 초기 DB와 테스트 환경 검증

버전: v1.0. 검증일: 2026-10-19 KST. 범위: Flyway 초기 스키마, Docker 테스트 DB 분리, 앱 기동·JDBC 세션 저장 및 관련 제약.

## 실행 환경

| 항목 | 실제 확인 값 |
|---|---|
| Java | Eclipse Temurin 21.0.12.1 |
| Spring Boot / Gradle Wrapper | 4.1.1 / 9.8.1 |
| Testcontainers | 2.0.5, Spring Boot BOM 관리 |
| Docker Engine | 27.2.0, Linux engine |
| MySQL | 8.4.11 / InnoDB |
| 컨테이너 OS | Oracle Linux 9.8; 운영 대상 Debian 검수와 구분 |
| 이미지 | `mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70` |
| 테스트 DB | `openstudy_test`, 매핑된 임의 포트와 테스트 전용 계정 |
| JDBC 시간대 / DB collation | `+00:00` / `utf8mb4_0900_ai_ci` |

실행 명령: JDK 21을 JAVA_HOME으로 지정하고 `./gradlew build --no-daemon` 또는 `.\gradlew.bat build --no-daemon`. Docker 엔진이 실행되어 있어야 한다. 개인 DB 자격 증명은 필요하지 않으며 테스트 계정 비밀번호는 실행 중 생성한다.

## 분리와 수명주기

`@MySqlIntegrationTest`는 test 프로필과 `MySqlTestConfiguration`을 사용한다. MySQL 컨테이너를 Spring bean으로 관리하므로 앱 초기화보다 먼저 시작하고 앱의 의존 bean 종료 후 정리한다. 컨테이너 재사용은 끄고 고정 호스트 포트·개발 DB 볼륨을 사용하지 않는다.

test 프로필과 Gradle test 작업의 JDBC/Flyway URL은 연결 불가 주소다. 정상 실행에서는 `@ServiceConnection`이 실제 컨테이너 접속 정보로 대체한다. 이번 통과는 실패용 URL이 설정된 상태에서 확인했다. 컨테이너 설정이나 Docker가 없으면 개발 DB로 fallback하지 않는다. 쓰기 테스트는 실제 DataSource의 DB명과 URL이 해당 컨테이너와 일치하는지도 확인한다.

Spring Boot의 [컨테이너 bean 수명주기와 서비스 연결](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)을 따랐다. 서비스 연결은 JDBC와 Flyway 연결 설정에 적용된다.

## 결과

| 테스트 클래스 | 항목 | 결과 |
|---|---:|---|
| OpenstudyBackendApplicationTests | 실제 임의 포트 서버의 `/actuator/health` 200·UP | 1 통과 |
| DatabaseInitializationTests | 환경·Flyway 재실행·JDBC 세션·두 연결 가시성 | 4 통과 |
| SchemaConstraintsTests | 영업시간 24사례·NULL 수정·지점/취소자 참조 보존 | 27 통과 |
| **전체** | **실패·오류·건너뜀 0개** | **32 통과** |

- 빈 DB에 Flyway V1을 실제 적용해 업무·세션 테이블 58개와 Flyway 이력 테이블 1개를 생성했다. validate 통과, 별도 Flyway 인스턴스의 재실행 결과는 적용 0건이며 V1 이력과 기존 가상 데이터가 보존됐다.
- 실제 앱은 JPA 초기화와 Flyway 이후 Tomcat 임의 포트에서 기동했고 health 요청에 응답했다. 아직 업무 엔티티가 없으므로 이 결과를 모든 업무 JPA 매핑의 검증으로 해석하지 않는다.
- Spring Session JDBC repository로 속성을 저장·재조회하고 삭제 후 세션 및 속성 행 정리를 확인했다. 실제 로그인·쿠키·보안 만료 계약 전체를 검증한 것은 아니다.
- 별도 실제 연결 두 개의 CONNECTION_ID가 다름을 확인하고 미커밋 비노출·롤백·커밋 후 노출을 검증했다. 업무별 잠금 경합·데드락 재시도 검증은 별도다.
- CHECK 거부는 MySQL 오류 3819, 참조 삭제 거부는 1451과 실제 제약 이름으로 확인했다. SchemaConstraintsTests 데이터는 테스트 트랜잭션으로 롤백하고 커밋 시험은 생성한 데이터만 정리했다.
- 빌드의 check·bootJar 생성까지 통과했다. 배포 bootJar에서 Testcontainers·테스트 설정이 제외됨을 확인했다.
- 종료 후 Testcontainers 라벨의 잔여 컨테이너가 없었다. 기존 개발 DB에는 검증·초기화·정리 명령을 실행하지 않았다.

실행 중 발견한 테스트 API의 비공개 JDBC 세션 타입과 digest 이미지 파싱 문제는 각각 공개 SessionRepository 인터페이스 사용과 `mysql@sha256:...` 표기로 수정했다. 최종 통과 결과만 위 표에 집계했다.

## 남은 범위

Debian 운영 환경 검수, 실제 인증·CSRF·초대 메일·업무 JPA 매핑·좌석/결제/예약 경합은 후속 기능 검증이다. 이번 작업은 해당 기능을 구현하거나 완료 상태로 변경하지 않는다. 원격 반영 전 기존 설계 스냅샷 제외·추적 제거와 함께 변경을 검토한다.

## 개정 이력

| 버전 | 일자 | 변경 내용 |
|---|---|---|
| v1.0 | 2026-10-19 | Testcontainers·실패용 기본 접속 설정·V1/Flyway·앱·세션·제약 회귀 테스트의 최초 실행 결과 기록 |
