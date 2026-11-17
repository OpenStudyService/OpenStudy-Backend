# 5. 정합성과 동시성: 데이터가 깨지지 않게 막은 장치

스터디카페 운영에서는 같은 좌석·같은 결제·같은 관리자 권한에 여러 요청이 동시에 들어온다. 이 문서는 "애플리케이션 검사만 믿지 않고 DB가 마지막으로 막는다"는 원칙을 어떤 장치로 구현했는지 정리한다.

## 한눈에 보기

| 위험 | 막는 장치 | 위치 |
|---|---|---|
| 잘못된 값 저장 | CHECK 87개 | 거의 모든 업무 테이블 |
| 다른 지점·다른 고객 데이터 연결 | 복합 FK 15개 | 이용권, 스터디룸 예약, 근무, 업무 등 |
| 상태 중복 (진행 중 이용 2건 등) | 생성 컬럼 + UNIQUE | [3. D2](03-key-design-decisions.md#d2-활성-상태는-하나만을-생성-컬럼--unique로-강제한다) |
| 이중 예약 | 시간 슬롯 UNIQUE | [3. D3](03-key-design-decisions.md#d3-스터디룸-이중-예약을-시간-슬롯-행으로-막는다) |
| 같은 요청 재전송 (결제 두 번) | 멱등성 키 | `idempotency_records`, 테이블별 `request_key` UNIQUE |
| 동시 수정으로 덮어쓰기 | 낙관적 잠금 `version` | 엔티티 29개 `@Version` |
| 마지막 관리자 제거 | 단일 행 가드 + 비관적 잠금 | `administration_guard` |
| 교착 상태 | 전역 잠금 순서 | 권한·초대·상품 변경 |
| 집계 중 데이터 변경 | `REPEATABLE READ` 읽기 전용 트랜잭션 | 매출·이용권·좌석 점유 집계 |

## 1. CHECK 제약: 잘못된 값은 저장되지 않는다

CHECK 87개가 업무 규칙을 DB 수준에서 강제한다. 직접 SQL로 넣어도 거부된다.

```sql
-- 상품 유형별로 필요한 값만 있어야 한다 (시간권은 분, 기간권은 일수, 정액권은 충전액과 시간당 요금)
CHECK (
  (kind='TIME' AND included_minutes > 0 AND included_minutes IS NOT NULL
   AND period_days IS NULL AND credit_krw IS NULL AND hourly_rate_krw IS NULL)
  OR (kind='PERIOD' AND ...)
  OR (kind='STORED_VALUE' AND ...)
)

-- 스터디룸 금액은 구매 당시 시간당 요금 × 시간
CHECK (amount_krw = rate_per_hour_snapshot_krw * hours)

-- 보존 규칙은 법정 최소 기간보다 짧을 수 없다
CHECK (period_days IS NULL OR legal_minimum_days IS NULL OR period_days >= legal_minimum_days)
```

**배운 점.** SQL의 CHECK는 결과가 `UNKNOWN`(NULL 비교)이면 통과시킨다. 그래서 필수 값에는 `IS NOT NULL`을 명시했다. 초기 설계 검토에서 영업시간 CHECK가 NULL일 때 통과하는 문제를 이 규칙으로 찾아 고쳤다.

## 2. 복합 FK: 다른 지점의 데이터는 연결할 수 없다

단일 FK는 "그 결제가 존재한다"만 보장한다. 복합 FK는 "그 결제가 **같은 고객·같은 지점**의 결제다"까지 보장한다.

```sql
-- 이용권은 같은 고객·지점·상품의 결제에서만 발급된다
FOREIGN KEY (purchase_payment_id, customer_id, purchase_branch_id, product_id)
  REFERENCES payments (id, customer_id, branch_id, product_id)
```

부모 쪽에는 `UNIQUE (id, customer_id, branch_id, product_id)` 같은 보조 유일 키를 두어 복합 FK의 대상이 되게 했다. 애플리케이션 버그로 다른 지점의 결제 ID가 들어와도 DB가 거부한다.

## 3. 멱등성 키: 같은 요청은 한 번만 처리한다

네트워크가 끊겨 클라이언트가 결제 요청을 다시 보내도 결제는 하나만 생겨야 한다.

- 클라이언트가 요청마다 `Idempotency-Key`를 보낸다.
- 서버는 `UNIQUE (actor_employee_id, operation, scope_key, idempotency_key)`에 기록하고, 요청 본문의 해시를 함께 저장한다.
- 같은 키 + 같은 본문이면 저장된 응답을 그대로 돌려준다. 같은 키 + 다른 본문이면 409로 거절한다.
- 업무 변경과 멱등 결과는 같은 트랜잭션에서 커밋한다. 업무가 롤백되면 멱등 기록도 사라진다.
- 처리 중에 같은 키가 또 오면 UNIQUE 잠금에서 기다렸다가 완료된 결과를 읽거나 `409 REQUEST_IN_PROGRESS`를 돌려준다.

결제·예약·좌석 입실·메시지처럼 자주 쓰는 명령은 테이블 자체에 `request_key` UNIQUE를 두어 같은 효과를 낸다(`uk_seat_sessions_3`, `uk_payments_1` 등).

## 4. 낙관적 잠금: 동시 수정은 한쪽만 성공한다

- 수정 가능한 업무 테이블은 `version` 컬럼을 갖는다. 업데이트는 `WHERE id = ? AND version = ?`로 하고 `version`을 1 올린다.
- 화면에서 version을 받아 오므로, 두 관리자가 같은 화면에서 동시에 저장하면 늦은 쪽이 `409`를 받는다. 조용히 덮어쓰지 않는다.
- JPA 전환 때 `@Version`은 화면에 version을 노출해 충돌을 거절하는 엔티티(29개)에만 적용했다. 나머지 테이블은 증가만 유지했다.

## 5. 관리자 0명 방지: 단일 행 가드

**문제.** 관리자 A와 B가 동시에 서로의 권한을 회수하면, 각 트랜잭션은 "다른 관리자가 남아 있다"고 판단해 둘 다 성공하고 관리자가 0명이 된다.

**장치.**

- `administration_guard`는 행이 정확히 하나인 테이블이다(`CHECK (id = 1)`).
- OWNER 생성·상태 변경, 관리 역할·권한 회수는 모두 이 행을 `SELECT ... FOR UPDATE`로 먼저 잠근다.
- 그래서 관리자 수에 영향을 주는 명령은 한 번에 하나씩 실행되고, 두 번째 트랜잭션은 첫 번째 결과를 보고 판단한다.
- 마지막 관리자를 없애려는 요청은 `409 LAST_ADMIN_REQUIRED`로 거절한다.

## 6. 전역 잠금 순서: 교착 상태를 만들지 않는다

여러 행을 잠그는 명령이 서로 다른 순서로 잠그면 교착 상태가 생긴다. 모든 명령이 같은 순서를 따른다.

```text
관리자 가드(필요할 때)
  → 직원 (ID 오름차순)
  → 지점 (ID 오름차순)
  → 초대·상품 등 업무 부모
  → 소속·권한 등 자식
```

- 지점·상품 잠금 뒤에 직원을 잠그는 역순을 만들지 않는다.
- 메일 발송 같은 외부 호출은 DB 트랜잭션 밖에서 한다.
- 교착 상태가 나면 트랜잭션 전체를 제한된 횟수만큼 재시도하고, 재시도 때 권한·상태·version을 다시 검사한다.

## 7. 집계 일관성: 한 화면의 숫자는 같은 시점의 숫자

매출 화면은 승인 합계와 환불 합계를 서로 다른 SQL로 읽는다. 그 사이에 환불이 들어오면 두 숫자가 다른 시점을 보게 된다. 매출·이용권·좌석 점유 집계 서비스는 `REPEATABLE READ` 읽기 전용 트랜잭션으로 묶어 같은 스냅샷에서 읽는다.

## 검증: 동시성 통합 테스트

위 장치는 실제 MySQL(Testcontainers)에 두 개 이상의 연결로 동시에 요청을 보내 확인한다. 주요 테스트:

| 시나리오 | 테스트 |
|---|---|
| OWNER 동시 생성 → 한 명만 | `OwnerBootstrapTests.concurrentCommandsCreateOnlyOneOwnerWithoutOverwritingWinner` |
| 마지막 OWNER 동시 제거 → 둘 다 롤백 | `AuthorizationIntegrationTests.concurrentRemovalOfLastOwnerIsRolledBackOnBothConnections` |
| 관리자 둘이 서로 회수 → 동시 성공 불가 | `AuthorizationIntegrationTests.twoManagersCannotConcurrentlyRevokeEachOtherEvenWithoutAnOwner` |
| 같은 키 결제 동시 요청 → 결제 1건 | `PaymentIntegrationTests.concurrentSameKeyRequestsCreateExactlyOnePayment` |
| 같은 결제 동시 환불 → 1회 | `PaymentIntegrationTests.sameRefundKeyReplaysAndConcurrentKeysRefundOnce` |
| 겹치는 사물함 배정 동시 요청 → 하나만 | `LockerAssignmentIntegrationTests.onlyOneOfManySimultaneousOverlappingAssignmentsWins` |
| 스터디룸 동시 취소 → 환불 1회 | `RoomCancelUsageIntegrationTests.simultaneousCancellationsWithDifferentKeysRefundOnce` |
| 같은 version 동시 저장 → 승자 1 | `CustomerIntegrationTests.concurrentSameVersionWritesYieldExactlyOneWinner` |
| 초대 링크 동시 수락 → 1회 소비 | `InvitationAcceptanceIntegrationTests.concurrentAcceptancesConsumeOneLinkAndApprovalRacesRejectionOnce` |
| 고객 동시 병합 → 거절 | `CustomerMergeIntegrationTests.selfRepeatedAndSimultaneousMergesAreRefused` |

2026-11-17 main 기준 전체 테스트(1,270개)에서 2개가 실패하고 28개가 건너뛰어졌다. 실패 2개(마이그레이션 버전 기대값 누락, 병렬 실행 중 일시적 자원 읽기 실패)는 같은 날 고치거나 단독 재실행으로 통과를 확인했다. 수정 후 전체 테스트는 다시 실행하지 않았다([기록](../development-log/2026-11-17-migration-version-assertion.md)).

## 근거 자료

- [기반 DB 설계: 트랜잭션과 잠금 순서](../design/foundation/database.md#트랜잭션과-잠금-순서)
- [전체 구축 SQL](../db/schema-full.sql): CHECK·UNIQUE·FK 원문
- 부분 인덱스 대신 생성 컬럼을 쓰기로 한 배경: 상위 작업 공간의 개발 기록 `2026-10-18-backend-mysql-correction.md` (이 저장소 밖)
