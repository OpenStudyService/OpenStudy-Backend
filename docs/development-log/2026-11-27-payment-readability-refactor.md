# 2026-11-27 payment 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 첫 번째 모듈이다. payment 모듈(17개 파일, 약 970줄)은 다음을 담당한다.

- 이용권 Mock 결제와 전액 환불
- 결제 목록·상세
- 스터디룸·사물함 결제 원장과 스터디룸 환불

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`try`, `var`, 한 줄에 여러 대입을 쓴 엔티티 생성 메서드
- 컨트롤러와 리포지토리 안의 완전한 이름(`java.util.List`, `com.openstudy.common.exception.ApiException`, `org.springframework.data.jpa.repository.Query` 등)
- 130~190자에 이르는 줄
  - 목록 조회의 검색 조건 생성
  - 20개 인자를 한 줄에 몰아 쓴 상세 응답 생성
  - 결제 저장 호출
- 결제 생성과 환불에 똑같이 들어 있는 두 가지 코드
  - 멱등 재실행 블록
  - 결과 저장 블록
- 이용권 환불과 스터디룸 환불에 똑같이 들어 있는 처리 순서(환불 저장 → 마감일 조정 → 결제 상태 변경)
- 이름이 동사로 시작하지 않는 메서드(`views`, `view`, `label`, `method`, `instant`, `storedKey`, `validKey`, `record`, `roomReservations`, `lockerAssignments`)
- `"승인"` 표시 상수 `APPROVED`와 감사 동작 문자열 `"APPROVED"`가 같은 이름이라 헷갈림

## 2. 검토한 선택지

- 엔티티 생성 메서드 이름(`pass`·`room`·`locker`·`full`): 동사로 바꿀지 검토했다. 정적 팩토리 관례에 맞고 호출부(`Payment.pass(...)`)에서 뜻이 분명해 그대로 두었다.
- 상세 응답의 20개 인자 생성: 빌더를 검토했다. 생성 지점이 한 곳뿐인 전체 매핑이라, 빌더 적용 기준(반복되는 생성, null·0 채움 인자)에 맞지 않는다. 생성자를 유지하고 인자를 한 줄에 하나씩 썼다.

## 3. 결정 및 이유

- 다른 모듈이 쓰는 이름은 그대로 둔다.
  - `PaymentService`: `create`, `refund`, `chargeRoom`, `chargeLocker`, `refundRoom`, `statusLabels`, `trails`
  - 응답 DTO
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `PaymentService`의 private 메서드
    - `views`·`view` → `toDetails`·`toRefundView`
    - `label` → `toLabel`, `method` → `toMethod`
    - `instant` → `parseInstant`, `storedKey` → `toStoredKey`
    - `validKey` → `requireValidKey`, `record` → `recordCharge`
  - `PaymentRepository`: `roomReservations`·`lockerAssignments` → `findRoomReservations`·`findLockerAssignments`
  - `PaymentSearchRepository`: `in` → `appendIn`
- 중복된 처리는 메서드로 뺐다.
  - `findStoredResult`: 멱등 재실행
  - `completeRequest`: 결과 저장
  - `refundInFull`: 환불 저장 → 마감일 조정 → 결제 상태 변경
  - `toRefundedState`: 환불 후 감사 상태
- 컨트롤러
  - 메서드 이름은 openapi operationId(`listPayments`, `createPayment`, `getPayment`, `refundPayment`)로 바꿨다.
  - 클래스 매핑 `/api/payments`는 그대로 둔다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증
  - 생성: 입력 → 키 → 범위 → 멱등 → 지점 → 고객 → 상품 → 저장 → 이용권 발급 → 감사 → 결과 저장
  - 환불: 잠금 → 검증 → 이용권 환불 → 환불 저장
  - 상세 변환: 이용권 사용 → 예약·사물함 → 고객 → 환불 → 직원 이름
- 표시 상수는 `APPROVED_LABEL`처럼 이름을 바꾸었다. 감사 동작 문자열은 `APPROVED_OUTCOME`·`FAILED_OUTCOME` 상수로 두었다.
- `AuditEvent.change` 호출은 위치 인자 그대로 둔다(빌더 전환은 #265).
- 엔티티의 DomainLens 어노테이션(`@SemanticField` 등)은 손대지 않았다(#250에서 domainlens 제외).

## 4. 구현 내용

- `PaymentService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 목록 조회의 검색 조건·적용 필터·상태별 합계(`toSummary`)를 이름 있는 변수와 메서드로 나눴다.
  - 상세 변환이 읽는 맵 여섯 개를 private 레코드 `DetailSources`로 묶었다. 결제 한 건 변환은 `toDetail`로 뺐다.
  - 상수로 바꾼 값
    - 201·200
    - 환불 사유 길이, 키 길이 16·128
    - 24시간, `Asia/Seoul`, `SHA-256`
    - 키 접두어 `PAY-`·`RFD-`, 감사 테이블 이름
- `PaymentController`
  - 완전한 이름을 import로 바꿨다.
  - 반복 파라미터 파싱의 이름을 `parseLabels`·`parseIds`로 바꿨다. 표시값 → 저장값 찾기는 `findStoredValue`로 뺐다.
  - 정렬 필드·분류 코드 길이·ID 형식을 상수로 바꿨다.
- `PaymentSearchRepository`
  - WHERE 조립을 `appendWhere`로, 자리표시자 생성을 `placeholders`로, 합계 행 변환을 `toStatusTotal`로 뺐다.
  - 바인딩 순서와 SQL 조각은 그대로다.
- `PaymentRepository`·`RefundRepository`
  - 네이티브 SQL을 text block으로 옮겼다.
  - 완전한 이름의 어노테이션을 import로 바꿨다.
- 엔티티(`Payment`, `Refund`)
  - 필드 어노테이션을 한 줄에 하나씩 썼다.
  - 한 줄 대입을 풀었다.
  - `pass`가 `room`·`locker`와 같은 공통 생성 메서드(`decided`)를 쓰고 이용권 필드만 더 채우게 했다. 채우는 필드와 값은 같다.
- DTO 레코드: 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- payment `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석과 DomainLens 어노테이션 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교: `origin/main`과 차이가 나는 것은 아래 항목뿐이다. 실행되는 SQL 문자열은 같다.
  - 검색: `","`·`"?"`가 `placeholders`로, `" FROM payments x WHERE "`가 상수로 모였다.
  - 서비스: 반복 문자열이 상수와 공용 메서드로 모였다.
  - `RefundRepository`의 INSERT는 줄바꿈 위치만 바뀌었다.
- 결제·환불·스터디룸·사물함·매출·이용권 이력을 다루는 25개 테스트 클래스, 357개 테스트: 실패 0, 건너뜀 2(환경 변수가 있어야 실행되는 `AiLiveParityIntegrationTests`·`SalesQueryPlanMeasurementTests`).
  - payment, passhistory, sales 모듈 테스트 전체
  - `LockerAssignmentIntegrationTests`, `RoomReservationIntegrationTests`, `RoomCancelUsageIntegrationTests`
  - `CustomerMergeIntegrationTests`, `SeatSessionIntegrationTests`, `ProductSaleLimitIntegrationTests`
  - 계약 테스트 4개: `CustomerSalesContractTests`, `OperationsExtensionContractTests`, `SpaceOperationsContractTests`, `AggregationContractTests`
- DTO 레코드 줄바꿈 정리 뒤 다시 실행: `PaymentIntegrationTests` 18개, `CustomerSalesContractTests` 3개, `OperationsExtensionContractTests` 6개 모두 통과.
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- `PaymentService.list`·`get`은 컨트롤러만 쓰지만, 이미 동사라 이름을 그대로 두었다.
- 다음 모듈: ticket, seat, locker, facility, reservation, task, chat
