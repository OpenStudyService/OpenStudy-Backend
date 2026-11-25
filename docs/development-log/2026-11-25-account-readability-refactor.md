# 2026-11-25 account 모듈 가독성 정리

## 1. 배경 / 문제

백엔드 코드에는 줄인 필드 이름(`repository`, `limiter`, `encoder`), `var`, 중괄호 없는 한 줄 `if`, 120자를 넘는 긴 줄과 SQL 문자열이 많아 읽기 어렵다. 모듈 단위로 가독성을 정리하는 계획을 #250에 등록했고, account 모듈을 첫 적용 대상으로 정했다. 이전에 정리한 auth 모듈(#164)과 비밀번호 재설정 메일(`f630c61`)의 패턴을 기준으로 삼았다.

## 2. 검토한 선택지

- 진행 범위: 백엔드 전체(767개 파일, 약 4.9만 줄)를 한 번에 고치는 방법과 모듈 단위로 나누는 방법을 검토했다. 한 번에 고치면 리뷰와 회귀 확인이 어려워, 모듈 단위로 나누고 account 모듈부터 적용하기로 사용자와 정했다.
- 컨트롤러 메서드 이름: 동사형 이름을 새로 짓는 방법과 계약 문서(`openapi.json`)의 operationId를 따르는 방법이 있었다. 계약 문서와 코드에서 같은 이름으로 찾을 수 있도록 operationId를 따랐다.

## 3. 결정 및 이유

- 동작, API 경로, 응답, 예외 메시지, SQL 의미는 바꾸지 않는다. 형식과 이름만 정리한다.
- 주입 필드 이름은 타입 이름을 따른다(`repository` → `accountCredentialRepository`, `limiter` → `loginAttemptLimiter`, `resetMail` → `passwordResetWorker`).
- 컨트롤러 메서드 이름은 operationId와 맞춘다(`change` → `changeMyPassword`, `view` → `getEmployeeAvatar` 등). 공통 경로 `/api`는 클래스 `@RequestMapping`으로 옮겼다.
- 리포지토리·도우미 메서드는 동사로 시작하게 바꿨다(`account` → `findAccount`, `sent` → `markSent`, `retry` → `scheduleRetry`, `Passwords.hash` → `hashToken`, `AvatarService.format` → `detectFormat`).
- 긴 SQL 문자열은 text block으로 바꿨다. 공백과 줄바꿈만 달라지고 쿼리 의미는 같다.
- 이미 정리된 `SmtpPasswordResetMailSender`, `PasswordResetMailConfiguration`은 손대지 않았다.
- import 순서는 #250에서 아직 정하지 않았으므로 파일별 기존 순서를 유지했다.

## 4. 구현 내용

- 컨트롤러: `AccountCredentialController`, `AvatarController`. 메서드·필드 이름 변경, 클래스 단위 경로, 파라미터 줄바꿈, 지역 변수로 중첩 호출을 풀었다. `AvatarController`의 업로드 파일 검사는 이름 있는 boolean(`exactlyOneFile`)과 `readBytes`로 나눴다.
- 서비스: `AccountCredentialService`는 필드 이름 변경, 입력 필드 이름 상수화, `findActiveAccount`로 중복 조회 추출, `isUsable` 조건 분리. `AvatarService`는 `@Slf4j`, 파일 시그니처 상수화, 이름 변경. `PasswordResetDelivery`는 작업자 ID 정규식과 후보 배수를 상수로 빼고, `finishLockedRow`·`isClaimedBy`로 완료 처리를 나눴다. `PasswordResetWorker`는 `@RequiredArgsConstructor`로 바꿨다.
- 리포지토리: `AccountCredentialRepository`의 SQL을 text block으로 바꾸고, 결과 변환 변수 `c`를 `columns`로 바꿨다.
- 기타: `Passwords`, `AccountTargets`, `AvatarStorageConfiguration`(`createS3Store` 추출), `S3AvatarStore`, `LocalAvatarStores`.
- 테스트: `AccountCredentialUnitTests`, `AvatarUnitTests`, `PasswordResetDeliveryUnitTests`의 호출부를 바뀐 이름으로 고쳤다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- account 모듈의 `src/main`에 `var`, 중괄호 없는 `if`·`for`가 남아 있지 않은 것을 검색으로 확인했다.
- `./gradlew test --tests "com.openstudy.modules.account.*" --tests "com.openstudy.architecture.*" --tests "com.openstudy.modules.auth.*"`: 184개 통과, 실패 0.
  - 첫 실행에서는 `ProfileIntegrationTests` 10건이 테스트용 MySQL 컨테이너를 띄울 Docker 환경을 찾지 못해 실패했다. Docker가 응답하는 것을 확인하고 다시 실행해 모두 통과했다.
  - account 통합 테스트(`AccountSecurityIntegrationTests`)와 계약 테스트(`AccountContractTests`)가 포함된다.
- account 모듈은 다른 모듈에서 참조하지 않는 것을 검색으로 확인했다.
- 전체 테스트 `./gradlew test`: 1,270개 중 실패 0, 건너뜀 28 (BUILD SUCCESSFUL, 약 18분). 건너뛴 28개는 domainlens·ai 패키지의 조건부 테스트(로컬 모델·실제 AI 호출이 필요한 경우)로, 이번 변경과 무관하다.
- 실제 SMTP 발송과 S3 저장소 연동은 검증하지 않았다.

## 6. 남은 작업 / 보류 사항

- #250의 다음 모듈(auth 나머지, common, config, bootstrap)
- import 순서와 와일드카드 import 기준 결정
- 한글 메시지와 Javadoc 한 줄은 120자 기준을 넘는 곳이 일부 남아 있다. 메시지 문자열은 나누지 않았다.
