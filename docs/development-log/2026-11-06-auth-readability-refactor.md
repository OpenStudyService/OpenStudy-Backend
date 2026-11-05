# 2026-11-06 auth 모듈 가독성 정리

## 1. 배경 / 문제

`AuthController`와 `CurrentUserService`는 짧은 필드 이름(`users`, `limiter`, `contexts`, `audit`), `var`, 한 줄에 몰아 쓴 `if`·삼항 연산자, 긴 인자 목록 때문에 읽기 어려웠다. `CurrentUser.User`도 생성자 인자 12개를 순서대로 넘겨 만들어서, 어느 값이 어느 필드인지 알기 어려웠다. 동작은 바꾸지 않고 읽기 쉽게 정리했다.

## 2. 검토한 선택지

따로 검토한 대안은 없다. 형식과 이름만 바꾸고 동작·API 경로·응답은 그대로 둔다는 범위로 진행했다.

## 3. 결정 및 이유

- 필드 이름은 주입 타입 이름을 따른다(`users` → `currentUserService`, `limiter` → `loginAttemptLimiter` 등). 의존성이 무엇인지 이름만 보고 알 수 있게 하기 위해서다.
- `CurrentUserService.current(...)`를 `getCurrentUser(...)`로 바꿨다. 컨트롤러의 `me`·`csrf`도 `getCurrentUser`·`getCsrfToken`으로 바꿨다.
- 생성자 주입은 프로젝트 관례대로 Lombok `@RequiredArgsConstructor`로 바꿨다.
- `CurrentUser.User`는 `@Builder`로 만든다. 필드 이름이 호출부에 드러나 순서 실수를 막는다.
- `AuthController`에 `@RequestMapping("/api")`를 두고 각 경로에서 `/api`를 뺐다. 최종 URL은 같다.

## 4. 구현 내용

- `modules/auth/controller/AuthController.java`: 필드·메서드 이름, 클래스 단위 경로, 줄바꿈 정리.
- `modules/auth/service/CurrentUserService.java`: Lombok 생성자, 메서드 이름 변경, 조건 분기를 블록으로 풀고 `User`를 builder로 생성.
- `modules/auth/dto/CurrentUser.java`: record 필드를 한 줄에 하나로 나누고 `User`에 `@Builder` 추가.
- `modules/auth/dto/LoginRequest.java`, `modules/employee/dto/EmployeeAccount.java`: record 필드 줄바꿈.
- `localpreview/LocalPreviewData.java`: 메서드 이름 변경에 맞춰 호출부 수정.

## 5. 검증 결과

- 예전 메서드 이름 `current(...)`를 호출하는 곳이 `src/main`과 `src/test`에 남아 있지 않은 것을 검색으로 확인했다.
- `./gradlew test bootJar`는 **실행하지 않았다**. 컴파일과 기존 테스트 통과는 확인하지 않은 상태다.

## 6. 남은 작업 / 보류 사항

- 병합 전에 `./gradlew test bootJar`로 컴파일과 기존 인증 테스트(`AuthHttpTests`, `ProfileIntegrationTests` 등) 통과를 확인해야 한다.
