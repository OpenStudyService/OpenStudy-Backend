# 2026-11-26 branch 모듈 가독성 정리

## 1. 배경 / 문제

#250의 세 번째 묶음 중 두 번째 모듈이다. branch 모듈(20개 파일, 약 1,000줄)은 지점 관리, 운영시간, 지점 휴무일, 세션용 지점 조회를 담당한다. 다음 문제들이 읽기를 어렵게 했다.

- `BranchService`와 `BranchController`의 공백 없는 코드
- 한 줄에 여러 문장을 쓴 코드
- 10개 넘는 조건을 한 줄로 이은 운영시간 검증
- `var`, 중괄호 없는 `if`·`for`
- 문자열을 이어 붙인 쿼리

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 이름은 바꾸지 않았다.
  - `BranchService`: `list`, `get`, `create`, `lookup` 등
  - `BranchClosureService`: `closed`, `closedDates`, `upcoming`, `SEOUL`
  - `SessionBranchService`의 메서드
  - `Branch` 엔티티의 생성자·getter
- 컨트롤러 메서드는 openapi 계약의 operationId에 맞췄다.
  - `listBranches`, `createBranch`, `getBranch`, `updateBranch`, `changeBranchStatus`
  - `listBranchClosures`, `createBranchClosure`, `updateBranchClosure`, `deleteBranchClosure`
- `BranchClosureRepository`의 쿼리 메서드는 이 모듈에서만 쓰므로 `find…`로 시작하게 바꿨다.
  - `branchOf` → `findBranchIdById`, `locked` → `findForUpdateById`, `between` → `findBetween` 등
- 검증 순서, 잠금 순서, 감사 기록, 멱등 키 처리는 그대로 둔다.
  - 운영시간 검증은 조건을 `isValidWeekday`·`isValidTimes`로 나누되 평가 순서를 유지했다. 요일 중복 검사(`Set.add`)가 앞 조건이 모두 참일 때만 실행되는 것도 같다.
  - PATCH의 null 금지 필드 목록은 원래처럼 `Set.of`로 두었다. 순서를 바꾸면 여러 필드가 동시에 null일 때 보고되는 필드가 달라질 수 있다.

## 4. 구현 내용

- `BranchController`
  - 클래스 경로를 `/api`로 두고 파라미터를 한 줄에 하나씩 썼다.
  - PATCH 본문 해석을 `readUpdate`로 뺐다.
  - 범위 확인을 `requireSelectedScope`로 이름 붙였다.
  - 정규화된 이름 대신 import를 썼다(`ResponseStatusException`, `JacksonException`).
- `BranchClosureController`: 클래스 경로 `/api`, 메서드 이름을 operationId에 맞추고 파라미터를 줄바꿈했다.
- `BranchService`
  - `@RequiredArgsConstructor`를 쓰고 주입 필드 이름을 타입에 맞췄다.
  - 운영시간 검증을 `validateWeeklyHours`·`isValidWeekday`·`isValidTimes`로 나눴다.
  - 멱등 재실행(`replay`), 지점 코드 생성(`createCode`), 운영시간 적용(`applyWeeklyHours`), 키워드 검색(`matchesKeyword`)을 메서드로 뺐다.
  - 매직 넘버를 상수로 바꿨다(7일, 1439분, 2880분, 키 길이 16~128, 201, 24시간).
- `BranchClosureService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 람다 안의 처리를 `requireNoClosureOn`, `toCreated`, `toView`, `toAffectedReservation`, `toNameMap`, `recordChange`, `toSnapshotJson`으로 나눴다.
  - 상수 이름을 의미가 드러나게 바꿨다(`NOTE` → `MAX_NOTE_LENGTH`, `WARNINGS` → `MAX_AFFECTED_RESERVATIONS` 등).
- `BranchViews`
  - 요일별 표시와 시각 변환을 `toDisplay`·`toTime`으로 나눴다.
  - 세션 지점 응답과 같은 표시 규칙을 `toPhone`·`toStatusLabel`로 공유한다.
- 엔티티
  - `Branch`: Lombok `@Getter`·`@NoArgsConstructor(PROTECTED)`를 썼다. 복사본을 돌려주는 `getWeeklyHours`는 직접 두고 Lombok 생성에서 뺐다.
  - `BranchWeeklyHour`: 외부에 공개하던 필드에만 `@Getter`를 붙였다.
  - `BranchClosure`: 어노테이션과 대입을 줄바꿈했다.
- 저장소: `BranchRepository`의 네이티브 SQL과 `BranchClosureRepository`의 JPQL 세 개를 텍스트 블록으로 옮겼다.
- DTO 레코드: 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- branch `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- `./gradlew test`: 1,270개 중 실패 0, 건너뜀 28 (BUILD SUCCESSFUL, 약 18분). 건너뛴 28개는 ai·sales 패키지의 AI 관련 조건부 테스트다.

## 6. 남은 작업 / 보류 사항

- `BranchService`와 `BranchClosureService`에 멱등 키 검증과 SHA-256 계산이 각각 있다. 공통으로 모으면 다른 모듈에도 영향이 있어 이번에는 그대로 두었다.
- #250의 같은 묶음에서 남은 모듈: authorization, invitation
