# 2026-11-26 customerhistory·passhistory 모듈 가독성 정리

## 1. 배경 / 문제

#250의 고객 묶음 중 두 번째 단위다. 두 모듈은 고객 모듈과 다른 업무 모듈의 공개 service를 묶어 읽는 조합 모듈이다.

- customerhistory(8개 파일, 약 240줄): 전 지점 이용 요약(거부 기록 포함), 룸·사물함 이력
- passhistory(6개 파일, 약 200줄): 고객의 구매·사용·조정·환불 내역

다음 문제들이 읽기를 어렵게 했다.

- `var`, 중괄호 없는 `if`·`else`
- 주입 필드 별칭(`customers`, `passes`, `seats`, `policy`, `commands`, `audit`, `json` 등)
- 12개 인자를 한 줄에 이어 쓴 이용권 내역 이벤트 생성
- 한 메서드 안에 섞인 이용권별 이벤트 조립과 정렬
- 120자를 넘는 레코드 선언과 람다

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 모듈과 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 세 service의 공개 메서드는 다른 모듈과 테스트에서 호출하지 않는 것을 확인하고 동사로 시작하게 바꿨다. 테스트는 HTTP로만 호출한다.
  - `CrossBranchSummaryService.summary` → `getSummary`
  - `CustomerSpaceHistoryService.history` → `getHistory`
  - `PassHistoryService.history` → `getHistory`
- `CrossBranchDenialTrail.record`와 `CrossBranchSummaryService.ENTITY`는 이미 의미가 드러나 그대로 두었다.
- 컨트롤러 메서드는 openapi operationId에 맞췄다(`getCrossBranchSummary`, `getCustomerSpaceHistory`, `getCustomerPassHistory`).
- 조회 순서와 응답은 그대로 둔다.
  - 전 지점 요약: 권한 확인 → 고객 존재 확인 → 기록 → 이용권·이용 조회 순서가 같다. 거부 시 별도 트랜잭션 기록도 같다.
  - 이용권 내역: 이벤트를 쌓는 순서(구매 → 조정 → 이용 → 환불)와 정렬 기준이 같다. 표시할 수 있는 처리자만 이름을 조회하는 규칙도 같다.
  - 룸·사물함 이력: 예약 → 사물함 조회 순서를 지역 변수로 풀면서 유지했다.

## 4. 구현 내용

- 컨트롤러
  - 클래스 경로를 `/api`로 두고 메서드 경로에 나머지를 썼다. 최종 URL은 같다.
  - 파라미터를 한 줄에 하나씩 썼다.
- `CrossBranchSummaryService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 권한 판단을 `isPermitted`, 이용 목록 조합을 `findUsages`·`toUsage`로 이름을 바꾸고, 사유 길이 500을 상수로 뺐다.
  - 기록 분기를 중괄호 있는 `if`/`else`로 풀었다.
- `CrossBranchDenialTrail`: 고객 ID 유효성을 `validCustomerId`로 이름 붙이고 감사 이벤트 인자를 한 줄에 하나씩 썼다.
- `PassHistoryService`
  - 이용권 하나의 이벤트 조립을 `addEvents`로 뺐다.
  - 이벤트 생성 메서드 이름을 `toPurchase`·`toRefund`·`toAdjustment`·`toStay`·`toForeignStay`·`toCheck`로 바꾸고 인자를 한 줄에 하나씩 썼다.
  - 이용 이벤트의 차감값 계산을 `toDeltaMinutes`·`toDeltaKrw`로 뺐다.
  - 처리자 ID 수집을 `findActorIds`로 이름을 바꿨다.
- DTO 레코드: 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- customerhistory·passhistory `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- `./gradlew test --tests "com.openstudy.modules.passhistory.*" --tests "com.openstudy.modules.customerhistory.*"`: 80개 통과, 실패 0.
- `./gradlew test -PtestForks=2`: 1,270개 중 실패 0, 건너뜀 52 (BUILD SUCCESSFUL, 약 44분).
  - 별도 git worktree에서 실행했다. 커밋하지 않는 로컬 임베딩 모델(`.models/koen-e5-tiny-q8`)이 worktree에 없어 `DOMAINLENS_EMBEDDING_DIRECTORY`로 원래 체크아웃의 모델을 지정했다.
  - 기준(건너뜀 28)보다 24개 많은 건너뜀은 작업 디렉터리의 `.models` 경로를 직접 확인하는 AI 조건부 테스트다. 이 모듈과 관계없는 환경 차이이며, 이번 실행에서는 확인하지 못했다.

## 6. 남은 작업 / 보류 사항

- customerhistory 모듈에는 전용 테스트 패키지가 없다. 전 지점 요약과 룸·사물함 이력은 passhistory 패키지의 `CustomerHistoryIntegrationTests`가 HTTP로 검증한다.
- #250의 같은 묶음에서 남은 단위: audit·retention, home·workspace, notice, inquiry
