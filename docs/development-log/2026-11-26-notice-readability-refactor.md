# 2026-11-26 notice 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음 중 여덟 번째 모듈이다. notice 모듈(12개 파일, 약 720줄)은 내부 공지의 목록·상세·등록·수정·상태 변경을 담당한다. 대상 지점 권한, 게시 상태 전이, 멱등 등록도 포함한다. 다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`·`if/else`, `var`
- 한 줄에 여러 대입·`put`을 쓴 코드
- 130자가 넘는 SQL 조각과 응답 생성
- `update`의 긴 람다 안에 있는 다음 대상 지점 결정 분기와 상태 전이 조건
- 이름이 동사로 시작하지 않는 메서드(`item`, `detail`, `writable`, `visible`, `compare`, `label`, `flag`)

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 `NoticeService.list`(홈 요약)와 서비스의 다른 공개 메서드 이름은 그대로 둔다.
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `NoticeInputs`: `status`·`target`·`label`·`branchIds`·`flag` → `toStatus`·`toTarget`·`toLabel`·`parseBranchIds`·`parseFlag`
  - `NoticeSearchRepository`: `branchIds` → `findBranchIds`
  - 서비스의 private 메서드: `visible`·`manageable` → `isVisible`·`isManageable` 등
- 컨트롤러
  - 메서드 이름은 openapi operationId(`listNotices`, `createNotice`, `getNotice`, `updateNotice`, `changeNoticeStatus`)로 바꿨다.
  - 모든 메서드가 `/api/notices` 아래에 있어 이를 클래스 매핑에 두었다. 최종 경로는 같다.
- 검증 순서, 잠금 순서, 다시 확인하는 순서(가시성 → 관리 권한 → 대상 변경 권한 → 버전), 감사 기록 내용은 그대로 둔다.
- 검색 SQL은 120자 제한 때문에 조각을 나눴다. 원래 파일과 문자열 리터럴 토큰을 비교해 SQL이 같음을 확인했다.
  - 차이는 반복되던 `String.join(",", nCopies(n, "?"))`를 `toMarks`로 모아 줄어든 `","`·`"?"` 리터럴뿐이다.
- 감사 기록
  - 등록은 원래 8개 인자 `AuditEvent.change`(사유 없음)를 썼고, 이제 다른 기록과 함께 9개 인자 판에 사유 `null`을 넘긴다.
  - 저장되는 칼럼 값은 같다. 8개 인자 판도 사유를 `null`로 넘기기 때문이다.
  - `AuditEvent` 생성 방식 자체의 변경은 #265에서 다룬다.

## 4. 구현 내용

- `NoticeService`
  - 주입 필드 이름을 타입에 맞췄다.
  - `update`의 다음 대상 지점 결정을 `resolveNextIds`로, 상태 전이 조건을 `isAllowedTransition`으로 뺐다.
  - 멱등 재실행(`replay`)과 감사 기록(`recordChange`)을 메서드로 뺐다.
  - 제목·본문·라벨 길이, 키 길이, 201, 24시간을 상수로 바꿨다.
- `NoticeSearchRepository`
  - WHERE 조각 조립을 줄바꿈했다.
  - 물음표 목록 생성을 `toMarks`로 모았다.
  - 한 줄 조건문을 풀었다.
- `NoticeInputs`: 한 줄 `if`를 풀고 최대 지점 수(500)와 ID 형식을 상수로 바꿨다.
- `Notice` 엔티티: 필드 어노테이션과 한 줄 대입을 풀었다.
- DTO 레코드: 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- notice `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교: 위 `","`·`"?"` 외에 차이 없음.
- 공지와 공지 카드를 다루는 테스트 41개 모두 통과.
  - `NoticeIntegrationTests` 23개
  - `CollaborationContractTests` 13개
  - `HomeSummaryIntegrationTests` 5개
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- #250의 같은 묶음에서 남은 모듈: inquiry
