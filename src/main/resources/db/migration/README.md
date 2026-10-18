# 스키마 마이그레이션

업무 테이블과 Spring Session JDBC 테이블의 버전별 DDL을 관리한다. `V1__init_schema.sql`은 초기 스키마이며, Flyway와 flyway-mysql 의존성은 프로젝트에 포함되어 있다. 최초 적용 전 검토·전용 DB 실행 검증이 필요하다.

기준 DB는 MySQL 8.4(Debian)/InnoDB다. 단일 마이그레이션 순서를 사용하고 중복 버전을 방지한다. 시연 데이터와 최초 사장님 비밀번호는 자동 마이그레이션에 포함하지 않는다.

적용된 마이그레이션은 수정하지 않고 다음 버전의 SQL을 추가한다. DB·계정 생성은 관리자 사전 준비 단계이며 마이그레이션 SQL에 `CREATE DATABASE`나 `USE`를 포함하지 않는다. 자세한 절차는 [DB 관리 안내](../../../../../docs/db/README.md)를 따른다.
