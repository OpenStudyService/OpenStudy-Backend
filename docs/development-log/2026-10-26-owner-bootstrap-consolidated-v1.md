# 통합 V1 기준 OWNER 초기화 명령

## 배경 / 문제

사용자가 기존 V1~V3 대신 통합 V1을 실행 경로에 배치하고 빈 DB에 적용했다. 기존 `OwnerBootstrap`은 Flyway 현재 버전이 3 이상이어야 실행되므로, 필요한 테이블이 모두 있는 통합 V1에서도 초기화를 거부했다. 실제 이전 전체 시험의 OWNER 초기화 실패 6개가 이 조건에 연결돼 있었다.

main `3484d1c`에서 `fix/owner-bootstrap-v1` 작업 브랜치를 만들었다. 사용자가 이미 변경한 V1, V2/V3 삭제 및 다른 DB 문서/백업 변경은 그대로 유지했으며 이 작업에서 migration SQL이나 Flyway 이력을 수정하지 않았다.

## 검토한 선택지

- 최소 버전을 3에서 1로 바꾸는 방법도 가능하지만, 이후 미적용 migration이 있어도 초기화를 허용하는 숫자 비교를 계속 유지하게 된다.
- 기존 명령의 암호화·관리 가드·트랜잭션을 재사용하고 적용 상태만 검사하면 통합 V1에 대응하면서 초기화 안전 장치를 유지할 수 있다.

## 결정 및 이유

Flyway validate를 유지하고, 적용된 migration이 존재하며 현재 설정된 migration 중 미적용 항목이 없을 때만 초기화를 진행한다. 버전 번호를 스키마 준비 여부의 고정 기준으로 사용하지 않는다. 이 명령은 migration을 실행하거나 history를 repair/baseline하지 않는다. 실제 필요한 guard 행이 없거나 감사 저장이 실패하면 기존 트랜잭션 경계가 생성을 거부하거나 롤백한다.

## 구현 내용

- `OwnerBootstrap.java`의 `version >= 3` 조건을 적용 이력 존재/미적용 migration 없음 검사로 변경했다.
- BCrypt, 이메일 정규화, 환경 변수 전용 입력, 비밀값 없는 출력, 기존 OWNER 자격증명 보존, 같은 이메일 EMPLOYEE 승격 금지 및 동시 실행 가드는 재사용했다.
- 기존 생성 시험에서 통합 V1의 실제 Flyway 버전 1을 확인한다.
- 미초기화 DB에서는 스키마를 생성하지 않고 거부하는 회귀 시험을 추가했다.
- 별도 JVM의 테스트 classpath에 임시 V2를 추가해, pending migration을 적용하거나 OWNER를 생성하지 않고 거부하는 회귀 시험을 추가했다. 임시 SQL은 JUnit 임시 폴더에서만 생성한다.

## 검증 결과

- Java 21·전용 Testcontainers MySQL 8.4.11에서 `gradlew.bat test --tests '*OwnerBootstrapTests' bootJar --no-daemon` 성공(1분 27초).
- OWNER 초기화 10개 시험, 실패·오류·건너뜀 0. 실제 별도 JVM 명령, 생성/반복/동시 실행, 기존 직원 보존, 입력 제한, 감사 실패 롤백, guard 누락, 미초기화/미적용 migration 거부를 확인했다.
- 실행 JAR 빌드와 변경 파일의 `git diff --check` 통과.
- `graphify update .` 실행 완료. SQL parser 미설치 및 Gradle AST 경고는 탐색 그래프의 제한이며 실제 Java 빌드 결과와 구분한다.
- 사용자의 신규 V1 SHA-256은 `9f79cb372dbfc2c8dc795b08da5a5cba8af2d20041e44427bce84c7e12dd8802`로 변경하지 않았다. 테스트는 자체 컨테이너만 사용했고 사용자 DB에 초기화 명령을 실행하지 않았다.

## 남은 작업 / 보류 사항

전체 테스트는 재실행하지 않았다. 옛 V1/V2 이관을 전제하는 `FoundationMigrationTests`와 최신 버전 3을 기대하는 `DatabaseInitializationTests`는 이번 OWNER 명령 수정의 범위가 아니며 별도 정리가 필요하다. 이번 결과를 전체 시험 통과로 표시하지 않는다. 사용자 DB의 실제 OWNER 생성은 운영자가 환경 변수를 설정한 뒤 별도로 실행한다.
