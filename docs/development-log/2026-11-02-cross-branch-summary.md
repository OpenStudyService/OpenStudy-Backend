# 2026-11-02 타 지점 최소 요약과 고객의 룸·사물함 이력 API

관련: Issue [#72](https://github.com/OpenStudyService/OpenStudy-Backend/issues/72), [계약 문서](../design/foundation/space-operations-api.md), [ADR-M6-001](../db/ADR-M6-001.md) M6-D06, [ADR-M5-001](../db/ADR-M5-001.md) M5-D07, [사물함 기록](2026-11-02-locker-assignment-api.md), [구매·사용 내역 기록](2026-11-02-pass-adjustment-history.md).

## 1. 배경 / 문제

현장 응대 중인 직원이 다른 지점에서 이용하던 고객을 도우려면 그 고객의 이용 상태를 알아야 한다. 그러나 일반 고객 조회 범위(M4-D05)를 넓히면 연락처와 메모까지 열린다. POL-01은 지정된 사용자에게 최소 요약만 보이게 하고, NFR-SEC-09는 그 조회의 성공과 거부를 모두 남기라고 요구한다.

고객 상세에도 룸 예약과 사물함 배정이 아직 연결되지 않았다. 두 원장의 응답과 어긋나지 않으면서 결제 정보는 지점별 권한을 따라야 한다.

## 2. 검토한 선택지

- **조합 코드의 위치**: 고객 모듈에 두면 좌석·이용권·룸·사물함이 이미 고객에 의존하므로 의존 순환이 생겨 `ModuleBoundaryTests`가 막는다. `passhistory`에 넣는 방안과 새 모듈을 비교했다. `passhistory`는 이름과 책임이 이용권 내역이고 룸·사물함 의존이 새로 붙는다. 조회·기록 규칙이 전혀 다른 요약까지 넣을 이유가 없어 새 모듈 `customerhistory`로 분리했다.
- **권한 표현**: `BranchAction`은 "역할 AND 권한"만 표현한다. 계약은 "MANAGER OR 권한"이다. `AuthorizationService`에 전용 판정을 넣는 방안과 행위를 둘로 나눠 OR로 묶는 방안을 비교했다. 후자는 `permits`의 소속 유효성 판정을 그대로 쓴다. 전용 판정은 같은 조건을 다시 써야 해서 중지 소속 처리가 어긋날 수 있다. 기존 `MANAGE_COLLABORATION`도 역할만 보지만 협업 전용 의미라 재사용하지 않았다.
- **거부 감사의 별도 트랜잭션**: 콜백 안에서 `REQUIRES_NEW`로 쓰는 방안은 `AuthorizedCommands`가 금지한다. 바깥 트랜잭션이 쥔 `employees` 행 잠금 때문에 감사 행의 외래 키 확인이 기다리게 될 것으로 본다(실행해 확인하지는 않았다). 그래서 서비스 진입점은 트랜잭션 없이 두고, 명령 트랜잭션이 끝난 뒤 `catch`에서 `REQUIRES_NEW`로 기록한다.
- **요약의 이용 기록 읽기**: 이력 DTO를 재사용하는 방안은 처리자·사유·ID가 함께 오므로, 각 모듈이 장소와 시간만 담은 DTO를 돌려주게 했다. 잘못 넣을 필드가 애초에 없다.

## 3. 결정 및 이유

- **권한을 고객 존재 여부보다 먼저 판정한다.** 무권한 직원은 있는 고객이든 없는 고객이든 같은 403을 받고, 없는 고객 ID도 거부 감사에 남는다. 404를 먼저 내면 무권한 직원이 고객 ID의 존재를 알아낼 수 있다. 권한이 있는 직원의 404는 숨길 것이 없다. 어차피 그 직원은 어떤 고객이든 요약을 볼 수 있다.
- **응답은 계약 스키마와 같은 record로만 만든다.** `CrossBranchSummary`·`CrossBranchPass`·`CrossBranchUsage`가 응답의 전부이고, 시험이 필드 집합을 고정한다.
- **이용권 조회는 구매 지점 범위를 일부러 넘는다.** `PassService.heldBy`는 `usableBy`와 같이 최소 DTO만 돌려준다. 상태는 기존 `status()`를 그대로 써서 다른 이용권 응답과 어긋나지 않는다.
- **공간 이력은 기존 `views`를 그대로 탄다.** 같은 매핑이므로 원장 상세와 같은 JSON이고, 결제 값도 행의 지점별로 판단된다.
- **성공 기록의 분기는 정확 일치 조회와 같다.** `state.memberships()`에 응대 지점 행이 있으면 `customer_service_interactions`, 없으면 `audit_events`(`CROSS_BRANCH_SUMMARY`)다. 소속 행이 있는 OWNER는 행의 상태와 관계없이 외래 키가 맞으므로 표에 남는다.
- **마이그레이션은 없다.** 권한 정의는 #68에 있고 V1 표로 충분하다.

계약이 정하지 않아 판단한 점:

- **사물함의 "이용"**: 표에 시작 시각이 없어 시작일의 Asia/Seoul 자정을 `startAt`으로 쓴다. `endAt`은 반납 시각이고, 반납 전에는 null이다. 이용 기간이 지났어도 물건이 들어 있으므로(`LockerOccupation`의 정의) 끝난 것으로 보지 않는다. 예정 배정, 결제 실패, 취소, 시작일 전에 반납한 배정은 이용이 아니므로 뺀다.
- **룸의 "이용"**: 예약이 아니라 `room_usages`만 센다. 시각은 처리 시각이다. 이용하지 않은 예약은 나오지 않는다.
- **좌석의 시각**: 좌석 이용 응답과 같은 `checkedInAt`·`checkedOutAt`이다. 정산용 유효 시각은 쓰지 않는다.
- **정렬**: 시작 시각 내림차순이다. 같은 시각이면 좌석·스터디룸·사물함 순이다. 이용권은 ID 내림차순이다.
- **`NOT_STARTED` 이용권**: 계약 상태 4개에 없고 새로 발급되지 않으므로 요약에서 뺀다.
- **형식이 틀린 요청**: 권한 판정 전에 422다. 사유 누락 등은 조회 시도가 아니라 입력 오류로 보아 거부 감사를 남기지 않는다. 형식 검사는 고객 존재 여부를 드러내지 않는다.
- **없는 고객의 404**: 성공도 거부도 아니므로 기록하지 않는다. 표에는 외래 키 때문에 남길 수도 없다.
- **운영 중지된 응대 지점**: 계약에 제한이 없고 요약은 읽기이므로 허용한다.
- **열에 담을 수 없는 ID**: `audit_events.branch_id`는 외래 키이고 `entity_id`는 부호 없는 수다. 없는 지점이나 음수 고객 ID로 거부되면 해당 열을 비우고 `after_json`에 값을 남긴다. 그러지 않으면 403이 500이 된다. 이 내용을 위해 `AuthorizationService.branchExists`를 더했다.
- **OWNER 감사 행의 사유**: 계약이 `audit_events`의 형식을 정하지 않아, 인수 조건의 "사유 기록"을 위해 `reason` 열에 원문을 남긴다.

## 4. 구현 내용

- `modules/customerhistory`(신규): `CustomerHistoryController`, `CrossBranchSummaryService`, `CrossBranchDenialTrail`, `CustomerSpaceHistoryService`, 응답 DTO
- `BranchAction.SERVE_CROSS_BRANCH_AS_MANAGER`·`SERVE_CROSS_BRANCH`, `AuthorizationService.branchExists`, `AuditEvent.denied`
- `PassService.heldBy`, `SeatSessionService.recentUsages`, `RoomReservationService.recentUsages`·`ofCustomer`, `LockerAssignmentService.recentUsages`·`ofCustomer`와 저장소 조회
- OpenAPI 2개 operation을 `implemented`로 변경하고 `info.description`과 `space-operations-api.md`의 구현 상태 문장을 갱신. 이로써 이 계약 문서의 operation은 모두 구현됐다. 그 밖의 계약 변경은 없다.

## 5. 검증 결과

- `./gradlew cleanTest test` 전체(35분 25초): 65개 suite, 818개 테스트, 실패·오류·건너뜀 0. Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 전체 실행은 코드 기준 1회로 첫 실행에서 통과했고, 그 뒤에는 이 기록만 바꿨다.
- 전체 실행 전 부분 실행에서 실패한 것은 시험 코드의 실수뿐이었다(시계를 하루 옮겨 세션이 만료됨, 조회 응답 필드 이름, `ck_passes_period_activation`에 맞지 않는 `NOT_STARTED` 행, 공통 필터가 내는 결제 상세 403을 404로 기대). 구현은 바꾸지 않았다.
- 신규 `CustomerHistoryIntegrationTests` 19개를 HTTP와 DB로 확인했다. 공통 픽스처를 쓰려고 `passhistory` 시험 패키지에 두었다. `SpaceOperationsContractTests`에 구현 상태 시험 1개를 더했다.
  - 권한: OWNER, 소속 없는 OWNER, 권한 없는 MANAGER, 권한 보유 STAFF, 권한 없는 STAFF, 결제·회원 권한만 있는 STAFF, 다른 지점 MANAGER, 다른 지점의 권한, 중지(`REVOKED`)·승인 대기(`PENDING`) 소속, 로그인 중 소속 중지
  - 응답: 네 객체의 필드 집합, 활성·소진·만료·환불, `NOT_STARTED` 제외, 연락처·회원번호·이름·이메일·메모·사유·결제 금액 미포함, `Cache-Control: no-store`
  - 이용 기록: 실제 체크인·룸 이용·배정으로 만든 진행 중·종료 이용, 다른 지점 이용, 예약만 있는 룸·결제 실패·예정·시작 전 반납 배정 제외, 사물함 시작 시각의 Asia/Seoul 자정
  - 50건: 좌석 52건에 룸·사물함을 섞어 50건, 내림차순, 오래된 좌석 3건과 더 이른 사물함 제외
  - 기록: 호출마다 행 추가, 조회자·지점·사유·시각, 소속 없는 OWNER의 감사 행, 소속이 생긴 OWNER의 표 기록
  - 거부: 403 뒤 감사 행의 결과·조회자·고객·지점·요청 ID, 사유 원문이 어느 행에도 없음, 상호작용 행 없음, 없는 고객과 있는 고객의 같은 403, 없는 지점·음수 고객 ID
  - 범위: 요약 뒤에도 고객 상세·목록·구매 내역·공간 이력이 404이거나 비어 있음
  - 결제 상세: 대상 지점 소속이 아닌 직원은 404, `scope`에 그 지점을 넣으면 403, 그 지점의 `payments:read` 없는 소속 직원은 403, 있는 직원은 200. 다른 지점의 `payments:read`는 대신하지 않는다.
  - 공간 이력: 목록 행과 원장 상세의 JSON 일치, 결제 행과의 대조, 결제 실패 포함, 범위 밖 지점 비노출, `scope` 좁히기, 소속 밖 `scope` 403, 정확 일치로만 찾은 고객 404, 빈 이력
  - 결제 노출: 일반 직원·MANAGER는 null, `payments:read`·`payments:write`는 값 있음, 두 지점에 걸친 직원은 지점 행별로 다름
- 거부 감사가 "롤백 뒤에 남는다"는 것은 403 응답 뒤 DB에서 행을 읽어 확인했다. 외부 트랜잭션 안에서 호출하는 경우는 시험하지 않았다. 2절의 잠금 대기를 피하려고 진입점을 트랜잭션 없이 두었다.
- 미검증: 실제 운영 환경, 거부 감사 기록 자체가 실패할 때의 응답(오류가 그대로 나간다. 데이터는 나가지 않는다), 성능(요약의 이용 조회는 `fk_seat_sessions_1`과 `ix_room_reservations_3`의 고객 열에 기댄다. 실행 계획은 확인하지 않았다).

## 6. 남은 작업 / 보류 사항

- **요청 필터에서 막히는 거부는 감사에 남지 않는다.** `AuthorizationWebConfiguration`이 서비스보다 먼저 계정 상태와 `scope` 쿼리를 본다. 계정이 활성이 아니거나, 계약에 없는 `?scope=`로 소속 밖 지점을 보내면 서비스에 닿기 전에 403이다. 데이터는 나가지 않지만 `CROSS_BRANCH_SUMMARY_DENIED`도 없다. 공통 필터 정책이라 여기서 바꾸지 않았다.
- 이 조회의 횟수 제한은 없다. M5-D08과 같이 후속 과제다. 거부 감사도 호출마다 쌓인다.
- `getCustomerSpaceHistory`의 `x-error-codes`에 `VALIDATION_FAILED`가 없지만 형식이 틀린 `scope`는 기존 공통 동작대로 422다. 응답 목록에는 422가 있어 계약을 고치지 않고 이 기록에만 남긴다.
- 고객 상세 화면과 응대 이력 조회 화면은 Frontend와 후속 이슈의 범위다.
