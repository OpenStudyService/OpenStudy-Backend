# 2026-11-01 업무 등록·수정·상태·체크리스트·처리 이력 API

관련: Issue [#75](https://github.com/OpenStudyService/OpenStudy-Backend/issues/75), [계약 문서](../design/foundation/collaboration-api.md), [ADR-M7-001](../db/ADR-M7-001.md), [M7 계약 기록](2026-11-01-m7-collaboration-contract.md).

## 1. 배경 / 문제

업무 관리 화면은 로컬 샘플로만 동작하고 저장은 501로 끝났다. `modules/task`가 비어 있었기 때문이다. #74 계약이 병합되어 업무 10개와 담당자 후보 operation을 구현할 수 있게 됐다.

또한 로컬 `.env`에 실제 Gmail 발송 설정이 켜진 뒤로, 통합 테스트가 그 파일을 읽으면 테스트 초대 메일이 실제로 나갈 위험이 있었다.

## 2. 검토한 선택지

- **테스트의 실제 메일 차단**
  - `application-test.yaml`에 끈다: `spring.config.import`로 들어온 `.env`가 이 파일을 덮어써 막지 못한다.
  - Gradle test 태스크의 system property로 끈다: 시스템 속성은 import된 설정보다 우선한다. 이쪽을 택했다. `.env`는 건드리지 않는다.
- **"MANAGER 이상" 판정**
  - 기존 `BranchAction`을 재사용한다: 모두 행위 권한(grant)이 함께 필요해 계약과 다르다.
  - 역할만 보는 `MANAGE_COLLABORATION`을 추가한다: 이쪽을 택했다. #76·#79도 같은 값을 쓸 수 있다.
- **체크 항목 변경의 업무 version 증가**: 항목만 바뀌면 업무 행이 dirty가 아니라 `@Version`이 오르지 않는다. 직원 계정의 `incrementVersion`과 같은 JPQL 방식을 썼다.
- **담당자 자격 확인 위치**: 다른 모듈 entity에 접근할 수 없으므로(ModuleBoundaryTests) employee 모듈의 공개 service에 `assignees`·`assignable`을 추가했다. `ActorSummary`는 payment 모듈과 묶이지 않게 task dto에 따로 두었다.
- **샘플의 `/api/tasks`**: Spring이 고정 경로를 우선하므로 그대로 둬도 실제 API가 이긴다. 다만 쓰이지 않는 분기가 남고 샘플 테스트가 실제 API를 호출하게 되어, 샘플 경로에서 tasks를 뺐다. 홈 샘플의 업무 항목은 M9 전까지 유지한다.

## 3. 결정 및 이유

- 명령 순서는 입력 검증 → 범위(404) → 잠금 → 현재 수정자 확인(403) → version(409)으로 고정했다. version 충돌 정보가 권한 없는 사용자에게 나가지 않게 하기 위해서다.
- 같은 내용의 수정과 같은 상태 요청은 version·이력·알림을 만들지 않는다.
- 한 번의 `PATCH`는 내용·담당자·기한 묶음별로 이력을 남긴다.
- 목록과 수치는 `TaskSearchRepository`의 같은 WHERE 조립을 쓴다. 지점별 수치는 scope의 모든 지점을 0 포함해 돌려준다.
- `mine`·`overdue`의 `false`는 필터 없음으로 처리한다. 계약은 `true`의 의미만 정의한다.
- `version`만 보낸 `PATCH`는 현재 값을 돌려준다. 계약의 필수 항목이 `version`뿐이기 때문이다.

## 4. 구현 내용

- `modules/task`: entity 5개(enum 포함), repository 4개, `TaskService`, `TaskInputs`, `TaskFilters`, `TaskController`, 응답 DTO
- `BranchAction.MANAGE_COLLABORATION`, `EmployeeAccountService.assignees/assignable`
- `WorkspaceEventService`: `tasks` 알림 매핑
- `LocalPreviewController`: 샘플 경로에서 tasks 제거. `LocalPreviewIntegrationTests`는 샘플 자원을 seats로 바꾸고 `openstudy.local-preview.enabled=true`를 명시해 `.env`에 영향받지 않게 했다.
- `build.gradle`: 테스트에서 초대 메일 비활성화
- OpenAPI 11개 operation을 `implemented`로 변경, 계약 문서에 구현 상태 표기

마이그레이션은 없다.

## 5. 검증 결과

- `./gradlew test` 전체: 46개 suite, 320개 테스트 통과(실패·오류·건너뜀 0). Testcontainers MySQL 사용.
- 신규 `TaskIntegrationTests` 16개: 등록·위조 필드 거부, 잘못된 담당자, 역할별 수정 허용·거부, 소속 회수, 상태 전이·완료 시각, 버전 충돌과 최신값, 불변 필드, 이력의 작업자·전후 값, 다른 업무의 체크 항목, 삭제 API 없음(405), 범위 밖 지점, 필터·정렬·페이지, 수치와 목록 일치, 멱등 재요청을 HTTP로 확인했다.
- 이전 PR에서 `.env` 때문에 실패하던 `LocalPreviewIntegrationTests` 5개도 이번에 통과한다.
- 전체 테스트 로그에 Gmail 관련 내용은 없다. 메일 발송 기능이 켜지지 않은 상태로 실행했다.

**실행하지 않은 것**

- 실제 서버 실행과 Frontend 연결. Frontend는 데모 경로·입력 모양이라 #26에서 계약에 맞춰야 한다.
- 두 사용자의 동시 수정 경합 테스트. 명령은 공통 guard 잠금으로 직렬화되며, 이번에 별도 동시성 테스트는 추가하지 않았다.
- 인프라 서버 검증.

## 6. 남은 작업 / 보류 사항

- Frontend #26: 업무 화면을 이 API에 연결한다.
- 소속 행이 없는 OWNER는 담당자가 될 수 없다. 사장님 계정만 있는 초기 지점에서는 담당자 후보가 비어 업무를 등록할 수 없다. 테스트 때는 직원을 먼저 등록해야 한다.
- 홈(M9)의 업무 수치는 아직 샘플이다.
