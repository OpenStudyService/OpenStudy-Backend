# 2026-11-27 seat 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 세 번째 모듈이다. seat 모듈(41개 파일, 약 2,000줄)은 다음을 담당한다.

- 좌석 원장과 운영 중단·재개, 좌석 배치도·점유 현황·점유 추이
- 입실·퇴실·좌석 이동과 이용 기록 조회
- 입실용 고객 정확 일치 조회와 조회 한도

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성 메서드와 한 줄짜리 enum 생성자·접근자
- 120자를 넘는 줄
  - SQL 문자열
  - 응답 생성(이용 기록 응답은 19개 인자)
  - 람다 안의 긴 명령
- 코드 중간의 완전한 이름(`com.openstudy.modules.seat.service.SeatOccupancyTrendService`, `java.security.NoSuchAlgorithmException` 등)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `branchOf`, `locked`, `seatsRegistered`, `stays`, `counts`
  - 헬퍼: `SeatInputs.kind`·`state`·`zone` 등
  - 서비스 private: `views`, `visible`, `items`, `ref`, `rate`, `ratio` 등
- 점유 추이 계산이 반복문 하나에 모두 들어 있어 구간 하나의 계산을 따라가기 어려움

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다. 응답 레코드는 모두 생성 지점이 한 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - 서비스 public 메서드 전부
    - `SeatService.occupancy`(home)
    - `SeatSessionService.usage`·`recentUsages`(passhistory, customerhistory)
    - `checkIn`·`checkOut`(테스트)
  - `CheckInLookupLimiter.check`(단위 테스트), DTO
  - `SeatSessionRepository`의 `exists...` 메서드(통합 테스트가 spy로 가로챈다)
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `SeatRepository`·`SeatSessionRepository`: `branchOf` → `findBranch`, `locked` → `findLocked`
  - 두 메서드 모두 `@Query`가 있어 이름을 바꿔도 Spring Data의 이름 해석은 일어나지 않는다.
  - `SeatOccupancyRepository`: `seatsRegistered` → `findSeatRegistrations`, `stays` → `findStays`
  - `SeatSearchRepository`: `counts` → `countByBranch`
  - `SeatInputs`: `parseKind`, `parseState`, `parseDisplayStatus`, `parseSessionStatus`, `parseZone`, `parseReason`, `parseId`
- 컨트롤러
  - 메서드 이름은 openapi operationId 14개로 바꿨다.
  - `CheckInLookupController`는 메서드가 하나뿐이다. 다른 컨트롤러처럼 클래스에 `/api/customer-lookups`를 두고 메서드에 `/check-in`을 두었다. 최종 경로는 같다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증
  - 입실: 좌석 잠금 → 지점 → 좌석 상태 → 좌석 점유 → 고객 잠금 → 이용권 잠금 → 고객 입실 가능 → 고객 점유 → 이용권 사용 가능
  - 상세 응답: 이동 기록 → 좌석 → 직원 이름 → 이용권 → 고객
  - 단락 평가는 그대로 유지했다. 예: 좌석 번호 중복 확인, 운영 중단 시 점유 확인.
- 점유 추이 계산
  - 구간 하나를 `toBucket`으로 뺐다.
  - 체류가 겹친 초를 세는 부분을 `countOccupiedSeconds`로 뺐다.
  - 합계는 각 구간의 값을 더한다. 계산식과 경계는 같다.
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265).
- DomainLens 어노테이션은 손대지 않았다(#250에서 domainlens 제외).

## 4. 구현 내용

- 서비스 5개
  - 주입 필드 이름을 타입에 맞췄다.
  - 201·200·24시간·키 길이·이름 길이·`SHA-256`·키 접두어를 상수로 바꿨다.
  - 응답 변환을 메서드로 나눴다: `toView`·`toActiveSessionView`·`toDetail`·`toListItems`·`toUsage`·`toCandidates` 등.
  - 유효하지 않은 키, 변경 없음, 좌석 번호 중복 같은 조건을 이름 있는 변수로 바꿨다.
- `SeatSearchRepository`
  - 세 번 쓰던 `" FROM seats s WHERE "`와 집계 열 목록을 상수로 뺐다.
  - WHERE 조립을 `appendWhere`로 바꾸었다. 바인딩 순서와 SQL 조각은 그대로다.
- `SeatOccupancyRepository`: SQL을 상수로 옮겼다.
- `SeatSessionSearchRepository`: 병합 고객 조건을 상수로 옮겼다.
- 엔티티
  - 필드 어노테이션을 한 줄에 하나씩 썼다.
  - 한 줄 대입을 풀었다.
  - `SeatSession`의 "이미 종료된 세션" 검사를 `requireActive`로 모았다.
- enum(`SeatKind`, `SeatState`, `SeatDisplayStatus`, `SeatSession.Status`): 상수·생성자·접근자를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- seat `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석과 DomainLens 어노테이션 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교(`origin/main` 대비)
  - `SeatRepository`·`SeatSessionRepository`·`SeatOccupancyRepository`·`SeatSessionSearchRepository`: 차이 없음.
  - `SeatSearchRepository`: 세 번 쓰던 `" FROM seats s WHERE "`가 상수 하나로 모인 것뿐이다. 실행되는 SQL은 같다.
- 좌석과 이를 쓰는 모듈의 16개 테스트 클래스, 233개 테스트: 모두 통과(실패 0, 건너뜀 0).
  - seat 모듈 전체(좌석, 입퇴실, 좌석 이동, 조회 한도 단위 테스트)
  - home, passhistory 모듈 전체
  - `CustomerMergeIntegrationTests`, `InquiryIntegrationTests`, `AdvancedFilterIntegrationTests`, `LocalPreviewIntegrationTests`
  - 계약 테스트 3개: `SeatUsageContractTests`, `OperationsExtensionContractTests`, `AggregationContractTests`
  - `OpenStudySemanticCatalogTests`(엔티티 정리 뒤 DomainLens 카탈로그가 그대로인지 확인)
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: locker, facility, reservation, task, chat
