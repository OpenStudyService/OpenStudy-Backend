# 2026-11-04 본인 출근·휴게·퇴근과 실근무 시간·근태 조회 API

관련: Issue [#84](https://github.com/OpenStudyService/OpenStudy-Backend/issues/84), [계약 문서](../design/foundation/workforce-api.md), [ADR-M8-001](../db/ADR-M8-001.md) M8-D02·D04·D07·D08, [계약 기록](2026-11-03-m8-workforce-contract.md), [근무표 구현 기록](2026-11-03-shift-schedule-api.md), [M8 정책 확정 기록](2026-10-28-m8-shift-attendance-policy.md).

작업은 2026-11-03에 시작해 사용량 한도로 중단됐다가 2026-11-04에 이어서 마쳤다. 구현과 관련 시험은 2026-11-03에, 이 기록과 최종 전체 시험은 2026-11-04에 진행했다.

## 1. 배경 / 문제

#82 계약에서 #84로 표시된 근태 operation 7개(`GET /me/attendance/current`, `POST /me/attendance/clock-in`, `break-start`, `break-end`, `clock-out`, `GET /attendance-records`, `GET /attendance-records/{id}`)를 구현해야 했다. #83은 근태를 읽기 전용 엔터티로만 읽었고, 쓰기는 이번 범위였다.

핵심 위험:

- 진행 중 근태는 직원당 하나여야 한다(지점 무관, `uk_attendance_records_1`). 동시 출근에서도, 유일 키 위반이 나도 500이 되면 안 된다.
- 휴게에는 열린 휴게를 하나로 막는 키가 없다. 같은 근태의 휴게·퇴근 동시 요청에서 하나만 성공해야 한다.
- 다른 직원의 근태를 바꿀 수 없어야 하고, 조회 범위(M8-D02)·이벤트로도 다른 직원의 근태가 새면 안 된다.
- 실근무 분(M8-D07)이 목록·상세·주간 근무표·근무의 연결 근태 요약에서 같아야 한다.
- 예정 근무 연결이 취소 커밋과 경쟁할 때 취소된 근무에 연결되면 안 된다.

## 2. 검토한 선택지

- **잠금 경로**: 계약 처리 순서 표를 그대로 공통 가드 없이 구현하는 방안은 #83에서 교착 가능성 때문에 계약에서 배제됐다. 기존 `AuthorizedCommands.activeEmployee` 안에서 출근은 멱등 행 → 본인 직원 행, 휴게·퇴근은 본인 진행 중 근태 행을 `lockBusinessParent`로 잠그는 방안을 택했다. `inBranches`는 OWNER를 모든 지점에서 통과시키므로 "OWNER도 소속 행 필요"를 표현할 수 없어 쓰지 않았다.
- **근태 version**: JPA `@Version`은 열이 바뀔 때만 오른다. 휴게 시작·종료는 근태 행의 열을 바꾸지 않으므로 version이 오르지 않는다. 엔터티 강제 증가(`OPTIMISTIC_FORCE_INCREMENT`)와 직접 증가를 비교했고, 근태 행을 이미 `FOR UPDATE`로 잡고 있으므로 직접 증가(`touch()`)가 단순해 이쪽을 택했다.
- **진행 중 근태 찾기**: `employee_id AND status` JPQL과 생성 열 `active_employee_id` native 조회를 비교했다. 후자가 유일 키 인덱스 하나로 정확히 한 행을 가리켜 native(`FOR UPDATE`)로 했다. 생성 열은 여전히 매핑하지 않는다.
- **유일 키 위반 처리**: 기존 `DuplicateKeys.translate`(MySQL 1062만 좁힘)를 쓰고 `uk_attendance_records_1`이면 409 `ATTENDANCE_ALREADY_WORKING`으로 바꿨다(사물함 `LockerConflicts`와 같은 방식). 실제 경쟁으로는 이 경로에 도달하지 않으므로 시험은 채팅 `MessageDuplicateKeyTests`처럼 조회를 한 번 가리는 spy로 했다.
- **응답 매핑 공유**: `ShiftService`의 근태 매핑을 그대로 두고 새 서비스에 복사하는 방안은 값이 어긋날 위험이 있다. 매핑을 `AttendanceViews`로, M8-D02 판정(`sees`, 목록 필터)을 `RecordScope`로 옮겨 두 서비스가 같은 코드를 쓰게 했다.
- **로컬 샘플 `/api/attendance`**: 운영 경로와 겹치지 않는다. 그대로 두는 방안과 종료하는 방안을 비교했다. 샘플 목록은 빈 목록을 돌려주어 운영 근태가 없다고 오해하게 만들 수 있고, 계약 문서가 "샘플 정리는 #84에서 한다", API 계약이 "운영 CRUD로 대체한 뒤 해당 local 샘플만 종료"라고 정해 목록 경로에서만 뺐다. #83처럼 lookups의 `attendance` 키는 남겼다.

## 3. 결정 및 이유

- **출근 순서**: 본문 형식(422, 정의되지 않은 필드 포함) → `Idempotency-Key`(422) → 잠금 없는 소속 사전 확인(403) → 가드·전 직원·전 지점 → 멱등 행 → 본인 직원 행 `FOR UPDATE` → 잠금 상태의 소속 재확인(403) → 멱등 재생 → 운영 상태(409 `BRANCH_NOT_ACTIVE`) → 진행 중 근태 `FOR UPDATE`(409 `ATTENDANCE_ALREADY_WORKING`) → 연결 근무 `FOR SHARE` → 저장(유일 키 변환) → 이벤트 → 멱등 결과. 계약 출근 검증 표의 순서다.
- **소속 판정**: 해당 지점에 `ACTIVE` 소속 행이 있어야 한다. OWNER의 전역 역할은 소속이 아니다(`fk_attendance_records_1`). 소속 행 없는 OWNER는 403이다.
- **휴게·퇴근 순서**: 가드 → 본인 진행 중 근태 `FOR UPDATE`(없으면 409 `ATTENDANCE_NOT_WORKING`) → 열린 휴게 잠금 읽기로 상태 판정 → version(409 `VERSION_CONFLICT`, `reloadUrl=/api/me/attendance/current`) → (휴게 시작만) 근태 지점의 현재 소속(403) → 변경·version 증가 → 이벤트. 계약의 "상태 → version" 순서와 휴게 시작 표의 "상태·version·소속" 순서를 따랐다. 운영 중지 지점은 휴게·퇴근에서 보지 않는다.
- **시각**: 서버 시각을 초 단위로 버려 저장한다. 실근무 분 계산은 #83의 `AttendanceReadings`를 그대로 쓴다.
- **조회 범위**: 목록은 #83의 `ShiftSearchRepository.where`를 같이 쓴다(`branch_id ∈ 읽기 가능 지점 AND (branch_id ∈ 관리 지점 OR employee_id = 요청자)`). 상태 필터 `휴게 중`/`근무 중`은 열린 휴게 `EXISTS`로 같은 질의에서 거른다. 정렬은 `clockInAt`, `id` 내림차순. 상세는 볼 수 없으면 404.
- **현재 근태**: 소속이 회수된 지점의 진행 중 근태도 본인에게 반환한다(계약 예외).
- **이벤트**: `WorkspaceEventService`에 `attendance-records` 자원 매핑과 필터를 더했다. 지점 범위 확인 뒤 `AttendanceService.announcedTo`가 OWNER·그 지점 MANAGER·근태 직원 본인일 때만 보낸다. `shifts`와 같은 방식이고 다른 자원 조건은 바꾸지 않았다.

계약이 정하지 않아 판단한 점:

- **출근 멱등 범위**: 근무 등록은 지점을 범위 키로 쓰지만, 출근은 범위 키를 `me`(본인 하나)로 했다. 지점별로 나누면 같은 키로 다른 지점을 보낸 요청이 "같은 키·다른 입력"(409 `IDEMPOTENCY_CONFLICT`)이 아니라 별개 요청이 되기 때문이다.
- **멱등 재생 위치**: 잠금 상태의 소속 확인 뒤, 운영 상태·진행 중 근태 검사 앞이다. 근무 등록과 같다. 그래서 재시도는 그 사이 퇴근했어도 처음 201을 받는다(계약이 예고한 동작).
- **퇴근도 version을 올린다**: 모든 변경이 근태 version을 올린다.
- **소속 회수 뒤 이벤트**: 회수된 지점의 근태 이벤트는 지점 범위 확인에서 걸러져 본인에게도 가지 않는다. 그 기록은 목록·상세에서 숨겨지므로 일관되지만, 본인의 다른 세션은 `current`를 직접 다시 읽어야 한다.
- **감사 기록**: `audit_events`는 남기지 않았다. 근태 행 자체가 이력이고 정정은 M12다.
- **상세의 `scope`**: 계약 operation에 없어 받지 않는다.

계약 변경 필요 사항은 찾지 못했다. 계약 문서는 구현 상태 문장만 바꿨다.

## 4. 구현 내용

- `modules/workforce`
  - 엔터티: `AttendanceRecord`(쓰기 가능, 생성자·`touch`·`clockOut`), `AttendanceBreak`(쓰기 가능, `end`). `insertable/updatable=false`를 걷어냈고 근무표가 읽는 열은 그대로다.
  - 저장소: `AttendanceRecordRepository.lockedCurrent`(native `active_employee_id` `FOR UPDATE`), `findFirstByEmployeeIdAndStatus`, `AttendanceBreakRepository.locked`, `ShiftRepository.covering`(연결 근무 `FOR SHARE`), `ShiftSearchRepository.findAttendance`
  - 서비스: `AttendanceService`(신규), `AttendanceViews`·`RecordScope`(공유, 신규). `ShiftService`는 이 둘을 쓰도록 바꿨고 동작은 그대로다.
  - `AttendanceController`, `CurrentAttendance` DTO
- `WorkspaceEventService`: `attendance-records` 자원과 전달 필터
- `LocalPreviewController`: 목록 경로에서 `attendance` 제거
- OpenAPI: 7개 operation을 `implemented`로, `info.description`에 #84 구현 문장 추가. `workforce-api.md` 구현 상태 문장 갱신(M8 operation 13개 모두 구현). 마이그레이션 없음.

근태를 읽는 다른 경로 확인:

- 근무 응답의 연결 근태 요약, 주간 근무표 `attendance`: `AttendanceViews`로 옮겨 근태 API와 같은 매핑·계산을 쓴다(시험으로 값 일치 확인).
- 홈(`modules/home`): 구현이 없다. 로컬 `/api/home` 샘플은 `attendance`를 담지 않는다.
- 로컬 lookups의 `attendance`: 빈 샘플 배열이다. #83이 lookups의 `shifts`를 남긴 것과 같이 남겼다.
- 이벤트 전달: 위 필터.
- 그 밖에 `attendance_records`, `attendance_breaks`를 읽는 main 코드는 없다.

## 5. 검증 결과

- 2026-11-04 관련 시험(`modules.workforce.*`, `architecture.*`, `modules.workspace.*`, `LocalPreviewIntegrationTests`): 18개 suite, 123개 테스트, 실패·오류·건너뜀 0.
- 2026-11-04 `./gradlew cleanTest test` 전체(BUILD SUCCESSFUL, 39분 25초): 74개 suite, 914개 테스트, 실패·오류·건너뜀 0. Testcontainers MySQL 사용. 수치는 `build/test-results/test/*.xml`을 합산했다. 1회 실행으로 통과했고 그 뒤에는 이 기록만 바꿨다.

- 신규 `AttendanceIntegrationTests` 13개(HTTP·DB, 서버 시계를 단계마다 고정)
  - 출근: 초 미만 버린 서버 시각(`09:00:30.789` → `09:00:30`, DB `MICROSECOND=0`), 선택 지점·본인, `Location`, 계약 필드 그대로, `current`의 `asOf`, outbox 1건
  - 입력: `clockInAt`·`employeeId`·`shiftId` 필드, 빈 본문, 잘못된 ID 422, 키 누락·짧은 키 422, 형식 오류가 403보다 앞, 같은 키 재시도 같은 결과 1행, 같은 키 다른 지점 `IDEMPOTENCY_CONFLICT`, 다른 지점 새 키 `ATTENDANCE_ALREADY_WORKING`, 퇴근 뒤 같은 키 재시도의 처음 201
  - 소속: 다른 지점·없는 지점 403, 소속 행 없는 OWNER 403 → 소속 행 추가 뒤 201, 회수된 소속 403
  - 운영 중지: 새 출근 409 `BRANCH_NOT_ACTIVE`, 비소속은 여전히 403, 진행 중 근태가 있어도 `BRANCH_NOT_ACTIVE`가 먼저, 진행 중 근태의 휴게 시작·종료·퇴근 200, 목록·상세 조회 200
  - 상태 표: 진행 중 근태 없음/근무 중/휴게 중의 모든 칸, 상태가 version보다 먼저(낡은 version으로도 상태 오류), 상태가 맞고 version만 낡으면 `VERSION_CONFLICT`(`latestVersion`, `reloadUrl`), 휴게 여러 번, 중복 퇴근 `ATTENDANCE_NOT_WORKING`, 퇴근 뒤 재출근
  - 명령 본문: `version` 외 필드·형식 오류 422, 아무것도 바뀌지 않음
  - 타인 변경: 동료·MANAGER·OWNER의 휴게·퇴근은 자기 진행 중 근태가 없어 409, ID 필드 422, `/attendance-records/{id}` 쓰기 경로 없음(404·405), 대상 근태 변화 없음
  - 실근무 분: 계약 경계 예시 7개를 명령으로 기록해 480·540·0·0·498·480·0. 퇴근 응답·상세·본인 목록·MANAGER 목록·주간 근무표·근무 상세의 연결 요약이 모두 같은 값. 초 미만 시각 저장 없음. SQL로 넣은 비정상 휴게의 0 하한, 진행 중 null
  - 근무 연결: 예정 근무만으로 출근되지 않음, 조기 출근(08:55)·종료 시각 정각 미연결, 시작 정각·종료 1초 전 연결, 다른 지점 근무 미연결, 취소된 근무 미연결, 연결 뒤 근무 수정으로 연결 유지, `shiftId` 필터
  - 조회 범위: STAFF는 scope·`employeeId`·`mine`과 무관하게 본인만(다른 직원 ID는 빈 목록·total 0), 다른 직원 상세 404, 읽을 수 없는 scope 403. 지점별 역할(첫 지점 MANAGER·둘째 지점 STAFF), 두 지점 직원의 다른 지점 기록, 둘째 지점 MANAGER, OWNER
  - 필터: Asia/Seoul 날짜(UTC로 같은 날인 23:30·00:30 구분), 상태 3종, 정렬, 페이지, 잘못된 값 422
  - 소속 회수: 진행 중 근태는 `current`로 보이고 목록·상세에서 숨김, 휴게 종료·퇴근 200, 휴게 시작 403(낡은 version이면 `VERSION_CONFLICT` 먼저), 퇴근 뒤 다른 지점 출근 가능, 기록 지점 MANAGER는 계속 조회
- 신규 `AttendanceConcurrencyIntegrationTests` 5개(요청마다 스레드)
  - 같은 직원의 동시 출근 8건 × 3회(두 세션, 두 지점): 매회 201 1건, 나머지 `ATTENDANCE_ALREADY_WORKING`, 진행 중 근태 1개
  - 같은 근태의 휴게 시작 2건 + 퇴근 2건 × 4회: 매회 200 1건, 열린 휴게 ≤ 1, 저장 상태 일치. 휴게 중이면 휴게 종료 3건 중 200 1건
  - 가드 없이 직원 행만 `FOR UPDATE`로 잡고 진행 중 근태를 넣은 트랜잭션: 출근 요청은 커밋까지 끝나지 않았고, 커밋 뒤 잠금 읽기로 보고 409(유일 키가 아닌 검사 경로). 지금은 가드의 전 직원 잠금에서 기다리므로 명시적 직원 행 잠금과 구별되지 않는다(#83과 같은 한계).
  - 근무 행을 잠그고 취소 중인 트랜잭션: 출근 요청이 기다렸다가 커밋이면 연결하지 않고, 롤백이면 연결함
  - 교착: 출근·휴게·퇴근, 두 지점 근무 등록, 업무 생성 11건 × 3회 동시. 500 없이 200·201·409(계약 코드)만, 진행 중 근태·열린 휴게 직원당 ≤ 1
- 신규 `AttendanceDuplicateKeyIntegrationTests` 1개: 잠금 조회를 한 번 가려 유일 키에 닿게 하면 409 `ATTENDANCE_ALREADY_WORKING`, 행·outbox·멱등 기록이 남지 않고 같은 키를 나중에 다시 쓸 수 있음
- 신규 `AttendanceEventDeliveryIntegrationTests` 2개(실제 SSE): 출근·휴게 시작·종료·퇴근 이벤트를 OWNER·그 지점 MANAGER·본인만 받고, 같은 지점 다른 STAFF·같은 지점 소속인 다른 직원·다른 지점 MANAGER는 받지 않음. 둘째 지점 근태는 첫 지점 MANAGER와 둘째 지점 다른 STAFF에게 가지 않음. `tasks` 이벤트는 STAFF에게 그대로 감
- `WorkforceContractTests`: 구현 상태 시험을 "M8 operation 모두 implemented"로 바꿈(18개). `LocalPreviewIntegrationTests`: `/api/attendance` 404, 운영 `/api/attendance-records`·`/api/me/attendance/current` 응답, 소속 없는 샘플 OWNER의 출근 403 시험 추가(7개)
- 임시 변경 확인(전체 시험 전에 수행하고 되돌림): (1) 유일 키 변환을 빼자 `AttendanceDuplicateKeyIntegrationTests`가 500으로 실패, (2) 연결 근무 조회의 `FOR SHARE`를 빼자 취소 경쟁 시험이 취소된 근무에 연결해 실패. 두 시험이 해당 보호를 실제로 검증함을 확인했다.
- 미검증: 운영 환경, Frontend 연동, 가드가 없는 경로의 교착(현재 코드에 그런 경로 없음), 명시적 직원 행 잠금 단독의 효과(가드와 구별 불가)

## 6. 남은 작업 / 보류 사항

- Frontend: 근태 화면은 `/api/attendance`(로컬 샘플)와 MSW의 `/attendance/clockin`, `/attendance/{id}/{action}` 경로를 쓰고 있어 계약 경로(`/me/attendance/*`, `/attendance-records`)로 옮겨야 한다. 로컬 프로필에서 `/api/attendance`는 이제 404다.
- 계정 비활성·이용 제한으로 닫지 못한 근태, 관리자 대리 처리는 M12 정정 범위다.
- 가드를 나눌 때 직원 행 잠금과 근태 행 잠금의 순서를 다시 검증해야 한다.
