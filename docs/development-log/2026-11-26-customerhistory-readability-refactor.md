# 2026-11-26 customerhistory 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음 중 두 번째 모듈이다. customerhistory 모듈(8개 파일, 약 240줄)은 두 가지를 담당한다.

- 고객의 전 지점 이용 요약(cross-branch summary)과 거부 기록
- 고객 상세의 룸·사물함 이력

작은 모듈이지만 다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`else`, `var`
- 130자가 넘는 감사 기록 호출과 이용 내역 병합 스트림
- 이름이 동사로 시작하지 않는 서비스 메서드(`summary`, `history`)

작은 모듈도 묶지 않고 모듈 하나씩 PR을 만든다는 방침에 따라 단독으로 진행했다.

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 서비스 메서드는 다른 모듈과 테스트에서 직접 부르지 않는다(컨트롤러만 호출). 그래서 동사로 시작하게 바꿨다.
  - `CrossBranchSummaryService.summary` → `summarize`
  - `CustomerSpaceHistoryService.history` → `findHistory`
- `CrossBranchDenialTrail.record`는 원래 동사라 그대로 둔다.
- 컨트롤러 메서드는 openapi operationId에 맞췄다: `getCrossBranchSummary`, `getCustomerSpaceHistory`.
- 권한 판단 → 고객 존재 확인 → 기록 → 응답 순서와 거부 시 별도 트랜잭션 기록은 그대로 둔다.
  - 거부 기록의 고객·지점 ID 검사 순서도 원래와 같다.

## 4. 구현 내용

- `CrossBranchSummaryService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 요약 기록의 `if`/`else`를 조기 반환으로 바꿨다.
  - 감사 기록 호출과 이용 내역 병합 스트림을 줄바꿈했다.
  - 상수 `ENTITY`를 `AUDIT_ENTITY_TYPE`으로, 사유 길이 500을 상수로 바꿨다.
- `CrossBranchDenialTrail`
  - 고객 ID 유효 여부를 `validCustomerId`로 이름 붙였다.
  - 거부 감사 기록 인자를 한 줄에 하나씩 썼다.
- `CustomerSpaceHistoryService`: `var`를 실제 타입으로 바꾸고 응답 조립을 줄바꿈했다.
- `CustomerHistoryController`: 클래스 경로를 `/api`로 두고 메서드에 `/customers/{id}/...`를 붙였다. 최종 경로는 같다.
- DTO 레코드 세 개: 구성 요소를 줄바꿈했다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- customerhistory `src/main`에 `var`, 중괄호 없는 `if`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- 이 모듈을 다루는 테스트(별도 테스트 패키지가 없어 다른 위치에 있음) 실행: 58개 모두 통과.
  - `passhistory.CustomerHistoryIntegrationTests` 19개
  - `inquiry.InquiryIntegrationTests` 26개
  - `architecture.SpaceOperationsContractTests` 13개
- `./gradlew test`: 1,270개 중 실패 0, 건너뜀 28 (BUILD SUCCESSFUL, 약 19분). 건너뛴 28개는 ai·sales 패키지의 AI 관련 조건부 테스트다.

## 6. 남은 작업 / 보류 사항

- #250의 같은 묶음에서 남은 모듈: passhistory, audit, retention, home, workspace, notice, inquiry
