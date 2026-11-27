# 2026-11-27 workforce 모듈 가독성 정리

## 1. 배경 / 문제

#250의 `sales, workforce` 묶음 중 두 번째 모듈이다. workforce 모듈(70개 파일, 약 4,700줄)은 다음을 담당한다.

- 근무 일정 등록·수정·취소, 주간 근무표
- 출퇴근·휴게 기록과 근태 정정
- 휴가 부여·잔여·신청·승인
- 대체근무 신청·승인·승인 취소

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`, 두 줄에 걸친 중괄호 없는 `if`·`for`, 한 줄짜리 메서드 본문, `var`
- 한 줄에 대입 여러 개를 쓴 엔티티 생성자·상태 변경 메서드, 한 줄짜리 enum
- 120자를 넘는 줄(응답 생성 15~20개 인자, 서비스 명령 람다, JPQL·SQL 연결)
- import 그룹 사이에 빈 줄이 없는 파일
- 코드 중간의 완전한 이름(`java.security.NoSuchAlgorithmException`, `java.util.Comparator`, `java.time.Instant` 등)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리 `@Query` 메서드: `locked`, `shared`, `meeting`, `overlapping`, `covering`, `usable`, `available`, `touching`, `forShift` 등
  - 검색 리포지토리: `all`, `attendance`, `grants`, `requests`, `swaps`, `corrections`
  - 서비스 private·패키지 내부: `views`, `frame`, `decide`, `decidable`, `visible`, `sees`, `member`, `replay`, `record`, `values`, `day`, `instant`, `flag` 등
- 주입 필드 이름이 타입과 다름(`records`, `shifts`, `history`, `search`, `readers`, `policy`, `commands`, `keys`, `json` 등)

## 2. 검토한 선택지

- 한 줄 `if`·`for`, 대입 여러 개, 한 줄 메서드, enum, 줄바꿈된 매개변수 목록은 반복되는 기계적 형태라 스크립트로 먼저 풀고, 서비스·검색 리포지토리는 손으로 다시 썼다.
  - 스크립트가 한 줄 record 선언을 메서드 선언으로 잘못 읽어 검색 리포지토리 두 개를 망가뜨린 일이 있었다. 두 파일은 `HEAD`에서 되돌려 손으로 다시 썼고, 스크립트는 record·class 선언을 건너뛰도록 고쳤다.
- 응답 생성 코드가 15~20개 인자를 한 줄에 넣던 곳(`toViews`)은 행 하나를 만드는 `toView`로 나누었다. 생성 지점이 한 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다.
- `WorkforceRequestRefusal`의 정적 생성 메서드(`balance`, `overlap`, `shifts` 등)는 `ApiException.conflict`처럼 오류 이름을 나타내는 생성 메서드라 이름을 그대로 두었다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - `ShiftService.colleagues`·`list`(home 모듈)
  - 서비스 공개 메서드 전부(컨트롤러가 호출)
  - `AttendanceRecordRepository.lockedCurrent`(`AttendanceDuplicateKeyIntegrationTests`가 spy로 바꿔 끼운다)
  - 엔티티·DTO의 공개 이름, `ShiftStatus.label()`
- 리포지토리 이름 변경
  - `@Query` 메서드만 바꿨다. 파생 쿼리 이름(`findByShiftIdIn...` 등)은 그대로다.
  - 잠금 조회는 다른 모듈과 같은 `findLocked`, 그 밖에는 `findShared`, `findMeeting`, `findOverlapping`, `findCovering`, `findUsable`, `findAvailable`, `findAllLocked`, `findTouching`, `findForShift`, `findForAttendance`, `findLockedFor`, `findActiveMember`, `findActiveBranches`, `findColleagueShiftIds`
  - `ShiftSearchRepository`: `all` → `findAll`, `attendance` → `findAttendanceRecords`
  - `LeaveSearchRepository`: `grants`·`requests`·`swaps`·`corrections` → `findGrants`·`findRequests`·`findSwaps`·`findCorrections`
- 서비스 내부 이름 변경(대표)
  - 공통 틀: `frame`·`command`·`change`·`decide` → `runCommand`·`runChange`·`runDecision`
  - 조회: `visible` → `findVisible`, `decidable` → `findDecidable`, `replay` → `findReplay`, `sees` → `canSee`, `member` → `isMember`
  - 변환: `views` → `toViews`(+ 행 하나 `toView`), `values` → `toValues`, `actor` → `toActor`, `reload` → `toReloadUrl`
  - `RecordScope`: `manages`·`sees`·`readable`·`filter` → `canManage`·`canSee`·`findReadable`·`createFilter`
  - `AttendanceViews`: `records`·`record`·`linkedTo` → `toViews`·`toView`·`findLinkedTo`
  - `ShiftInputs`·`LeaveInputs`: `day`·`startOf`·`instant`·`date`·`id`·`flag`·`validKey` 등 → `toDay`·`toStartOf`·`parseInstant`·`parseDate`·`parseId`·`parseFlag`·`validateKey` 등
  - `AttendanceReadings`: `status`·`workedMinutes` → `toStatus`·`calculateWorkedMinutes`
  - `AttendanceConflicts`·`ShiftConflicts`: `overlaps`·`misplaced`·`of` → `findOverlaps`·`findMisplaced`·`toConflicts`
- 컨트롤러
  - 메서드 이름을 openapi operationId 39개로 바꿨다.
  - 근무(`/api/shifts`)는 클래스 매핑을 그대로 두고, 출퇴근·정정·휴가·대체근무는 경로가 `/api` 아래에서 갈라져 클래스 매핑 `/api`를 붙였다.
- 호출 순서는 그대로 둔다.
  - 명령 공통 틀: 가드 → 멱등 키 → 직원 행 잠금(대체근무는 두 직원을 id 순) → 신청 행 → 근무·근태 행
  - 잠금 아래 권한 재확인 → 상태 → 버전 → 명령
  - 단락 평가가 DB 호출을 막는 조건(대체근무 승인의 근무 변경 이력 조회 등)은 한 식 안에 그대로 두었다.
  - 대체근무 `canSee`는 "읽을 수 있는 지점인지"를 먼저 보고 아니면 바로 거짓을 돌려, 관리 권한 확인이 원래처럼 그 뒤에만 일어난다.
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265). DomainLens 어노테이션은 건드리지 않았다.

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다(`RecordScope`는 `recordScope`).
  - 201·24시간·사유 길이·키 길이·`SHA-256`·이동 경로·`"ACTIVE"`·취소 표시 문구 등을 상수로 바꿨다.
  - 긴 람다 본문과 응답 생성을 메서드로 나눴다: 근태 정정의 `createCorrection`·`requireNoOverlap`, 각 서비스의 행별 `toView`(휴가는 `toRequestView`).
  - 대체근무 `findMarkers`의 반복 `json.readValue(..., Map.class)`를 `readMap` 한 곳으로 모았다.
- 검색 리포지토리: 반복되던 엔티티·별칭·정렬 조각과 열린 휴게 조건을 상수로 뺐다. WHERE 조립 순서와 바인딩 이름은 그대로다.
- `@Query`: JPQL·SQL을 text block으로 옮겼다. `LeaveGrantRepository`의 같은 JPQL 두 개는 상수 `USABLE_JPQL` 하나로 모았다(잠금 모드만 다르다).
- 엔티티 10개: 필드 어노테이션을 한 줄에 하나씩, 대입을 한 줄에 하나씩 풀고, 여러 줄에 걸치던 생성자 매개변수를 한 줄에 하나씩 썼다.
- enum 8개(`ShiftStatus`와 엔티티·검색 리포지토리 안의 enum): 상수를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩, 빈 본문은 `{` `}` 두 줄로 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- workforce `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- SQL·JPQL 리터럴 토큰 비교(`origin/main` 대비)
  - 리포지토리 10개: 차이 없음.
  - `LeaveGrantRepository`: 같은 JPQL 두 개가 상수 하나로 모인 만큼만 줄었다.
  - `ShiftSearchRepository`: 두 번씩 쓰던 엔티티·별칭·정렬 조각이 상수 하나씩으로 모인 만큼만 줄었다. 조립된 쿼리는 원래 코드와 나란히 비교해 같음을 확인했다.
- workforce와 이를 쓰는 곳의 26개 테스트 클래스, 203개 테스트: 모두 통과(실패 0, 건너뜀 0).
  - workforce 모듈 전체(출퇴근·중복 키·동시성·근무·근태 정정·휴가·대체근무)
  - home 모듈 전체(오늘 근무 카드가 `ShiftService`를 호출)
  - `LocalPreviewIntegrationTests`, `BranchClosureIntegrationTests`
  - `com.openstudy.architecture` 계약·경계 테스트 전체(`WorkforceContractTests`, `WorkforceRequestsContractTests` 포함)
  - `OpenStudySemanticCatalogTests`
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: ai
- 엔티티 3개(`AttendanceBreak`·`AttendanceCorrection`·`AttendanceRecord`)는 DomainLens import가 jakarta import 앞에 붙어 있어 import 그룹 빈 줄을 맞추지 않았다(DomainLens 제외 방침).
