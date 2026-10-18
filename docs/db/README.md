# DB 관리

스키마의 Git 정본은 [`src/main/resources/db/migration/`](../../src/main/resources/db/migration/)의 Flyway SQL이다. 업무 테이블과 Spring Session JDBC 테이블을 같은 버전 순서에서 관리한다.

## 로컬 사전 준비

1. MySQL 8.4의 전용 개발 DB와 계정을 관리자가 생성한다. [`db_setup_local.sql`](db_setup_local.sql)은 수동 준비용 예시이며 Flyway가 실행하지 않는다. `CHANGE_ME`를 실제 비밀번호로 바꾼 사본은 Git에 추가하지 않는다.
2. IntelliJ 실행 구성이나 로컬 환경에 `DB_USERNAME`, `DB_PASSWORD`를 설정한다. 현재 공통 JDBC 주소는 `localhost:3306/openstudy`이며, 주소 변경은 추적하지 않는 `application-local.yaml` 등으로 재정의하고 해당 프로필을 활성화한다.
3. 검토된 마이그레이션을 아직 초기화되지 않은 전용 DB에 적용한다. 이미 설계 SQL을 수동 적용한 DB라면 초기 V1 실행과 충돌할 수 있으므로 현재 상태를 먼저 확인한다. 기존 데이터가 있는 DB를 자동 초기화하거나 임의로 baseline 처리하지 않는다.
4. Flyway 이력, 테이블·제약 조건, 세션 저장과 앱 기동을 확인한다. 설정 파일이나 정적 검사만으로 실DB 적용 성공을 판단하지 않는다.

JDBC 연결과 Hibernate의 시간대는 UTC이며 영업일 계산은 애플리케이션에서 Asia/Seoul 기준으로 처리한다. `ddl-auto: validate`와 `spring.session.jdbc.initialize-schema: never`를 유지해 스키마 생성 주체를 Flyway로 통일한다.

## 테스트 DB 운영

2026-10-19 변경된 M1-12에 따라 테스트는 Docker 전용 MySQL 8.4/InnoDB에서 수행한다. `@MySqlIntegrationTest`와 Spring 관리 컨테이너·서비스 연결로 수명주기 및 JDBC/Flyway 접속 정보를 제공한다. 개발 DB의 접속·계정·볼륨을 재사용하지 않고 테스트 자원만 정리한다. Docker가 없으면 개발 DB로 자동 대체하지 않고 테스트가 실패한다.

빈 DB의 Flyway 적용·재실행, 세션 저장·조회·삭제, 실제 HTTP health 응답, 두 연결의 롤백·커밋 가시성 및 V1 제약을 검증했다. [검증 보고서 v1.0](../verification/database-initialization-v1.0.md)에 이미지 버전·digest·OS와 결과를 기록했다. 이 기반 검증과 지점·좌석·결제 등 업무별 경합 검증은 구분하며 운영 대상 Debian 검수도 별도로 수행한다.

## 변경 관리

`V1__init_schema.sql`은 초기 스키마다. 적용된 버전의 파일은 수정하지 않고 `V2__...sql` 등 새 버전을 추가한다. 운영 데이터와 충돌 가능성이 있는 DDL은 전용 테스트 DB에서 먼저 확인한다.

`v0.4/`, `v0.5/`와 해당 `OpenStudy_DB_Initial_v0.*.zip` 설계 스냅샷은 로컬 참고용으로 보존하며 Git 추적 대상에서 제외한다. 과거 개발 기록의 해당 경로는 당시 산출물을 가리킨다. 추적 제거 커밋을 원격에 반영해도 과거 Git 이력은 유지된다.
