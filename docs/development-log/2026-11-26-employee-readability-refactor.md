# 2026-11-26 employee 모듈 가독성 정리

## 1. 배경 / 문제

#250의 세 번째 묶음(employee, branch, authorization, invitation) 중 첫 모듈이다. employee 모듈(17개 파일, 749줄)은 직원 계정, 직원 관리 API, 프로필 사진 키를 담당한다. 다음 문제들이 읽기를 어렵게 했다.

- 한 줄에 여러 문장을 쓴 생성자·메서드
- 공백 없는 인자 목록
- 200자가 넘는 스트림 체인
- 한 줄 `switch`, `var`, 한 줄 `if`
- 문자열을 이어 붙인 네이티브 SQL

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 public 이름은 바꾸지 않았다.
  - `EmployeeAccountService`: `byEmail`, `names`, `memberships`, `lookup`, `assignees`, `invitationTarget` 등
  - `AvatarKeys`: `newKey`, `parse`, `path`, `Format.extension`
  - `Employee`의 getter
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `EmployeeRepository`의 네이티브 쿼리(`findLookups`, `findAssignees`, `existsAssignable`, `findChannelCandidates`, `existsChannelEligible`): 저장소는 다른 모듈에서 쓰지 않는다.
  - package-private `EmployeeChanges`의 메서드(`saveVersion`, `recordChange`, `toAccount`, `toStatusLabel` 등)
  - 컨트롤러 메서드와 `EmployeeManagementService.membership` → `updateMembership`
- 직원 관리 API는 openapi 계약에 operationId가 없다. 그래서 컨트롤러 메서드 이름은 동작에 맞춰 `listEmployees`, `getEmployee`, `updateEmployee`, `updateMembership`으로 정했다.
- 검증 순서, 잠금 순서(계정 행 → 소속 행), 감사 기록 내용은 그대로 둔다.
  - 버전 비교 전에 입력을 읽는 순서, 대상 조회 후 입력을 읽는 순서처럼 어떤 오류가 먼저 나는지에 영향을 주는 순서도 원래대로 유지했다.

## 4. 구현 내용

- `Employee`
  - Lombok `@Getter`와 `@NoArgsConstructor(access = PROTECTED)`로 직접 쓴 getter 15개를 대체했다.
  - 한 줄에 여러 대입을 쓴 메서드를 나눴다.
  - 긴 `@SemanticField`를 속성별로 줄바꿈했다.
- `EmployeeRepository`: 네이티브 SQL 다섯 개를 텍스트 블록으로 옮겼다. 공백과 줄바꿈만 달라지고 문장은 같다.
- `EmployeeAccountService`
  - 주입 필드 이름을 타입에 맞췄다.
  - `invitationTarget`의 중첩 람다를 `toInvitationTarget`·`withMembership`으로 나눴다.
  - 반복되던 조회 행 변환을 `toLookups`로 합쳤다.
- `EmployeeManagementService`
  - 한 줄 생성자를 `@RequiredArgsConstructor`로 바꿨다.
  - 조회 결과 조립을 `toMembership`·`toManagedEmployee`로, 상태 변환을 `toDbStatus`로, 키워드 검색을 `matchesKeyword`로 뺐다.
  - 404 생성은 `notFound`로 모았다.
- `EmployeeChanges`
  - 한 줄 `switch` 두 개를 여러 줄로 바꿨다.
  - 두 서비스에 있던 역할 표시 변환을 `toRoleLabel`로 합쳤다.
- `EmployeeManagementController`
  - 클래스 경로를 `/api`로 두고 메서드에 `/employees/...`를 붙였다. 최종 경로는 같다.
  - 파라미터를 한 줄에 하나씩 썼다.
- DTO(`AvatarKeys`, `InvitationTarget`, `ManagedEmployee`): 한 줄 생성자·레코드를 여러 줄로 바꿨다.
- 테스트: `EmployeeManagementIntegrationTests`의 `membership` 호출을 `updateMembership`으로 바꿨다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- employee `src/main`에 `var`, 중괄호 없는 `if`가 남아 있지 않은 것을 검색으로 확인했다.
- `./gradlew test`: 1,270개 중 실패 0, 건너뜀 28 (BUILD SUCCESSFUL, 약 18분). 건너뛴 28개는 ai·sales 패키지의 AI 관련 조건부 테스트다.

## 6. 남은 작업 / 보류 사항

- `package-info`와 일부 Javadoc 줄은 120자를 넘지만 문서 내용이라 손대지 않았다.
- #250의 같은 묶음에서 남은 모듈: branch, authorization, invitation
