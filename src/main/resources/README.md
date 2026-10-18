# 백엔드 리소스

`application.yaml`은 IntelliJ에서 생성한 공통 설정 파일이다. 기존 내용을 유지했다.

- `db/migration/`: 업무 테이블과 JDBC 세션 테이블의 버전별 DDL 위치.
- `db/seed/local/`: 자동 스키마 마이그레이션과 분리한 로컬 시연 데이터 위치.

현재 실제 SQL과 DB 접속 설정은 없다. Flyway와 MySQL용 의존성은 생성된 build.gradle에 포함되어 있다. 마이그레이션/시드 구현은 후속 작업이다. 비밀값은 환경변수 또는 추적하지 않는 로컬 설정으로 관리한다.
