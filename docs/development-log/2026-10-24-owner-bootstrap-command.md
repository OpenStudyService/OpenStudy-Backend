# 운영 사장님 최초 초기화 명령

## 배경 / 문제

B04의 운영 OWNER 초기화는 공개 가입 API나 일반 서버 기동에 결합하지 않는 제한된 명령이어야 한다. 기존 `local` 프로필의 개발 계정 준비와 분리하고, 재실행으로 기존 사장님의 비밀번호나 상태가 바뀌지 않아야 한다. 이 작업은 B04 중 초기화 명령에 한정하며 세션 보완 전체 완료를 의미하지 않는다.

## 검토한 선택지

- Spring 비웹 프로필의 초기화 runner: 기존 설정 로더를 재사용할 수 있지만 전체 스캔·자동 설정을 제한해야 하며 local preview·서버 컴포넌트와 결합될 수 있다.
- 별도 Java main과 Gradle JavaExec task: Spring 컨텍스트를 만들지 않고 필요한 DB 트랜잭션만 실행한다. 대신 Spring의 `.env` 로더를 사용하지 않으므로 프로세스 환경 주입을 명시해야 한다.

## 결정 및 이유

별도 `com.openstudy.bootstrap.OwnerBootstrap` main과 `ownerBootstrap` task를 채택했다. 일반 서버 main은 Gradle에 명시하여 실행 클래스 탐색이 모호해지지 않게 했다. 운영 실행 환경이 DB 및 OWNER 환경 변수를 주입한다. CLI 인자·Gradle 속성 입력은 사용하지 않으며 `.env`를 자동 해석하지 않는다.

## 구현 내용

- 입력 검사 후 Flyway validate로 기존 마이그레이션을 검증한다. V3 이상이 필요하며 DDL·migration·repair를 자동 실행하지 않는다.
- 이름 1~100자, 정규화한 이메일 최대 254자, 비밀번호 8자 이상·UTF-8 최대 72바이트를 검사한다. 일반 인증 설정과 같은 BCrypt 강도 12를 사용한다.
- `administration_guard(id=1)`을 먼저 잠그고 OWNER 존재 여부를 확인한다. OWNER가 있으면 비활성 상태여도 그대로 둔다. 같은 이메일의 일반 계정을 승격하지 않으며 DB 유일 제약도 유지한다.
- 계정 INSERT와 시스템 감사 INSERT를 한 JDBC 트랜잭션에서 커밋한다. 감사 actor는 NULL(시스템)이며 고정 사유만 기록하고 이메일·비밀번호·해시를 저장하지 않는다. 감사 쓰기 실패도 계정 생성을 롤백한다.
- Flyway CLI 전용 logger는 출력을 생략한다. 성공·거부·DB 실패는 고정 메시지와 종료 코드로 알리고 JDBC URL·예외 원문·비밀값을 출력하지 않는다.
- 일반 서버 기동, 기존 local 시드, HTTP API 및 V1~V3 SQL은 변경하지 않았다. README에 프로세스 환경 주입·반복 실행 의미·종료 코드를 기록했다.

## 검증 결과

- JDK 21, 전용 Testcontainers MySQL 8.4.11에서 `./gradlew test --tests '*OwnerBootstrapTests' bootJar --no-daemon` 성공. 8개 테스트, 실패·오류·스킵 0, 일반 서버 bootJar 빌드 성공.
- 신규 ACTIVE OWNER 및 감사 생성, 기존 OWNER의 SUSPENDED 상태·이메일·비밀번호·버전 보존, 같은 이메일 일반 계정 거부, 동시 실행 시 한 계정·한 감사 기록, 입력 누락·짧은 비밀번호·UTF-8 72바이트 초과 거부, 정확히 72바이트 허용, 감사 INSERT 실패 시 전체 롤백, 가드 누락 거부를 확인했다.
- 실제 별도 JVM main을 테스트 컨테이너 DB에 연결해 실행하여 종료 코드 0, 출력 정확히 `Initial OWNER created.` 한 줄, branches 0 및 웹/preview 미실행을 확인했다. 비밀번호·접속 URL·일반 Spring 서버 시작 로그가 출력에 없음을 검증했다.
- 실제 `ownerBootstrap` Gradle task를 입력 누락·접속 불가 고정 주소로 실행해 DB 연결 전에 Java 종료 코드 2로 거부됨을 확인했다. Gradle 자체는 예상대로 task 실패(종료 코드 1)를 보고한다.
- `git diff --check` 통과. 전체 기존 HTTP 회귀 테스트는 이 독립 CLI 변경에서 재실행하지 않았다. 운영/개발 실제 DB 검증은 수행하지 않았다.

## 남은 작업 / 보류 사항

B04의 승인 제한 세션·유휴 및 최대 수명·로그인 세션/CSRF 교체 보완은 별도 작업이다. 운영/개발 실제 사용자 DB에는 접근하지 않았다. 운영 비밀값 주입과 운영 권한·배포 경로 검수는 실행 환경에서 수행해야 한다.
