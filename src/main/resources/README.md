# 백엔드 리소스

`application.yaml`은 공통 DB 접속, JPA 검증, Flyway와 JDBC 세션 설정을 관리한다. 계정 정보는 `DB_USERNAME`, `DB_PASSWORD` 환경 변수로 받는다.

- `db/migration/`: 업무 테이블과 JDBC 세션 테이블의 버전별 DDL 위치.
- `db/seed/local/`: 자동 스키마 마이그레이션과 분리한 로컬 시연 데이터 위치.

초기 SQL은 `db/migration/V1__init_schema.sql`에 있다. Hibernate는 `ddl-auto: validate`로 스키마를 검증하고 Spring Session은 `initialize-schema: never`로 자동 생성을 하지 않는다. 스키마 변경은 Flyway에서 관리하며 시드 구현은 후속 작업이다. 비밀값은 환경 변수 또는 추적하지 않는 로컬 설정으로 관리한다. 설정 추가와 실제 DB 기동 검증 완료는 구분한다.
