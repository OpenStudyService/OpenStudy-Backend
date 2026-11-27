# 2026-11-27 task 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 일곱 번째 모듈이다. task 모듈(23개 파일, 약 1,200줄)은 다음을 담당한다.

- 지점 업무의 등록·수정·상태 변경·요약
- 체크리스트 항목과 변경 이력
- 채팅에 공유하는 업무 카드

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성자·변경 메서드
- 한 줄짜리 enum
- 요약 합계를 `long[5]` 배열 칸 번호로 더하는 코드
- 체크리스트 진행률을 `long[2]` 배열로 넘기는 코드
- 120자를 넘는 줄(응답 생성 17·19개 인자, SQL, 이력 기록)
- 코드 중간의 완전한 이름(`java.security.NoSuchAlgorithmException`)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `lastSortOrder`, `progress`, `counts`, `events`
  - 헬퍼: `TaskInputs.status`·`priority`·`label`·`body`·`version` 등
  - 서비스 private: `edit`, `saved`, `advanced`, `visible`, `editable`, `record`, `detail`, `items` 등

## 2. 검토한 선택지

- `long[2]` 진행률은 이름 있는 private 레코드 `ChecklistProgress(total, done)`로 바꿨다. 기본값 `new long[2]`는 `ChecklistProgress.NONE`(0, 0)으로 옮겼다.
- 응답 레코드는 모두 생성 지점이 한 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다. `TaskCardView.inaccessible()`도 한 곳에서 만드는 고정 값이라 그대로 두었다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 이름은 그대로 둔다.
  - `TaskService.list`·`summary`·`completedBetween`(home)
  - `TaskCardService.accessible`·`cards`(chat)
  - `TaskFilters.parse`, DTO
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `TaskChecklistItemRepository`: `lastSortOrder` → `findLastSortOrder`, `progress` → `findProgress`
  - `TaskSearchRepository`: `counts` → `countByBranch`, `events` → `findEvents`
  - `TaskInputs`
    - `parseStatus`, `parsePriority`, `toLabel`, `parseBody`, `parseDueAt`, `parseInstant`
    - `parseChecklist`, `parseId`, `parseFlag`, `parseVersion`
  - `TaskService`
    - private: `applyEdit`, `saveAndView`, `advanceVersion`, `findItem`, `findVisibleTask`, `canEdit`, `recordEvent`, `toDetail`, `toListItems` 등
  - `TaskCardService`: `permits` → `canRead`
- 컨트롤러
  - 메서드 이름은 openapi operationId 11개로 바꿨다.
  - `/tasks`와 `/assignee-candidates`가 서로 다른 최상위 경로라 클래스 매핑 `/api`를 그대로 둔다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증, 단락 평가
  - 명령 공통 틀: 범위 → 상위 잠금 → 편집 권한 → 버전
  - 수정: 비교 → 담당자 확인 → 이력(내용·담당자·마감 순) → 변경 → 저장
  - 업무 카드: 읽기 권한 확인을 목록 거르기와 카드 만들기에서 한 번씩 하는 원래 방식
- 이력 행과 직원 이름 조회 횟수를 바꾸지 않았다.

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다.
  - 요약 합계의 `long[5]`를 이름 있는 변수 다섯 개로 바꿨다.
  - 201·24시간·길이 제한·`SHA-256`을 상수로 바꿨다.
  - 목록 항목·체크 항목·이력·카드 변환을 메서드로 나눴다.
- `TaskSearchRepository`
  - 네 번 쓰던 `" FROM work_tasks t WHERE "`와 집계 열 목록을 상수로 뺐다.
  - 이력 조회 SQL을 text block 상수로 옮겼다.
  - WHERE 조립을 `appendWhere`로 바꾸었다. 바인딩 순서와 SQL 조각은 그대로다.
- `TaskChecklistItemRepository`: JPQL을 text block으로 옮겼다.
- 엔티티: 필드 어노테이션과 한 줄 대입을 풀었다.
- enum 2개: 상수를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- task `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교(`origin/main` 대비)
  - `TaskChecklistItemRepository`·`WorkTaskRepository`: 차이 없음.
  - `TaskSearchRepository`: 네 번 쓰던 `" FROM work_tasks t WHERE "`가 상수 하나로 모인 것뿐이다. 실행되는 SQL은 같다.
- 업무와 이를 쓰는 모듈의 13개 테스트 클래스, 170개 테스트: 모두 통과(실패 0, 건너뜀 0).
  - task, chat(업무 카드 포함), home 모듈 전체
  - `AttendanceConcurrencyIntegrationTests`, `ShiftConcurrencyIntegrationTests`, `LocalPreviewIntegrationTests`
  - 계약 테스트 2개: `CollaborationContractTests`, `AggregationContractTests`
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: chat
