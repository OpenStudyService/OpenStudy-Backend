# 2026-11-27 locker 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 네 번째 모듈이다. locker 모듈(31개 파일, 약 1,500줄)은 다음을 담당한다.

- 사물함 원장과 운영 중단·재개, 지점 단위 요금 일괄 적용, 점유 현황
- 유료 배정·연장·반납과 결제 원장 연결

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성 메서드
- 한 줄짜리 enum 생성자·접근자
- 120자를 넘는 줄
  - JPQL 조각과 응답 생성(배정 응답 16개, 요금 응답 11개 인자)
  - 람다 안의 긴 명령
- 코드 중간의 완전한 이름(`java.security.NoSuchAlgorithmException`)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `branchOf`, `locked`, `holding`, `occupying`, `heldBy`, `lockedInBranch`, `counts`, `ofCustomer`
  - 헬퍼: `LockerInputs.today`·`date`·`integer` 등, `LockerConflicts.duplicateLockerNo` 등
  - 서비스 private: `views`, `view`, `terms`, `amount`, `snapshot` 등

## 2. 검토한 선택지

- 결제 1건의 사물함 원장(`LockerAssignmentCharge`) 생성자는 인자가 10개이고, 최초 배정과 연장 두 곳에서 만든다. 날짜 두 개(시작·종료), 금액 두 개(요금·금액)처럼 같은 타입의 인자가 이웃해 있다. 빌더 적용 기준(반복 생성, 같은 타입의 이웃한 인자)에 맞아 생성자에 `@Builder`를 붙였다.
- 응답 레코드(`LockerAssignmentView`, `LockerChargeView`, `LockerView`)는 생성 지점이 한 곳뿐인 전체 매핑이라 생성자를 유지했다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - 서비스 public 메서드 전부
    - `LockerAssignmentService.ofCustomer`·`recentUsages`(customerhistory)
    - `LockerService.create`(테스트)
  - DTO
  - 통합 테스트가 spy로 호출 횟수를 세는 `findByAssignmentIdInOrderByIdAsc`·`saveAndFlush`
- 테스트가 직원 이름 조회 횟수를 세므로 조회 지점과 횟수를 바꾸지 않았다.
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - 리포지토리
    - `branchOf` → `findBranch`, `locked` → `findLocked`, `lockedInBranch` → `findLockedInBranch`
    - `holding` → `findHolding`, `occupying` → `findOccupying`, `heldBy` → `findHeldBy`
    - `counts` → `countByBranch`, `ofCustomer` → `findByCustomer`
  - 바뀐 리포지토리 메서드는 모두 `@Query`가 있거나 직접 구현한 메서드라 Spring Data의 이름 해석과 무관하다.
  - `LockerInputs`: `toBusinessDate`, `toStartInstant`, `parseState`, `parseAssignmentState`, `parseAssignmentStatus`, `parseDate`, `parseInteger`, `parseId`, `requireValidKey`, `toStoredKey`
  - `LockerConflicts`: `createDuplicateLockerNo`, `createPeriodConflict`, `createNotReturned`
  - 서비스 private: `countTerms`, `calculateAmount`, `toViews`, `toChargeView`, `toSnapshot`, `canSeeCharges` 등
- 컨트롤러
  - 메서드 이름은 openapi operationId 12개로 바꿨다.
  - `LockerController`는 `/lockers`와 `/branches/{id}/locker-rate`를 함께 가지므로 클래스 매핑 `/api`를 그대로 둔다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증, 단락 평가
  - 배정: 멱등 → 사물함 잠금 → 지점 일치 → 지점 운영 → 사물함 운영 → 시작일 → 기간 → 고객 → 점유 확인 → 결제 → 배정 → 원장
- 비어 있는 `announce` 메서드도 그대로 둔다. 호출 지점이 정해져 있는 자리여서 이번 정리 범위 밖이다.
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265).
- DomainLens 어노테이션은 손대지 않았다(#250에서 domainlens 제외).

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다.
  - 201·200·24시간·길이·키 접두어·결제 결과 값을 상수로 바꿨다.
  - 멱등 결과 저장을 `completeRequest`로 모았다.
  - 응답 변환을 `toView`·`toBranchOccupancy`·`toChargeView`로 나눴다.
  - 사용 이력 판단을 `wasUsed`로 뺐다.
  - 사물함 상태 결정을 중첩 삼항에서 `if`/`else`로 바꿨다.
- 리포지토리
  - `LockerSearchRepository`·`LockerAssignmentSearchRepository`의 JPQL 조각(`FROM`, 정렬, 병합 고객 조건)을 상수로 뺐다.
  - 바인딩 이름과 순서는 그대로다.
- 엔티티: 필드 어노테이션과 한 줄 대입을 풀었다.
- enum 6개: 상수·생성자·접근자를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- locker `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석과 DomainLens 어노테이션 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교(`origin/main` 대비)
  - `LockerRepository`·`LockerAssignmentRepository`·`LockerSearchRepository`·`LockerOccupation`: 차이 없음.
  - `LockerAssignmentSearchRepository`: 두 번 쓰던 정렬 조각 `" ORDER BY a.createdAt DESC, a.id DESC"`가 상수 하나로 모인 것뿐이다. 실행되는 JPQL은 같다.
- 사물함과 이를 쓰는 모듈의 12개 테스트 클래스, 226개 테스트: 모두 통과(실패 0, 건너뜀 0).
  - locker 모듈 전체(원장·배정·연장·반납, 이름·원장 조회 횟수 검증 포함)
  - passhistory, payment 모듈 전체, `SalesLedgerIntegrationTests`, `LocalPreviewIntegrationTests`
  - 계약 테스트 3개: `SpaceOperationsContractTests`, `OperationsExtensionContractTests`, `AggregationContractTests`
  - `OpenStudySemanticCatalogTests`(엔티티 정리 뒤 DomainLens 카탈로그가 그대로인지 확인)
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: facility, reservation, task, chat
