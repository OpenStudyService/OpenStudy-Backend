# 2026-11-26 bootstrap 패키지 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 단계다. bootstrap 패키지는 운영자가 서버 밖에서 직접 실행하는 OWNER 초기화 명령(`OwnerBootstrap`) 한 파일이다. 80줄 가까운 `run` 메서드 안에 환경 읽기, 입력 검증, 마이그레이션 확인, 트랜잭션과 SQL 다섯 개가 중첩된 try 블록으로 들어 있었다. 한 줄 `if`, `var`, 문자열 연결 SQL, 매직 넘버(254, 100, 8, 72, 12, 종료 코드)도 읽기를 어렵게 했다.

## 2. 검토한 선택지

- JDBC 코드를 별도 클래스로 나누는 방법: `PersistenceAccessTests`가 이 파일 하나만 plain JDBC 예외로 허용하므로, 파일을 나누면 아키텍처 규칙도 바꿔야 한다. 이번 범위(형식 정리)를 벗어나 택하지 않았다.
- 같은 파일 안에서 private 메서드로 나누는 방법: 규칙과 공개 API(`main`, `run`, `SilentMigrationLogger`)를 그대로 두고 흐름만 드러낼 수 있어 이 방법을 택했다.

## 3. 결정 및 이유

- 공개 이름(`run`, `main`, `SilentMigrationLogger`)과 출력 문구, 종료 코드(0·1·2), 예외 종류(검증 실패는 `IllegalArgumentException` → 2, 그 외 → 1)는 그대로 둔다.
- 잠금 순서(guard → OWNER → 같은 이메일)와 비밀번호 해시를 잠금 전에 계산하는 순서도 유지했다.
- 여섯 개 환경 값은 메서드 사이에 넘기기 쉽도록 private record `BootstrapSettings`로 묶었다. 값이 자격 증명을 담으므로 출력하지 않는다는 주석을 달았다.

## 4. 구현 내용

- `run`을 `readSettings` → `validateSettings` → `requireMigratedSchema` → 해시 → `createOwner` 흐름으로 나눴다.
- 트랜잭션 안의 단계를 `lockAdministrationGuard`, `ownerExists`, `emailExists`, `insertOwner`, `insertAuditEvent`로 뺐다.
- 검증 조건을 `validUrl`·`validEmail`·`validName`·`validPassword`로, 마이그레이션 조건을 `noMigrationApplied`·`hasPendingMigrations`로 나눴다.
- 길이·바이트 제한, bcrypt 강도, 마이그레이션 위치, 종료 코드, 이메일 정규식을 상수로 뺐다.
- SQL 다섯 개를 텍스트 블록 상수로 옮겼다. 공백과 줄바꿈만 달라지고 문장은 같다.
- `var`를 실제 타입(`PreparedStatement`, `ResultSet`, `MigrationInfoService`)으로 바꾸고, 모든 `if`에 중괄호를 붙였다.
- `SilentMigrationLogger`의 한 줄 메서드를 `@Override`와 본문이 나뉜 형식으로 바꿨다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- 전체 테스트와 slow 테스트 결과는 아래에 덧붙인다.

## 6. 남은 작업 / 보류 사항

- #250의 다음 단계: employee, branch, authorization, invitation
