# 2026-11-26 home 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음 중 여섯 번째 모듈이다. home 모듈(4개 파일, 약 400줄)은 운영 홈 요약(`GET /home/summary`)을 만든다. 좌석·업무·문의·시설·근무·공지·휴무일 카드를 각 기능의 공개 서비스로 모은다. 다음 문제들이 읽기를 어렵게 했다.

- 한 줄에 여러 변수 선언(`long total = 0, inUse = 0, ...`)과 여러 문장(`total += ...; inUse += ...;`)
- 응답 생성자 안에서 카드 일곱 개를 한꺼번에 만드는 긴 호출
  - 어느 소스를 어떤 순서로 읽는지 보이지 않았다.
- 스트림 안에 목록 조회 호출과 응답 변환이 중첩된 구조
- 한 줄 `if`·`for`, `var`, 130자가 넘는 줄
- 동사로 시작하지 않는 메서드(`summary`, `seatCard`, `flag`, `id`, `link`)

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 서비스의 `summary`는 컨트롤러만 호출하므로(통합 테스트는 HTTP로 호출) `getSummary`로 바꿨다.
- 컨트롤러
  - 메서드 이름은 openapi operationId인 `getHomeSummary`로 바꿨다.
  - 클래스 매핑은 `/api/home`, 메서드는 `/summary`로 두었다. 최종 경로는 같다.
- 카드를 만드는 순서(좌석 → 업무 → 문의 → 시설 → 근무 → 공지 → 휴무일)는 원래 생성자 인자 평가 순서와 같게 지역 변수로 풀었다. 각 소스 서비스를 부르는 순서가 바뀌지 않는다.
- 업무 조건의 기본값 계산은 `mineByDefault`로 이름을 붙였다.
  - 원래 식에서는 담당자를 지정하면 삼항식 부분을 건너뛰었다. 이제는 이 값을 미리 계산하지만, 부작용 없는 값이라 결과는 같다.
- 정렬 기준, 페이지 반복 종료 조건, 동료 근무 범위(활성 소속 지점만), 응답 링크·출처 쿼리의 키 순서는 그대로 둔다.

## 4. 구현 내용

- `HomeSummaryService`
  - 주입 필드 이름을 타입에 맞췄다(`policy`·`seats`·`tasks` 등 → `authorizationService`·`seatService`·`taskService` 등).
  - 카드 생성 메서드를 `to…Card`로, 업무 집계를 `countTasks`로 바꿨다.
  - 스트림 안의 조회를 `findOpenTasks`·`findInquiries`로, 근무 페이지 반복을 `findShiftsOverlapping`으로, 활성 소속 지점 계산을 `findActiveMemberBranches`로 뺐다.
  - 응답 항목 변환을 `toTaskItem`·`toShiftItem`으로 뺐다.
  - 헬퍼 이름: `link`·`source`·`query`·`with`·`flag`·`id` → `toLink`·`toSource`·`toQuery`·`copyWith`·`parseFlag`·`parseEmployeeId`
  - 상태 문자열(`완료`, `접수`, `처리 중`, `예정`)과 근무 페이지 크기 100, 직원 ID 형식을 상수로 바꿨다.
- `HomeSummary.Ratio.of`: 한 줄 `if`와 `var`를 풀고, 100·소수 자릿수를 상수로 바꿨다. 계산 결과는 같다.
- `HomeSummary` 레코드: 구성 요소가 많은 레코드를 한 줄에 하나씩 썼다.
- `HomeController`: 위 매핑과 operationId 정리, 파라미터 줄바꿈.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- home `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- 운영 홈과 집계 계약을 다루는 테스트 32개 모두 통과.
  - `HomeSummaryIntegrationTests` 5개
  - `AggregationContractTests` 21개
  - `OperationsExtensionContractTests` 6개
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- #250의 같은 묶음에서 남은 모듈: workspace, notice, inquiry
