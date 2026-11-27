# 2026-11-27 facility 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 다섯 번째 모듈이다. facility 모듈(24개 파일, 약 1,200줄)은 다음을 담당한다.

- 시설 유형 코드표
- 지점 시설의 등록·수정·요약
- 점검 완료와 다음 점검 작업 열기

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성자·변경 메서드
- 한 줄짜리 enum 생성자·접근자
- 요약 합계를 `long[6]` 배열의 칸 번호로 더하는 코드
- 120자를 넘는 줄(응답 생성 16·18개 인자, SQL 조각, 감사 기록)
- 코드 중간의 완전한 이름(`java.security.NoSuchAlgorithmException`)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `locked`, `openOf`, `openInspections`, `lastFinishedInspection`, `counts`
  - 헬퍼: `FacilityInputs.status`·`days`·`id` 등
  - 서비스 private: `visible`, `detail`, `items`, `lookup`, `type`, `actor` 등

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다. 응답 레코드는 모두 생성 지점이 한 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 이름은 그대로 둔다.
  - 서비스 public 메서드 전부(`FacilityService.summary`·`list`는 home이 쓴다)
  - `FacilityFilters.parse`, DTO
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `FacilityRepository.locked` → `findLocked`
  - `FacilityWorkRepository`
    - `openOf` → `findOpenOf`, `lockedOpenOf` → `findLockedOpenOf`
    - `openInspections` → `findOpenInspections`, `lockedOpenInspections` → `findLockedOpenInspections`
    - `lastFinishedInspection` → `findLastFinishedInspection`
  - 바뀐 리포지토리 메서드는 `@Query`가 있거나 default 메서드라 Spring Data의 이름 해석과 무관하다.
  - `FacilitySearchRepository.counts` → `countByBranch`
  - `FacilityInputs`: `parseStatus`, `parseWorkStatus`, `parseDays`, `parseOptionalText`, `parseOptionalId`, `parseOptionalDate`, `parseDate`, `parseId`, `parseFlag`
  - `FacilityTypeService.view` → `toView`
  - `FacilityService`
    - `nextInspectionOn` → `calculateNextInspectionOn`, `inspectionDue` → `isInspectionDue`
    - private: `findVisibleFacility`, `findOrOpenWork`, `toDetail`, `toListItems`, `findLookup` 등
- 컨트롤러
  - 메서드 이름은 openapi operationId 8개로 바꿨다.
  - `/facility-types`와 `/facilities`가 서로 다른 최상위 경로라 클래스 매핑 `/api`를 그대로 둔다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증, 단락 평가
  - 수정: 잠금 → 버전 → 열린 작업 조회(없으면 생성) → 비교 → 유형 확인 → 담당자 확인 → 변경 → 저장
  - 점검 완료: 잠금 → 열린 작업 → 권한 → 멱등 → 버전 → 날짜 검증 → 완료 → 다음 작업
- 생성과 점검 완료에 똑같이 있던 멱등 재실행 블록은 `findStoredResult`로 모았다. 키 잠금과 시각 읽기 순서는 그대로다.
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265).

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다.
  - 요약 합계의 `long[6]`을 이름 있는 변수 여섯 개로 바꿨다.
  - 201·200·24시간·길이 제한·`SHA-256`을 상수로 바꿨다.
  - 목록 항목·상세·마지막 점검·열린 작업 변환을 메서드로 나눴다.
  - 정렬 방향 기본값 계산을 컨트롤러의 `toDirection`으로 뺐다.
- `FacilitySearchRepository`
  - 집계 열 목록을 상수로 뺐다.
  - WHERE 조립을 `appendWhere`로 바꾸었다.
  - SQL 문자열과 바인딩 순서는 그대로다.
- `FacilityWorkRepository`: JPQL을 text block으로 옮겼다. 열린 상태 목록은 private 메서드 하나로 모았다.
- 엔티티: 필드 어노테이션과 한 줄 대입을 풀었다.
- enum 3개: 상수·생성자·접근자와 검색 반복문을 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- facility `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교(`origin/main` 대비): `FacilitySearchRepository`, `FacilityWorkRepository`, `FacilityRepository` 모두 차이 없음.
- 시설과 홈 시설 카드를 다루는 테스트 79개: 모두 통과(실패 0, 건너뜀 0).
  - `FacilityIntegrationTests` 30개
  - `HomeSummaryIntegrationTests` 5개
  - `CollaborationContractTests` 13개, `AggregationContractTests` 21개
  - `LocalPreviewIntegrationTests` 7개
  - `OpenStudySemanticCatalogTests` 3개
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: reservation, task, chat
