# 2026-11-26 컨트롤러 공통 경로 접두사 복원

## 1. 배경 / 문제

#250 가독성 정리 중 여러 컨트롤러의 클래스 `@RequestMapping`을 `/api`로 줄이고, 메서드마다 나머지 경로를 반복해 넣었다.

- 근거로 삼은 규칙은 "공통 경로 접두사는 클래스에 두고 메서드에는 나머지만 쓴다"였다.
- 그런데 예시의 `AuthController`가 `/api`만 둔 것을 일반 규칙으로 잘못 읽었다. `AuthController`는 메서드 경로가 `/auth/...`와 `/me...`로 갈라져서 `/api`만 둔 것이다.
- 그 결과, 모든 메서드가 같은 자원 경로를 공유해 이미 규칙을 지키던 컨트롤러가 오히려 반복이 늘어난 형태로 바뀌었다.

리뷰에서 지적을 받아 되돌린다.

## 2. 검토한 선택지

- 남은 모듈 PR에서 함께 고치기: 이미 병합된 모듈을 다른 모듈 PR에 섞게 되어 리뷰 범위가 흐려진다.
- 별도 PR 하나로 병합된 컨트롤러를 모두 되돌리기: 변경이 매핑 어노테이션뿐이라 한 번에 검토하기 쉽다. 이 방법을 택했다.

## 3. 결정 및 이유

- 공통 접두사는 그 컨트롤러의 모든 메서드가 공유하는 가장 긴 경로로 정했다. 메서드 경로가 서로 다른 자원으로 갈라질 때만 `/api`를 둔다.
- 이 기준을 로컬 규칙 문서(`docs/architecture/code-readability.md`, Git 미포함) 8장에 문장으로 보탰다.
- 경로가 실제로 갈라지는 `BranchClosureController`, `CustomerMergeController`와 메서드가 하나뿐인 `PermissionDefinitionController`, `ApprovalStatusController`는 그대로 둔다.
- 아직 열려 있는 #262(customerhistory)는 그 브랜치에서 따로 고쳤다(`/api/customers/{id}`).

## 4. 구현 내용

다음 컨트롤러의 클래스 매핑을 원래 접두사로 되돌리고, 메서드 매핑에서 그 부분을 뺐다. 메서드 이름(operationId)과 그 밖의 정리는 유지한다.

| 컨트롤러 | 클래스 매핑 | 처음 바뀐 PR |
|---|---|---|
| EmployeeManagementController | `/api/employees` | #256 |
| BranchController | `/api/branches` | #257 |
| PermissionPresetController | `/api/permission-presets` | #258 |
| InvitationManagementController | `/api/invitations` | #259 |
| InvitationAcceptanceController | `/api/invitations` | #259 |
| CustomerController | `/api/customers` | #261 |

- 경로가 접두사와 같은 메서드는 `@GetMapping`·`@PostMapping`처럼 경로 없이 쓴다. 가독성 정리 전 원래 코드와 같은 형태다.
- 최종 URL은 바뀌지 않는다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- employee·branch·authorization·invitation·customer 모듈 테스트와 architecture(계약) 테스트: 221개 모두 통과.
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 매핑 어노테이션만 바뀌고 로직 변경이 없어서, 영향받는 모듈과 계약 테스트로 충분하다고 판단했다(사용자 결정).

## 6. 남은 작업 / 보류 사항

- facility, inquiry, notice, task, locker 컨트롤러는 원래부터 `/api` + 메서드 전체 경로 방식이다. 각 모듈의 가독성 정리 PR에서 같은 기준으로 판단한다.
  - inquiry·notice는 모든 메서드가 한 자원 아래에 있다.
  - facility·task·locker는 경로가 갈라진다.
