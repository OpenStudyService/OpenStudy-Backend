# 2026-11-27 reservation 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 여섯 번째 모듈이다. reservation 모듈(27개 파일, 약 1,500줄)은 다음을 담당한다.

- 스터디룸 원장과 운영 중단·재개
- 선결제 예약, 전액 환불 취소, 이용 시작·완료, 예약 달력

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성 메서드
- 한 줄짜리 enum 생성자·접근자
- 120자를 넘는 줄
  - JPQL 한 줄 문자열
  - 응답 생성(예약 응답 16개 인자)
  - 람다 안의 긴 명령
- 코드 중간의 완전한 이름(`java.time.LocalDate`, `java.security.NoSuchAlgorithmException`)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `branchOf`, `locked`, `lockedShared`, `ofCustomerFamily`, `latestOf`, `calendar`
  - 헬퍼: `RoomInputs.state`·`integer`·`id` 등, `RoomConflicts.timeConflict` 등
  - 서비스 private: `views`, `hours`, `amount`, `slots`, `moved`, `billing`, `usage` 등

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다. 응답 레코드는 모두 생성 지점이 한 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - 서비스 public 메서드 전부
    - `RoomReservationService.ofCustomer`·`recentUsages`(customerhistory)
    - `RoomService.create`(테스트)
  - DTO
  - 통합 테스트가 spy로 가로채거나 호출 횟수를 세는 리포지토리 메서드: `existsOverlapping`, `deleteAllOf`, `deleteStartingAfter`, `findByReservationId`, `findByReservationIdIn`
- 테스트가 직원 이름 조회 횟수를 세므로 조회 지점과 횟수를 바꾸지 않았다.
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - 리포지토리
    - `branchOf` → `findBranch`, `locked` → `findLocked`, `lockedShared` → `findLockedShared`
    - `ofCustomerFamily` → `findByCustomerFamily`, `latestOf` → `findLatestOf`, `calendar` → `findCalendar`
  - 바뀐 리포지토리 메서드는 모두 `@Query`가 있거나 직접 구현한 메서드라 Spring Data의 이름 해석과 무관하다.
  - `RoomInputs`: `parseState`, `parseReservationStatus`, `parseInteger`, `parseId`, `requireValidKey`, `toStoredKey`
  - `RoomConflicts`: `createTimeConflict`, `createDuplicateRoomNo`
  - `RoomService.view` → `toView`
  - `RoomReservationService`: `countHours`, `calculateAmount`, `createSlots`, `applyUsageChange`(이전 `moved`), `toViews`, `toBilling`, `toUsageView` 등
- 컨트롤러
  - 메서드 이름은 openapi operationId 12개로 바꿨다.
  - 클래스 매핑(`/api/rooms`, `/api/room-reservations`)은 그대로 둔다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증, 단락 평가
  - 예약: 멱등 → 룸 공유 잠금 → 지점 일치 → 지점 운영 → 룸 운영 → 시간 → 휴무일 → 정원 → 고객 → 겹침 → 결제 → 예약 → 시간 칸
  - 이용 완료의 남은 시간 칸 반납은 상태 저장 뒤에 일어나도록 `Supplier`로 넘기는 구조를 그대로 두었다.
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265).
- DomainLens 어노테이션은 손대지 않았다(#250에서 domainlens 제외).

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다.
  - 201·200·24시간·길이·키 접두어·결제 결과 값·3600초를 상수로 바꿨다.
  - 멱등 결과 저장을 `completeRequest`로 모았다.
  - 예약 응답 변환을 `toView`로 나눴다.
  - 시간 검증의 부정 조건 나열을 이름 있는 조건 `isValidPeriod`로 바꿨다.
- 리포지토리
  - JPQL을 text block으로 옮겼다.
  - 검색 저장소의 병합 고객 조건을 상수로 뺐다.
- 엔티티: 필드 어노테이션과 한 줄 대입을 풀었다.
- enum 2개: 상수·생성자·접근자를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- reservation `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석과 DomainLens 어노테이션 제외)이 남아 있지 않은 것을 확인했다.
- JPQL 리터럴 토큰 비교(`origin/main` 대비): 리포지토리 6개 모두 차이 없음.
- 스터디룸과 이를 쓰는 모듈의 22개 테스트 클래스, 249개 테스트: 실패 0, 건너뜀 2(환경 변수가 있어야 실행되는 `AiLiveParityIntegrationTests`·`SalesQueryPlanMeasurementTests`).
  - reservation 모듈 전체(예약, 취소·이용, 이름·이용 조회 횟수 검증 포함)
  - passhistory, payment, sales 모듈 전체, `BranchClosureIntegrationTests`, `LocalPreviewIntegrationTests`
  - 계약 테스트 2개: `SpaceOperationsContractTests`, `OperationsExtensionContractTests`
  - `OpenStudySemanticCatalogTests`(엔티티 정리 뒤 DomainLens 카탈로그가 그대로인지 확인)
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: task, chat
