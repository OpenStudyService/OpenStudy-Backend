# 2026-11-03 M6 스터디룸·사물함·타 지점 요약 API 계약

관련: Issue [#67](https://github.com/OpenStudyService/OpenStudy-Backend/issues/67), [계약 문서](../design/foundation/space-operations-api.md), [ADR-M6-001](../db/ADR-M6-001.md), [M4 계약](../design/foundation/customer-sales-api.md).

## 1. 배경 / 문제

M6 정책은 ADR-M6-001로 확정됐지만 API 계약이 없었다. 스키마(#68), 룸·예약(#69), 취소·이용(#70), 사물함(#71), 타 지점 요약(#72)과 Frontend가 같은 기준으로 병행하려면 operation, 오류, 권한, 요청 키, 요금 계산, 트랜잭션 경계를 먼저 고정해야 했다. M4·M5·M7과 같은 방식이다. 이슈 범위는 스키마 변경과 구현을 제외한다.

## 2. 검토한 선택지

- **결제 실패의 기록**
  - 실패하면 예약·배정 행을 남기지 않는다: 실패 결제가 무엇을 위한 것이었는지 끊긴다.
  - `결제 실패` 상태 행을 남기고 슬롯·기간은 점유하지 않는다: V1 상태값과 `payments`의 M4 동작(실패도 201, 멱등 저장)에 맞는다. 이쪽을 택했다.
- **이용 인원**: FR-ROOM-02는 정원 검증을 요구하지만 저장 열이 V1과 ADR의 #68 목록에 없다.
  - 열을 추가한다: #68의 범위가 넓어지고 인원을 쓰는 요구사항이 없다.
  - 검증에만 쓰고 저장하지 않는다: 이쪽을 택했다. 리뷰에서 바꿀 수 있다.
- **이용 시작의 시각 제한**: 이슈 #70은 운영 중단·상태·다른 이용 중 예약만 거절 사유로 적었다.
  - 예약 구간 안에서만 시작하게 한다: 일찍 온 고객을 처리할 수 없다.
  - 시각으로는 거절하지 않는다: 이쪽을 택했다. 같은 룸의 다른 이용 중 예약 검사로 겹침을 막는다.
- **미반납 초과 배정**: 기간이 끝났지만 반납되지 않으면 날짜로는 겹치지 않아도 실제로 물건이 들어 있다.
  - 날짜 겹침만 본다: 물건이 있는 사물함을 다른 고객에게 배정하게 된다.
  - `사용 중`으로 세고 `overdue`로 표시하며 신규 배정을 409 `LOCKER_NOT_RETURNED`로 막는다: 이쪽을 택했다.
- **금액 노출**: 지점 직원 누구나 예약을 보지만 기본 프리셋의 STAFF에게는 결제 권한이 없다.
  - 금액을 항상 준다: 결제 정보가 `payments:read` 없이 나간다.
  - `payments:read`가 있을 때만 준다: 처음 쓴 안이다. `payments:write`만 가진 직원이 연장 결제 실패를 응답에서 확인할 수 없는 문제가 있어 `payments:read` 또는 `payments:write`로 고쳤다.
- **타 지점 요약의 요청 방식**: 응대 사유를 URL에 남기지 않고 조회 기록을 남기는 동작이라 `POST` 본문으로 받는다. M5 정확 일치 조회와 같다.

## 3. 결정 및 이유

- operation 26개를 정의했다. 룸 5, 예약·이용 7, 사물함 7, 배정 5, 고객 2다.
- 입력 오류는 422의 구체 코드, 다른 예약·배정과의 충돌은 409로 나눴다. 화면이 입력 수정과 다른 시간 선택을 구분해 안내할 수 있다.
- 사물함 기간은 `[startDate, endDate)`로 정했다. DB의 `end_date - start_date = 구간 × 28`을 그대로 만족하고 다음 배정이 `endDate` 당일에 이어진다.
- 예약 길이 상한은 24시간으로 정했다. ADR에 상한이 없지만 슬롯 행이 무제한으로 생기는 것을 막아야 한다.
- 사물함은 기간 겹침을 막는 DB 제약이 없으므로 사물함 행 잠금으로 보장한다고 명시했다. 스터디룸은 슬롯 유일 제약이 있다.
- `Payment`를 확장했다. `purpose`에 두 값을 더하고 `productId`·`productName`을 nullable로 바꾸고 예약·배정 참조를 더했다. `POST /payments/{id}/refund`는 이용권 결제만 받는다고 적었다.

## 4. 구현 내용

- `docs/design/foundation/space-operations-api.md` 신규
- `docs/design/foundation/openapi.json`: 버전 `0.1.8-m6-space-operations`, operation 26개, 태그 4개, `Payment` 확장, `PermissionCode`에 `customers:cross-branch-read`, `WorkspaceEvent.resource` 4개 값
- `src/test/java/com/openstudy/architecture/SpaceOperationsContractTests.java` 신규, 계약 테스트 9개
- `docs/design/foundation/README.md` 안내 추가

서버 코드와 마이그레이션은 바꾸지 않았다.

## 5. 검증 결과

- `swagger-cli validate docs/design/foundation/openapi.json`: 통과
- 스크립트 대조: 계약 문서 표와 OpenAPI 신규 operation이 26개로 일치
- 기존 operation의 변경은 `POST /payments/{id}/refund`의 설명뿐이다. 기존 schema 변경은 `Payment`, `PermissionCode`, `WorkspaceEvent`다.
- `./gradlew test --tests 'com.openstudy.architecture.*'`: 통과, 신규 계약 테스트 포함

**실행하지 않은 것**

- 전체 테스트는 실행하지 않았다. 이번 변경은 문서·OpenAPI·아키텍처 테스트뿐이다.
- 계산 예시는 손으로 계산했다. #69·#71 구현 테스트에서 같은 값으로 확인해야 한다.
- Frontend 측 계약 검토는 하지 않았다.

## 6. 남은 작업 / 보류 사항

- #68: 시간당 요금 열, 스냅샷·시간 수 열, 정시 CHECK, 새 권한. 버전 번호는 병합 직전에 정한다.
- #69·#71: `Payment` 응답에 새 필드를 채우고, 환불 API가 다른 목적의 결제를 거절하도록 한다.
- 운영 중단 룸의 기존 예약은 자동 처리하지 않는다. 관리자가 취소한다.
- `customers:cross-branch-read`의 이름은 ADR에서 리뷰로 조정할 수 있다.
