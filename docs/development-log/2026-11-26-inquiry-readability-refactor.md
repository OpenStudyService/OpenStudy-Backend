# 2026-11-26 inquiry 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음 중 마지막 모듈이다. inquiry 모듈(17개 파일, 약 780줄)은 고객 문의의 대리 등록·목록·요약·상세·이력과 담당자 변경·답변·상태 변경을 담당한다. 다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성자
- `long[4]` 배열로 합계를 내는 요약 집계
- 130자가 넘는 응답 생성
- 이름이 동사로 시작하지 않는 메서드(`history`, `edit`, `visible`, `items`, `detail`, `label`, `id`)

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 홈 요약이 쓰는 이름은 그대로 둔다: `InquiryService.list`·`summary`, `InquiryFilters.parse`, 응답 DTO.
- 컨트롤러만 부르는 서비스 메서드 `history`는 `listEvents`로 바꿨다.
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `InquiryInputs`: `status`·`unresolvedStatus`·`source`·`label`·`optionalId`·`id` → `toStatus`·`toUnresolvedStatus`·`toSource`·`toLabel`·`parseOptionalId`·`parseId`
  - `InquirySearchRepository`: `counts`·`events` → `countByBranch`·`findEvents`
  - 서비스의 private 메서드: `edit` → `applyChange`, `visible` → `findVisibleInquiry`, `assigneeChangeable` → `canChangeAssignee` 등
- 컨트롤러
  - 메서드 이름은 openapi operationId 8개(`listInquiries`, `getInquirySummary`, `getInquiry`, `createInquiry`, `listInquiryEvents`, `changeInquiryAssignee`, `answerInquiry`, `changeInquiryStatus`)로 바꿨다.
  - 모든 메서드가 `/api/inquiries` 아래에 있어 이를 클래스 매핑에 두었다. 최종 경로는 같다.
- 명령의 확인 순서(범위 → 부모 잠금 → 현재 권한 → 버전)와 등록 순서는 그대로 둔다.
  - 등록 순서: 고객 확인 → 멱등 재실행 → 담당자 확인 → 저장 → 이력
- 등록 이력 메시지의 담당자 이름 조회는 원래처럼 문의 저장 뒤에 한다.
- 검색 SQL 리터럴은 원래 파일과 토큰 단위로 비교해 같은 것을 확인했다.

## 4. 구현 내용

- `InquiryService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 요약 합계의 `long[4]`를 이름 있는 변수 네 개(`total`, `received`, `inProgress`, `answered`)로 바꿨다.
  - 등록 이력 메시지 만들기(`createRegistrationMessage`), 멱등 재실행(`replay`), 목록 항목 변환(`toListItem`)을 메서드로 뺐다.
  - 길이 제한·키 길이·201·24시간을 상수로 바꿨다.
- `InquirySearchRepository`
  - 이력 조회 SQL을 text block 상수로 옮겼다.
  - 집계 열 목록을 상수로 뺐다.
  - 한 줄 조건문을 풀었다.
- `InquiryInputs`·`InquiryFilters`: 한 줄 `if`와 `var`를 풀고 ID 형식을 상수로 바꿨다.
- 엔티티(`Inquiry`, `InquiryEvent`): 필드 어노테이션과 한 줄 대입을 풀었다.
- DTO 레코드: 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- inquiry `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교: 차이 없음.
- 문의와 홈 문의 카드를 다루는 테스트 65개 모두 통과.
  - `InquiryIntegrationTests` 26개
  - `CollaborationContractTests` 13개
  - `AggregationContractTests` 21개
  - `HomeSummaryIntegrationTests` 5개
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 이 PR로 #250의 네 번째 묶음(customer, customerhistory, passhistory, audit, retention, home, workspace, notice, inquiry)의 작업이 모두 올라간다.
- 다음 묶음: payment, ticket, seat, locker, facility, reservation, task, chat
