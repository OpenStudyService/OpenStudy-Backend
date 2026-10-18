# 테스트 리소스

`fixtures/`에 가상 업무 데이터와 외부 계약 샘플을 둔다. 실제 개인정보·비밀번호·세션·토큰은 저장하지 않는다.

DB 통합 테스트는 `com.openstudy.support.database.MySqlIntegrationTest`를 사용한다. test 프로필과 `MySqlTestConfiguration`을 가져와 Spring이 MySQL 컨테이너 수명주기를 관리하고 `@ServiceConnection`이 JDBC·Flyway 접속 정보를 덮어쓴다. 컨테이너 재사용은 비활성화하며 개발 DB·포트·볼륨을 공유하지 않는다. 이미지의 실제 버전·digest·OS는 [검증 보고서](../../../docs/verification/database-initialization-v1.0.md)에 기록했다.

`application.properties`는 기본 test 프로필을 활성화하고 `application-test.yaml`은 컨테이너가 없으면 연결에 실패할 주소를 설정한다. Gradle test 작업도 JDBC·Flyway 주소를 같은 실패용 값으로 고정하므로 외부 개발 DB 환경 변수보다 우선한다. 정상 테스트는 서비스 연결이 이 값을 대체한다. Docker가 없으면 테스트를 건너뛰거나 개발 DB로 대체하지 않고 실패한다.

같은 테스트 트랜잭션의 CRUD는 롤백한다. 별도 연결·커밋 테스트는 생성한 가상 데이터만 finally에서 정리한다. 테스트 종료 시 Spring 컨텍스트와 Testcontainers가 컨테이너·볼륨을 정리한다. 기존 개발/시연 DB의 데이터를 정리하는 SQL은 실행하지 않는다.
