# DB 관리 안내

## 현재 기준

실행 스키마의 정본은 Backend의 [`src/main/resources/db/migration/`](../../src/main/resources/db/migration/)이다. MySQL 8.4/InnoDB, `utf8mb4_0900_ai_ci`, UTC 저장을 사용한다. 통합 V1은 기존 V1~V3과 검토된 M3/M4 물리 제약을 빈 DB에 한 번에 생성한다. 업무 기능 전체의 구현 완료를 뜻하지 않는다.

- [개발 DB 전환 방침과 순서](foundation-upgrade.md)
- [전체 구축 SQL](schema-full.sql): 통합 V1을 적용한 일회용 MySQL에서 추출하고 복원 검증한 대체 설치 경로. 실제 Flyway 이력과 기준 권한 시드가 포함된다. 빈 DB에만 적용한다.
- [ERD](erd.mmd): 적용된 DB의 전체 컬럼과 외래 키에서 생성한다. `flyway_schema_history`는 도표에서 제외한다.
- [당시 검토 초안](proposals/consolidated-v1/README.md): 채택 전의 검토 맥락이며 실행 정본이 아니다.

## 새 개발 DB 준비

관리자가 새 빈 스키마와 접속 계정을 준비한다. 다른 서비스의 스키마를 재사용하지 않는다. 실제 자격증명은 Git에 올리지 않는 `.env` 또는 프로세스 환경 변수로 전달한다. 변수와 실행 방법은 [프로젝트 안내](../../README.md#로컬-서버-실행)를 따른다.

기본 설치는 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 새 스키마를 가리키는지 확인한 뒤 `./gradlew bootRun`을 실행하는 경로다. 앱의 Flyway가 V1을 적용하고 Hibernate는 `validate`만 수행한다. Windows에서는 `./gradlew.bat bootRun`을 사용한다.

대체 설치가 필요한 경우에만 `schema-full.sql`을 빈 스키마에 한 번 실행한다. Flyway가 적용한 DB 위에 덧붙이지 않는다. 이 파일에 포함된 이력은 검증된 DB에서 추출한 것으로, 기존 DB 이력을 수동으로 맞추는 용도로 사용하지 않는다.

확인할 항목:

1. 성공한 Flyway 이력은 V1 한 건이며 65개 업무·세션 테이블과 이력 테이블이 존재한다.
2. 같은 코드로 다시 시작해도 migration 재적용이나 데이터 삭제가 없다.
3. 새 DB에는 고정 OWNER 자격증명과 업무 데이터가 없다. [별도 OWNER 초기화](../../README.md#운영-사장님-최초-초기화)를 실행하고 지점·상품·초대를 다시 만든다.
4. 개발 DB에서 로그인, 지점 조회, 상품 생성·변경, 직원 초대 흐름을 확인한다. 자동 테스트 결과와 실제 공유 환경 확인을 구분한다.

## 마이그레이션 관리 책임

SQL과 버전은 Backend 한 곳에서 관리한다. 로컬 기본 실행은 앱의 Flyway를 사용한다. Infra는 DB 인스턴스·접속·권한을 관리하며 별도 구형 SQL을 새 기준 DB에 적용하지 않는다.

공유 서버의 실제 적용 작업은 Backend의 동일 커밋·migration을 사용하는 단일 실행 주체로 구성해야 한다. 배포 전용 작업을 선택하면 앱 Flyway를 끄고, 앱을 선택하면 Infra의 자동 SQL 적용을 끈다. 두 경로를 동시에 사용하지 않는다. 현재 Infra는 자체 구형 V1과 앱 Flyway 비활성 설정이 있으므로 Backend PR 병합만으로 인계가 끝나지 않는다.

실제 Infra 설정 변경·RDS 확인·계정 및 TLS 검증은 [Backend #29](https://github.com/OpenStudyService/OpenStudy-Backend/issues/29), [Infra #1](https://github.com/OpenStudyService/OpenStudy-Infra/issues/1)에서 인계한다. Infra #1의 종전 V3 이관·재생성 제외 조건은 이번 사용자 확정 방침으로 대체하고 실제 적용 전 인수 조건을 맞춰야 한다. 이 PR에서는 AWS 설정과 실제 DB를 변경하지 않는다.

## 이후 변경 및 검증

이번 개발 기준선 전환 이후 적용된 V1은 수정하지 않는다. 변경은 V2부터 누적한다. 기존 DB의 체크섬 불일치나 누락 이력을 `repair`, 자동 `baseline`, history 행 수정으로 우회하지 않는다. 앱 시작에 자동 삭제·재생성을 추가하지 않는다.

```powershell
./gradlew.bat test bootJar --no-daemon
```

Java 21과 Docker가 필요하다. 테스트는 전용 Testcontainers DB만 사용한다. 전체 SQL 재생성이 필요하면 `FoundationMigrationTests`에 `-PfoundationExportDir=build/consolidated-v1-export`를 전달한다. 생성한 `schema-full.sql`을 검토해 정해진 위치에 갱신하고 원시 metadata는 커밋하지 않는다.
