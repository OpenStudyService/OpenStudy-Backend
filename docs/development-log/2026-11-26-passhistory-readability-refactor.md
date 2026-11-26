# 2026-11-26 passhistory 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음 중 세 번째 모듈이다. passhistory 모듈(6개 파일, 약 200줄)은 고객의 이용권 구매·이용·조정·환불 내역과 원장 검산을 조합해 보여 준다. 다음 문제들이 읽기를 어렵게 했다.

- 한 메서드(`history`) 안에서 조회, 이벤트 조립, 정렬이 모두 일어난다.
- 한 줄 `if`/`else`, `var`
- 12개 인자를 두세 줄에 몰아 쓴 이벤트 생성
- 이름이 동사로 시작하지 않는 메서드(`history`, `purchase`, `stay`, `check`)

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 서비스 메서드는 다른 모듈과 테스트에서 직접 부르지 않는다(컨트롤러만 호출). 그래서 동사로 시작하게 바꿨다.
  - `history` → `findHistory`
  - 이벤트 생성: `purchase`·`stay` 등 → `toPurchase`·`toStay` 등
  - 원장 검산 변환: `check` → `toCheck`
- 컨트롤러 메서드는 openapi operationId인 `getCustomerPassHistory`로 바꿨다.
  - 클래스 매핑은 같은 자원의 `CustomerHistoryController`와 맞춰 `/api/customers/{id}`로 두고, 메서드에는 `/pass-history`만 쓴다.
  - 메서드가 하나뿐이라 규칙상 어느 쪽이든 되며, 최종 경로는 같다.
- 이벤트를 모으는 순서는 원래와 같다: 이용권마다 구매 → 조정 → 이용 → 환불.
  - 이후 시각·종류 순 정렬은 안정 정렬이라, 같은 시각·같은 종류 이벤트의 상대 순서도 바뀌지 않는다.
- 범위 밖 지점 이용의 정보 제한(지점·시각·차감량만 노출)은 그대로 둔다.

## 4. 구현 내용

- `PassHistoryService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 이용권 하나의 이벤트 조립을 `toEvents`로 뺐다.
  - 결제 ID·이용권 ID 목록과 원장 검산 목록을 이름 있는 변수로 나눴다.
  - 이벤트 생성 다섯 곳은 처음에 인자를 한 줄에 하나씩 쓰는 데 그쳤다. 리뷰에서 위치 기반 생성자 반복이 적절한지 지적을 받아 Lombok `@Builder`로 바꿨다(아래 "리뷰 반영").
  - `toCheck`(11개 인자)는 모든 칸에 값이 들어가고 호출부가 한 곳뿐이라 생성자를 유지하고 줄바꿈만 했다.
  - 이름 조회 레코드의 `actor`를 `toActor`로, `actors`를 `findActorIds`로 바꿨다.
- `PassHistoryController`: 위 매핑과 operationId 정리, 파라미터 줄바꿈.
- DTO 레코드 세 개: 구성 요소를 한 줄에 하나씩 썼다.

### 리뷰 반영: `PassHistoryEvent` 빌더

- 문제: 12개 구성 요소의 레코드를 다섯 곳에서 위치 순서대로 생성했다.
  - 이벤트마다 쓰지 않는 칸에 `0`·`null`을 채워 넣어야 했다(구매 이벤트는 12칸 중 5칸).
  - 같은 타입(`long` 두 개, `String` 네 개)의 자리가 바뀌어도 컴파일되는 위험이 있었다.
- 검토한 선택지
  - 레코드에 이벤트 종류별 정적 팩터리 추가: 계약 스키마 DTO에 화면 조립 규칙이 들어간다.
  - Lombok `@Builder`: 값을 이름으로 넣고 안 쓰는 칸은 생략할 수 있다. 규칙 문서의 Lombok 방침과 맞아 이것을 택했다.
- 단점: 빌더는 필수 값을 컴파일러가 강제하지 않는다. 이 DTO는 원래 이벤트마다 빈 칸이 다른 구조라 위치 기반 생성자도 사실상 막지 못했고, 응답 내용은 통합 테스트가 확인한다.
- 구현
  - `PassHistoryEvent`에 `@Builder`를 붙였다.
  - 모든 이벤트에 공통인 `passId`·`productName`은 `newEvent(pass)`가 채운다.
  - 범위 안팎 이용이 공유하는 시각·종류·차감량·지점은 `newStayEvent`가 채운다. 범위 안 이용(`toStay`)은 여기에 이용 시간·좌석 세션·사유·처리자를 더한다.
  - 범위 밖 이용(`toForeignStay`)은 공유 부분만 쓴다. 의도적으로 빼는 정보가 코드에 그대로 드러난다.
- 설정하지 않은 필드는 `null`(차감량은 `0`)이 되어, 원래 명시적으로 넣던 값과 같다. 응답은 바뀌지 않는다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- passhistory `src/main`에 `var`, 중괄호 없는 `if`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- passhistory 모듈 테스트와 이 API를 다루는 테스트: 89개 모두 통과.
  - `PassHistoryIntegrationTests` 25개
  - `PassAdjustmentIntegrationTests` 36개
  - `CustomerHistoryIntegrationTests` 19개
  - `SeatUsageContractTests` 5개
  - `CustomerMergeIntegrationTests` 4개
- 빌더 적용 후 같은 테스트 89개를 다시 실행해 모두 통과했다.
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- #250의 같은 묶음에서 남은 모듈: audit, retention, home, workspace, notice, inquiry
