# 2026-11-03 주간 근무표와 예정 근무 등록·수정·취소·충돌 검증 API

관련: Issue [#83](https://github.com/OpenStudyService/OpenStudy-Backend/issues/83), [계약 문서](../design/foundation/workforce-api.md), [ADR-M8-001](../db/ADR-M8-001.md) M8-D01~D06, [계약 기록](2026-11-03-m8-workforce-contract.md), [M8 정책 확정 기록](2026-10-28-m8-shift-attendance-policy.md), [사물함 배정 기록](2026-11-02-locker-assignment-api.md).

## 1. 배경 / 문제

#82에서 고정한 근무표 계약의 6개 operation(`GET /shifts/weekly`, `GET /shifts`, `POST /shifts`, `GET /shifts/{id}`, `PATCH /shifts/{id}`, `POST /shifts/{id}/cancel`)을 구현해야 했다. 핵심 위험은 세 가지였다.

- `shifts`에는 같은 직원의 시간 겹침을 막는 DB 제약이 없다. 충돌은 지점이 달라도 생기므로 잠금 단위가 직원이어야 한다.
- 조회 범위(M8-D02)는 지점 단위가 아니라 "관리하는 지점은 전원, 나머지는 본인"이다. 지점 선택이나 직원 필터로 다른 직원의 기록이 열리면 안 되고, 충돌 응답과 workspace 이벤트로도 새면 안 된다.
- 계약 기록이 넘긴 주의점: `LocalPreviewController`의 `/api/shifts` 샘플 경로 정리, 이벤트 전달 대상 축소, 소속 행이 없는 OWNER, 운영 중지 지점의 409, 동시 등록의 실제 시험.

## 2. 검토한 선택지

- **잠금 경로**
  - 계약 문구 그대로 공통 guard 없이 `멱등 잠금 → 대상 직원 FOR UPDATE → 권한 상태 잠금 읽기`로 가는 별도 트랜잭션: 기존 모든 쓰기는 `AuthorizedCommands`의 guard → 전 직원 `FOR UPDATE` → 전 지점 순서다. guard 없는 근무 명령이 대상 직원 X를 잡은 뒤 요청자 A의 직원 행을 `FOR SHARE`로 읽으면(권한 잠금 읽기), id가 작은 A를 먼저 잠그고 X를 기다리는 기반 명령과 교차해 교착한다. A와 B가 서로를 대상으로 동시에 등록할 때도 같다. 계약은 "같은 방향이라 교착이 없다"고 했지만 요청자 ≠ 대상이면 성립하지 않는다고 판단했다.
  - 기존 `AuthorizedCommands` 안에서 `lockBusinessParent`로 멱등 행 → 대상 직원 행을 잠그고 그 뒤 권한 상태를 잠금 읽기: guard가 모든 명령을 직렬화하므로 교착이 생기지 않는다. 직원 행 잠금은 지금은 guard의 전 직원 잠금과 겹치지만, guard를 나눌 때 같은 직원의 쓰기를 직렬화하는 장치로 남는다. 이쪽을 택했다.
- **충돌 409 본문**: 공통 `ApiError`에 `conflicts`를 더하는 방안은 모든 오류 응답 타입을 바꾼다. 근무 모듈의 `ShiftConflictException`과 컨트롤러 전용 `@ExceptionHandler`로 계약의 `ShiftConflictError`만 내보내는 방안을 택했다. 다른 409는 공통 처리 그대로라 `conflicts`가 없다.
- **이벤트 전달 필터**: outbox에 대상 직원을 저장하는 방안은 `target_employee_id`가 "그 직원에게만 가는 개인 신호"로 이미 쓰여 의미가 겹치고 OWNER·MANAGER에게 보낼 수 없다. 전달 시점에 근무의 직원을 읽어 판정하는 방안을 택했다. 근무의 직원은 바뀌지 않으므로 늦게 읽어도 같다.
- **근태 읽기**: 주간 근무표와 근무의 연결 근태를 채우려면 `attendance_records`, `attendance_breaks`를 읽어야 한다. native SQL 조회와 엔터티 매핑을 비교했고, JPA-01에 맞춰 읽기 전용 엔터티(`insertable=false, updatable=false`)를 두었다. 쓰기는 #84가 맡는다.

## 3. 결정 및 이유

- **명령 순서.** 등록: 잠금 없는 권한 사전 확인(403) → 입력 형식(422) → 시각 규칙(`SHIFT_TIME_INVALID`·`SHIFT_BREAK_INVALID`) → guard·직원·지점 → 멱등 행 → 대상 직원 행 `FOR UPDATE` → 권한 상태 잠금 재확인 → 멱등 재생 → 운영 상태(409) → 대상 소속(`FOR SHARE`, 422) → 충돌(`FOR SHARE`, 409) → 저장·이력·이벤트·멱등 결과. 수정·취소: 근무 읽기(직원 ID) → 404·403 사전 확인 → 본문 검사(`IMMUTABLE_FIELD` 등) → guard·직원·지점 → 대상 직원 → 근무 행 `FOR UPDATE` → 404·403 재확인 → version → 상태 → (수정만) 운영 상태 → 소속 → 최종 값의 시각 규칙 → 자기 제외 충돌.
- **MANAGER 판정은 `BranchAction.MANAGE_SHIFTS`(역할만, 운영 지점 조건 없음)로 한다.** `requiresOperatingBranch`를 쓰면 운영 중지 지점이 403이 되므로, 운영 상태는 잠금 상태의 `branches`에서 따로 보고 409 `BRANCH_NOT_ACTIVE`로 답한다. 취소는 운영 상태를 보지 않는다.
- **대상 직원은 소속 행으로만 판정한다.** `employees.status=ACTIVE`이고 그 지점 `branch_memberships.status=ACTIVE`인 행이 있어야 한다. OWNER가 요청자면 `permits`로 모든 지점을 통과하지만, OWNER가 대상이면 소속 행이 없을 때 422 `INVALID_SHIFT_EMPLOYEE`다.
- **조회 범위.** 모든 목록·주간 근무표 질의에 `branch_id ∈ scope의 읽기 가능 지점 AND (branch_id ∈ 관리 지점 OR employee_id = 요청자)`를 넣는다(`ShiftSearchRepository`). `employeeId`와 `mine`은 그 위에 교집합으로만 더한다. 상세·수정·취소·충돌 표시의 "볼 수 있음"도 같은 조건(`ShiftService.sees`)이다.
- **충돌 응답.** 볼 수 있는 근무는 항목마다 `shift`(ID·지점·시작·종료)를 담고, 볼 수 없는 근무와 승인 휴가는 요청 구간과의 교집합만 시간순으로 합친다(맞닿은 구간 포함). 휴가는 `[starts_on 00:00, ends_on 다음 날 00:00)` Asia/Seoul이고 0.5일도 하루 전체다.
- **이벤트 전달.** `WorkspaceEventService.dispatch`에 `shifts` 자원만 추가 조건을 둔다. 지점 범위 확인 뒤 `ShiftService.announcedTo`가 OWNER·그 지점 MANAGER·근무 직원 본인일 때만 보낸다. 다른 자원의 전달 조건은 바꾸지 않았다.
- **로컬 샘플.** `LocalPreviewController`의 목록·상세·501 쓰기 경로에서 `shifts`를 뺐다. `/api/home`과 `/api/local-preview/lookups`의 샘플 `shifts` 행, 근태 샘플 `/api/attendance`는 그대로 두었다(경로가 겹치지 않고, 근태는 #84 몫).

계약이 정하지 않아 판단한 점:

- **같은 값의 수정**: 업무 수정과 같이 version·이력·이벤트 없이 현재 근무를 200으로 돌려준다. 시각 규칙·소속·충돌 검사는 먼저 수행한다.
- **예정 휴게의 상한**: 스키마의 `maximum: 1439`보다 계약 표의 "휴게 ≥ 길이 → `SHIFT_BREAK_INVALID`"를 따랐다. 음수·정수가 아닌 값만 `VALIDATION_FAILED`다.
- **본문 형식 오류의 위치**: 등록은 `branchId`만 먼저 읽어 403을 판정한 뒤 나머지 형식을 본다. 수정·취소는 404·403 사전 확인 뒤에 본문을 본다. 표의 "403 → 형식 오류" 순서를 지키기 위해서다.
- **`shift_events` 값**: 상태는 DB 코드(`SCHEDULED`·`CANCELLED`), version은 API version(DB+1), 시각은 UTC ISO 문자열이다.
- **감사 기록**: `audit_events`는 남기지 않았다. 업무(task_events)처럼 근무 변경은 `shift_events`가 이력이다.
- **상세·수정·취소의 `scope`**: 계약 operation에 `scope` 파라미터가 없어 받지 않고 전체 읽기 범위로 판정한다.
- **연결 근태 요약의 순서**: `clockInAt`, `id` 오름차순.
- **`canEdit`**: 계약 정의대로 "근무 지점의 MANAGER 이상이고 취소되지 않음"이다. 운영 중지 지점에서도 true일 수 있다(수정은 409, 취소는 가능).

## 4. 구현 내용

- `modules/workforce`
  - 엔터티: `Shift`(`@Version`), `ShiftStatus`, `ShiftEvent`, 읽기 전용 `LeaveRequest`·`AttendanceRecord`·`AttendanceBreak`. 생성 열 `active_employee_id`는 매핑하지 않았다.
  - 저장소: `ShiftRepository`(근무 행 잠금, 겹침 잠금 읽기, 직원 행 `FOR UPDATE`와 소속 `FOR SHARE`의 native 쿼리), `ShiftSearchRepository`(조회 범위 조건), `ShiftEventRepository`, `LeaveRequestRepository`, `AttendanceRecordRepository`, `AttendanceBreakRepository`
  - 서비스: `ShiftService`, `ShiftInputs`(시각 규칙·Seoul 날짜), `ShiftConflicts`(교집합·합치기), `AttendanceReadings`(근태 상태와 M8-D07 실근무 분), `ShiftConflictException`
  - `ShiftController`와 응답 DTO(`ShiftView`, `WeeklyShiftSchedule`, `AttendanceRecordView`, `ShiftConflictError` 등)
- `BranchAction.MANAGE_SHIFTS`
- `WorkspaceEventService`: `shifts` 자원 매핑과 전달 대상 필터
- `LocalPreviewController`와 `LocalPreviewIntegrationTests`의 `/api/shifts` 샘플 정리
- OpenAPI 6개 operation을 `implemented`로 바꾸고 `info.description`에 #83 구현을 더했다. `workforce-api.md`의 구현 상태 문장을 갱신했다. 그 밖의 계약 변경은 없다. 마이그레이션은 없다.

## 5. 검증 결과

- `./gradlew cleanTest test` 전체(39분 46초): 70개 suite, 892개 테스트, 실패·오류·건너뜀 0. Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 코드 기준 1회 실행으로 통과했고, 그 뒤에는 이 기록만 바꿨다.
- 신규 `ShiftIntegrationTests` 21개(HTTP·DB)
  - 권한: STAFF·다른 지점 MANAGER·없는 지점 403, MANAGER·OWNER 등록, MANAGER의 본인·다른 MANAGER 등록
  - 대상 직원: 다른 지점 소속, 없는 직원, 소속 행 없는 OWNER(요청자가 MANAGER·OWNER 모두) 422, 소속 행을 넣은 OWNER 201, 소속 회수·계정 정지 422
  - 시각 규칙: 계약 표의 10행(540·자정 넘김·1440·1441·0·음수·초 단위·479·480·-1)과 초 미만, 휴게 1.5·문자열, 오프셋 없는 시각, 정의되지 않은 필드, 키 누락
  - 판정 순서: 403이 형식 오류보다 앞, 시각 규칙 → 운영 중지 → 대상 직원
  - 충돌: 보이는 근무는 ID·지점·원래 시각, 볼 수 없는 지점 근무 3개(맞닿은 2개와 요청 끝에서 잘리는 1개)는 합친 2항목. 본문에 그 지점 ID·근무 ID·원래 경계·휴게·version·지점 이름이 없음. OWNER는 같은 요청에서 4개를 모두 이름으로 받음. 반대 방향(둘째 지점 MANAGER)도 같은 결과. 반열린 구간 경계 201
  - 승인 휴가(테스트 데이터로 `leave_requests`에 직접 삽입): 계약 예시(22:00~06:00)의 교집합, 0.5일의 하루 전체, 이틀 연속 휴가의 합침, 자정 경계 201, REQUESTED·REJECTED·CANCELLED 무시, 휴가 ID·사유·일수 미노출. 근무와 휴가가 섞인 계약 예시 요청의 3항목과 시각
  - 수정: 최종 값 검사, 자기 제외, 같은 값의 무변경, `IMMUTABLE_FIELD`, `version`만 보낸 422, 오래된 version 409(`latestVersion`·`reloadUrl`, `conflicts` 없음), 소속 회수 뒤 수정 422·취소 200, `shift_events`의 before/after/reason/처리자
  - 취소: 행·이력(CREATED·CANCELLED)·연결 근태 유지, 응답의 `cancellation`, 기본 목록·주간 근무표 제외와 `status=취소`·상세 포함, 재취소·수정 409 `INVALID_STATE`, 같은 시간 재등록 201
  - 운영 중지 지점: 등록(MANAGER·OWNER)·수정 409, 조회·취소 허용
  - 멱등: 같은 키 재시도의 같은 응답과 1행, 다른 입력 409, 키 없는 재시도의 `SHIFT_CONFLICT`, outbox 1건
  - 조회 범위: STAFF는 scope·`mine`·다른 직원 `employeeId`와 무관하게 본인 근무만(빈 목록·total 0), 다른 직원 상세 404, 본인 근무 수정·취소 403. 같은 직원의 다른 지점 근무는 그 지점 권한 없이 목록·주간·상세·수정·취소 모두에서 보이지 않음. 지점별 역할(첫 지점 MANAGER, 둘째 지점 STAFF), OWNER 전체, 소속 회수 뒤 본인 기록 숨김
  - 주간 근무표: 일요일 22:00 시작 근무는 그 주에만, 월요일 08:00(UTC로는 일요일) 근무는 다음 주에만, `weekOf`가 월·목·일이어도 같은 주, 근태는 별도 배열(연결 없는 근태 포함), 근무 중·휴게 중 상태와 실근무 분, 잘못된 날짜 422
  - 목록: Asia/Seoul `workDate` 필터, `startsAt`·`id` 정렬, 페이지, 잘못된 필터 422. 응답 필드가 계약 `Shift`와 정확히 같고 목록·주간·상세가 같은 값
- 신규 `ShiftConcurrencyIntegrationTests` 5개(요청마다 스레드, 래치로 동시 출발)
  - 같은 직원 겹치는 등록 8건 × 3회(MANAGER·OWNER 섞음): 매회 201 1건, 나머지 `SHIFT_CONFLICT`
  - 두 지점 MANAGER가 같은 직원에게 동시에 등록 6회: 매회 201·409 한 건씩, 진 쪽은 "다른 지점 근무". 이번 실행에서 첫 지점 승 1회
  - **행 잠금 실효성**: guard 없이 대상 직원 행만 `FOR UPDATE`로 잡고 다른 지점 근무를 넣은 트랜잭션을 두고 등록·수정 요청을 보냄. 요청은 그 트랜잭션이 커밋할 때까지 끝나지 않았고, 커밋 뒤 잠금 읽기로 그 근무를 보고 409가 됐다. 시험 DB 계정은 `information_schema.innodb_trx`를 읽지 못해 `LOCK WAIT` 확인 대신 1.5초 대기 뒤 미완료를 확인했다.
  - 교착: 두 MANAGER가 서로를 대상으로 등록, 수정·취소, 두 지점 등록, 같은 지점 업무 생성을 9건씩 3회 동시에 보냄. 500 없이 200·201·409(`SHIFT_CONFLICT`)만 나왔고 직원별로 겹친 예정 근무가 없음
  - 임시 변경 확인: 등록·수정·취소의 대상 직원 `lockEmployee` 호출을 지우고 동시성 시험 5개를 다시 실행했더니 모두 통과했다(되돌림). 지금은 guard의 전 직원 `FOR UPDATE`가 같은 역할을 하므로 명시적 직원 행 잠금은 시험으로 구별되지 않는다. 직원 행 잠금은 guard를 나눌 때를 위한 장치로 남는다.
- 신규 `ShiftEventDeliveryIntegrationTests` 3개(실제 SSE): 근무 등록·수정·취소 이벤트를 OWNER·그 지점 MANAGER·대상 직원만 받고, 같은 지점 다른 STAFF와 다른 지점 MANAGER는 받지 않음. 다른 지점 근무는 이 지점 MANAGER에게 가지 않음. 같은 지점의 `tasks` 이벤트는 STAFF에게 그대로 감
- `WorkforceContractTests`에 구현 상태 시험 1개를 더하고 기존 design-target 시험을 이슈 분할 확인으로 바꿨다(18개). `LocalPreviewIntegrationTests`의 `/api/shifts` 검증을 `members` 샘플로 옮기고, `local`에서 `/api/shifts`가 운영 API로 답하는 시험을 더했다(6개).
- 미검증: 운영 환경, 실제 #84 출퇴근 명령이 만든 근태(이번에는 SQL로 넣은 행만 읽음), Frontend 연동, guard가 없는 경로의 교착(현재 코드에 그런 경로가 없음)

## 6. 남은 작업 / 보류 사항

- #84: 출퇴근·근태 명령과 근태 목록·상세. 이번에 만든 근태 읽기 엔터티와 `AttendanceReadings`를 이어 쓴다. `attendance-records` 이벤트 전달 필터도 같은 방식으로 더해야 한다. 근태 샘플 `/api/attendance` 정리도 #84다.
- 계약의 동시성 절은 guard 없는 경로를 전제로 "교착이 없다"고 적었지만, 요청자와 대상이 다르면 성립하지 않는다(2절). 지금은 guard 안에서 실행해 문제가 없다. guard를 나누거나 계약 문구를 고칠 때 다시 판단해야 한다. 계약 문서는 바꾸지 않았다.
- M12: 휴가 승인·대체근무 승인도 같은 직원 행을 먼저 잠가야 한다. `LeaveRequest` 엔터티는 충돌 검사에 필요한 열만 매핑했다.
- 홈 화면(`modules/home`)은 아직 구현이 없다. 근무 요약이 생기면 같은 조회 범위 조건을 써야 한다.
