# 2026-11-26 authorization 모듈 가독성 정리

## 1. 배경 / 문제

#250의 세 번째 묶음 중 세 번째 모듈이다. authorization 모듈(23개 파일, 약 1,240줄)은 지점 권한 판단, 명령 잠금 순서(`AuthorizedCommands`), 요청 인터셉터, 소속·권한 프리셋을 담당한다. 거의 모든 업무 모듈이 이 모듈에 의존한다. 다음 문제들이 읽기를 어렵게 했다.

- 인터셉터의 5단 중첩 삼항식
- 한 줄 `if`·`for`
- 공백 없이 몰린 프리셋 서비스
- 명령마다 반복되는 잠금 세 줄
- 문자열을 이어 붙인 네이티브 SQL

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 public 이름은 바꾸지 않았다.
  - `AuthorizationService`, `AuthorizedCommands`, `MembershipService`, `PermissionCatalog`의 메서드
  - `PermissionPresetService.validateCopy`·`lookup`
  - `BranchAction`의 필드
  - `AuthorizationRepository`의 `current`·`currentLocked`·`activeOwners`·`activeAdministrators`: 테스트가 직접 호출한다.
- 컨트롤러 메서드는 openapi operationId에 맞췄다(`listPermissionDefinitions`, `listPermissionPresets`, `createPermissionPreset`, `updatePermissionPreset`).
- 모듈 안에서만 쓰는 저장소 쿼리는 이름에 잠금 종류를 드러냈다.
  - 예: `accountLocked` → `findAccountForShare`, `guard` → `findGuardForUpdate`, `lockedPermissionCodes` → `findPermissionCodesForShare`
- 잠금 순서와 쿼리 문장은 그대로 둔다.
  - 네이티브 SQL은 텍스트 블록으로 옮기면서 토큰(`id=:id` 같은 붙여 쓴 비교까지)을 바꾸지 않았다. 달라진 것은 줄바꿈과 들여쓰기뿐이다.
  - `lockMemberships`의 잠금 순서(직원 오름차순 × 지점 오름차순)도 같다.
- 조건을 이름 있는 boolean으로 나눌 때, 원래 단락 평가로 건너뛰던 호출은 그대로 건너뛰게 했다.
  - 예: 프리셋 목록의 OWNER 여부 확인 뒤 범위 계산
  - 부작용 없는 `permits` 계산만 미리 했다.

## 4. 구현 내용

- `AuthorizationWebConfiguration`
  - 익명 인터셉터 본문을 `authorize`로 옮겼다.
  - 범위 검사가 필요 없는 경로 판단을 `isOutsideBranchScope`로 뺐다.
  - 경로별 행동 결정을 `toAction`의 `if` 순서로 바꿨다. 판단 순서는 원래 삼항식과 같다.
- `AuthorizedCommands`
  - 모든 명령에 반복되던 guard → 직원 → 지점 잠금을 `lockFoundation`으로 묶었다. 호출 위치와 순서는 같다.
  - 활성 계정 확인을 `requireActive`로 뺐다.
- `AuthorizationService`
  - 한 줄 `if`를 풀고, 역할·상태 조건을 이름 있는 boolean으로 나눴다.
  - 반복 문자열(`ACTIVE`, `MANAGER`, `STAFF`, `all`)을 상수로 바꿨다.
- `PermissionPresetService`
  - `@RequiredArgsConstructor`를 썼다.
  - 멱등 키 검증(`requireValidKey`), 권한 검증(`validateGrants`), 응답 변환(`toView`)을 메서드로 정리했다.
  - 상수 이름을 의미가 드러나게 바꿨다(`CODES` → `ASSIGNABLE_CODES`, `CREATE` → `CREATE_OPERATION`).
- `MembershipService`·`PermissionCatalog`: 주입 필드 이름을 타입에 맞추고 스트림을 줄바꿈했다.
- 엔티티(`BranchMembership`, `PermissionDefinition`, `PermissionPreset`)
  - Lombok `@Getter`·`@NoArgsConstructor(PROTECTED)`로 바꿨다.
  - 권한 집합 필드는 getter를 만들지 않도록 `@Getter(AccessLevel.NONE)`을 붙였다.
- DTO 레코드와 `BranchAction`의 긴 주석을 줄바꿈했다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- authorization `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- 인터셉터와 명령 잠금은 모든 API에 영향을 주므로 전체 테스트를 실행했다. 결과는 아래에 덧붙인다.

## 6. 남은 작업 / 보류 사항

- `PermissionPresetService`에도 멱등 키 검증과 SHA-256 계산이 따로 있다(branch 모듈과 같은 상황). 공통화는 이번 범위에서 제외했다.
- #250의 같은 묶음에서 남은 모듈: invitation
