# 스키마 마이그레이션

업무 테이블과 Spring Session JDBC 테이블의 버전별 DDL 위치다. Flyway와 flyway-mysql 의존성은 기존 프로젝트에 포함되어 있다. 이번 작업에서는 SQL을 추가하지 않았다.

기준 DB는 MySQL 8.4(Debian)/InnoDB다. 단일 마이그레이션 순서를 사용하고 중복 버전을 방지한다. 시연 데이터와 최초 사장님 비밀번호는 자동 마이그레이션에 포함하지 않는다.
