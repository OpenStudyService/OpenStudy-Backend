# 2026-11-25 common 패키지 가독성 정리

## 1. 배경 / 문제

#250의 세 번째 단계다. common 패키지(23개 파일)는 모든 모듈이 쓰는 공통 코드(예외 처리, 요청 필터, 입력 검증, 감사·멱등 기록, CSV 내보내기)다. 중괄호 없는 한 줄 `if`, 한 줄에 여러 필드를 대입하는 생성자, 한 줄 getter, 120자를 넘는 줄이 많았다.

## 2. 검토한 선택지

- public API 이름 변경 여부: `ApiException.validation`, `StrictInput.text`, `AuditEvent.change`, `CsvFile.time` 같은 이름은 수백 곳에서 호출된다. 이름을 바꾸면 모든 모듈이 이 PR에 들어와 모듈 단위로 나눈 #250의 방식이 깨진다.
- 엔티티 getter: 직접 쓴 한 줄 getter를 유지하는 방법과, 다른 모듈 엔티티(51곳)가 쓰는 Lombok `@Getter`·`@NoArgsConstructor(access = PROTECTED)`로 맞추는 방법이 있었다.

## 3. 결정 및 이유

- public API 이름은 유지하고 필드, private·package-private 메서드, 형식만 정리했다.
- 엔티티(`AuditEvent`, `IdempotencyRecord`)는 프로젝트 관례대로 Lombok을 썼다. `IdempotencyRecord`는 기존에 getter가 있던 필드에만 `@Getter`를 붙여 새 public getter가 생기지 않게 했다. 복사본을 돌려주는 `getRequestHash`는 직접 구현을 유지했다.
- `ApiExceptionHandler`의 핸들러 메서드는 Spring만 호출하므로 `handleXxx` 형식으로 이름을 바꿨다.
- 동작, 응답 코드·메시지, 로그 내용은 바꾸지 않는다.

## 4. 구현 내용

- `CsvFile`: `write`를 헤더 블록·표·BOM 단계(`appendHeaderBlock`, `appendTable`, `withBom`)로 나눴다. 수식 시작 문자·구분자·줄 끝을 상수로 뺐다. package-private `text` → `toCell`, `number` → `toNumberCell`.
- `ApiRequestFilter`: `@Slf4j`·`@RequiredArgsConstructor`, 500 응답 작성(`writeInternalError`)과 MDC 복원(`restoreRequestId`) 분리, 로그 빌더 체인 줄바꿈.
- `ApiExceptionHandler`: 핸들러 이름 변경, 반복되는 메시지 상수화, 정규화된 import(`MaxUploadSizeExceededException`).
- `StrictInput`: 와일드카드 import 제거, `version`·`id` 검증을 블록으로 풀고 오류 생성 메서드 추출, `try` 안에서 예외를 던져 잡던 흐름을 `if`로 바꿨다(같은 422 응답).
- `ClientAddressResolver`: 프록시 설정 파싱을 `createProxyMatcher`로 추출, `literal` → `toIpLiteral`, `trusted` → `isTrusted`.
- `AuditEvent`, `IdempotencyRecord`: 필드 어노테이션을 한 줄에 하나로, 한 줄 대입 생성자를 풀어 씀, Lombok 적용.
- `ApiException`: 짝이 맞지 않던 Javadoc(404 설명이 `forbidden` 위에 붙어 있던 것)을 `notFound(String)`으로 옮겼다. `versionConflict` 검증 조건을 이름 있는 boolean으로 나눴다.
- 나머지(`OperationsConflict`, `FixedWindowCounter`, `ListQuery`, `PasswordPolicy`, `TimeRange`, `PageResponse`, `ApiVersion`, `DuplicateKeys`, `IdempotencyStore`, `AuditTrail`, `ApiError`): 중괄호, 줄바꿈, 필드 이름, 상수.
- 테스트: `CsvFileUnitTests`의 `CsvFile.text` 호출을 `toCell`로 고쳤다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- common `src/main`에 `var`, 중괄호 없는 `if`·`for`, 한 줄 메서드 본문이 남아 있지 않은 것을 검색으로 확인했다.
- 공통 영역이므로 전체 테스트를 실행했다. 결과는 아래에 기록한다.

## 6. 남은 작업 / 보류 사항

- 다른 모듈에서 쓰는 common public 메서드 이름(`text`, `choice`, `recorded` 등)은 바꾸지 않았다.
- #250의 다음 단계: config, bootstrap
