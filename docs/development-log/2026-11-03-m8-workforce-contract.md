# 2026-11-03 M8 근무표·근태 API 계약

관련: Issue [#82](https://github.com/OpenStudyService/OpenStudy-Backend/issues/82), [계약 문서](../design/foundation/workforce-api.md), [ADR-M8-001](../db/ADR-M8-001.md), [M8 정책 확정 기록](2026-10-28-m8-shift-attendance-policy.md).

## 1. 배경 / 문제

M8 정책(M8-D01~D08)은 확정됐지만 API 계약이 없었다. 근무표(#83)와 출퇴근·근태(#84) 구현, Frontend 연동이 같은 기준으로 병행하려면 operation, 권한·조회 범위, 오류 코드, 충돌 응답, 실근무 분 계산, 시간대 규칙, 동시성 처리를 먼저 고정해야 했다. M4~M7과 같은 방식이다. 이슈 범위는 계약만이고 서버 구현과 스키마 변경은 제외한다. #83·#84의 인수 조건을 계약이 모두 뒷받침해야 한다.

V1 스키마(`shifts`, `shift_events`, `attendance_records`, `attendance_breaks`, `leave_requests`)로 계약을 표현할 수 있는지도 확인해야 했다. ADR은 스키마 변경 없음을 전제로 한다.

## 2. 검토한 선택지

- **근태 명령이 대상 근태를 고르는 방식**
  - `/attendance-records/{id}/clock-out`처럼 ID를 받고 서버가 본인 것인지 검사한다: 다른 직원의 근태 ID를 넣을 수 있고, 관리자가 볼 수 있는 근태에는 403·404 구분이 생긴다.
  - `/me/attendance/*`에서 ID 없이 본인의 진행 중 근태만 바꾼다: `active_employee_id` 유일 키 때문에 진행 중 근태는 직원당 하나다. 다른 직원을 지정할 방법이 없어 FR-ATT-02의 대리 변경 금지가 구조로 보장된다. 이쪽을 택했다.
- **같은 직원의 겹치는 근무 동시 등록**: V1에 구간 겹침을 막는 제약이 없다.
  - 지점 행 잠금: 서로 다른 지점의 MANAGER가 같은 직원에게 동시에 등록하면 막지 못한다.
  - `shifts`의 직원 범위 잠금 읽기: 두 트랜잭션이 같은 간격을 공유 잠금한 뒤 서로의 삽입을 기다려 교착한다.
  - 대상 직원의 `employees` 행 `FOR UPDATE`: 직원 단위로 직렬화된다. 이쪽을 택했다. 다만 권한 상태의 잠금 읽기(지점·소속 `FOR SHARE`)를 먼저 잡으면 직원 전체를 잠그는 기반 명령(가드 → 직원 → 지점)과 교차할 수 있어, 직원 행을 첫 행 잠금으로 잡는다고 적었다.
- **충돌 오류의 본문**
  - 공통 `ApiError`에 `conflicts`를 더한다: 모든 operation의 오류 schema가 바뀐다.
  - 근무 등록·수정의 409에만 `ShiftConflictError`(ApiError 필드 + `conflicts`)를 쓴다: 기존 schema를 바꾸지 않는다. 이쪽을 택했다.
- **볼 수 없는 지점의 충돌 표시**
  - 근무마다 한 항목: 볼 수 없는 지점에 근무가 몇 개인지와 경계가 드러난다.
  - 겹친 구간을 시간순으로 합쳐 한 항목: M8-D05가 허용한 "겹친 시간"만 남는다. 이쪽을 택했다.
- **본인 근태 목록**
  - `GET /me/attendance` 목록을 따로 둔다.
  - `GET /attendance-records` 하나에 조회 범위 조건과 `mine` 필터를 둔다: 일반 직원과 관리자를 같은 질의로 처리하고 "지점 선택만으로 다른 직원 기록이 열리지 않는다"를 한 곳에서 보장한다. 이쪽을 택했다.
- **휴게·퇴근의 중복 방지**
  - `Idempotency-Key`: 기존 관례상 키는 생성·돈이 움직이는 명령에 쓴다.
  - `version`과 상태 검사: 기존 근태 행의 변경이고, 업무 체크 항목 추가가 업무 version을 쓰는 것과 같다. 이쪽을 택했다. 근무 등록과 출근은 새 행을 만들므로 키를 필수로 했다.
- **근태 명령의 검사 순서**: version → 상태(기존 예약 취소 등)와 상태 → version을 비교했다. 응답을 잃은 퇴근 재시도에 "이미 퇴근"을 알려야 하므로 상태 → version을 택했다.
- **진행 중 근태의 실근무 분**: 조회 시각까지의 값과 null을 비교했다. 목록과 상세가 같은 값을 내야 하므로(FR-ATT-04) null을 택했다.
- **근무 수정의 대상**: 직원·지점 변경을 허용하는 안과 막는 안을 비교했다. 직원 변경은 M12 대체근무(`SWAPPED`)의 영역이고 연결된 근태가 다른 직원의 근무를 가리키게 되므로 막고 취소 후 재등록으로 처리한다.
- **근태 시각 정밀도**: `datetime(6)` 그대로 두는 안과 초 미만을 버려 저장하는 안을 비교했다. M8-D07의 "초 단위로 계산"이 하나의 결과만 내도록 후자를 택했다.

## 3. 결정 및 이유

- operation 13개를 정의했다. 근무표 6(#83), 근태 7(#84)이다.
- 조회 범위는 기록 지점 기준이다. 모든 조회 질의에 `OWNER ∨ 기록 지점 MANAGER ∨ 본인` 조건을 넣고 `scope`는 지점만 좁힌다. 일반 직원이 다른 직원 ID로 거르면 빈 목록이다.
- 근무 시각은 분 단위만 받는다. 24시간 경계와 휴게 분 비교를 정확히 하기 위해서다.
- 주는 월요일 시작(Asia/Seoul)이고 근무·근태는 시작·출근 시각의 Asia/Seoul 날짜에 속한다. 자정을 넘는 근무가 두 주에 중복되지 않는다.
- 운영 중지 지점은 근무 등록·수정과 출근만 409 `BRANCH_NOT_ACTIVE`이고 취소·휴게·퇴근·조회는 허용한다(M8-D04).
- 휴게 종료·퇴근은 소속이 회수돼도 허용한다. 열린 근태가 `active_employee_id`로 다른 지점 출근까지 막기 때문이다. 정책에 없는 판단이라 계약의 "정책이 정하지 않아 이 계약에서 정한 것" 표에 모았다.
- workspace 이벤트에 `shifts`, `attendance-records`를 더하고, 기록을 볼 수 있는 연결에만 보내도록 했다. 기존 지점 이벤트처럼 모든 소속에게 보내면 일반 직원이 다른 직원의 근무·근태 ID를 받는다.
- ADR-M8-001은 바꾸지 않았다. 바꿔야 한다고 판단한 항목은 없다.

## 4. 구현 내용

- `docs/design/foundation/workforce-api.md` 신규
- `docs/design/foundation/openapi.json`: 버전 `0.1.10-m8-workforce`, operation 13개, 태그 2개, schema 18개(`Shift`, `AttendanceRecord`, `WeeklyShiftSchedule`, `ShiftConflictError` 등), `WorkspaceEvent.resource` 2개 값. 휴게·퇴근 본문은 기존 `VersionCommand`를 재사용했다.
- `src/test/java/com/openstudy/architecture/WorkforceContractTests.java` 신규, 계약 테스트 17개
- `docs/design/foundation/README.md` 안내 추가

서버 코드, 마이그레이션, `LocalPreviewController`는 바꾸지 않았다.

**스키마 대조**: 계약의 모든 필드를 V1 열로 표현할 수 있다. 취소 시각·처리자·사유는 `shifts`에 열이 없어 `shift_events`의 `CANCELLED` 행에서 읽는다. 휴게 상태는 `attendance_records.status`(`WORKING`·`LEFT`)와 열린 `attendance_breaks`로 계산한다. `attendance_breaks`에 version이 없어 근태 행 version이 휴게 변경을 대표한다. `leave_requests`에 지점이 없어 휴가 충돌은 직원 단위로 비교하고 휴가 상세를 노출하지 않는다(지점은 M12-D02에서 추가).

## 5. 검증 결과

- `npx --no-install @apidevtools/swagger-cli validate docs/design/foundation/openapi.json`: valid
- 스크립트 확인: `$ref` 2198개가 모두 해석됨, operationId 152개 중복 없음
- `git diff --diff-algorithm=histogram`: 기존 줄의 변경은 `info.version`, `info.description`(M8 문장 추가), `WorkspaceEvent.resource`의 마지막 값 뒤 쉼표뿐이다. 기존 operation과 기존 schema의 필드는 바꾸지 않았다.
- 실근무 분 7개 예시와 시간대 변환 예시(UTC↔Asia/Seoul, 2026-11-02·11-09가 월요일)는 Python으로 다시 계산해 문서 값과 일치함을 확인했다.
- `./gradlew cleanTest test --tests 'com.openstudy.architecture.*'`: 통과. 55개(신규 `WorkforceContractTests` 17개 포함), 실패 0

**실행하지 않은 것**

- 전체 테스트는 실행하지 않았다. 이번 변경은 문서·OpenAPI·아키텍처 테스트뿐이다.
- 직원 행 잠금 순서가 교착을 만들지 않는다는 판단은 코드와 잠금 순서를 읽고 내린 설계 판단이다. 실제 동시 요청 검증은 #83·#84의 HTTP·DB 통합 테스트에서 해야 한다.
- Frontend 측 계약 검토와 API 엑셀 동기화는 하지 않았다.

## 6. 남은 작업 / 보류 사항

- #83: `LocalPreviewController`의 `/api/shifts` 샘플 경로가 운영 경로와 겹친다. 운영 컨트롤러가 더 구체적인 경로로 우선하므로 `LocalPreviewIntegrationTests`의 `/api/shifts` 검증이 바뀐다. 샘플을 운영 API로 대체하며 정리한다. 근태 샘플 `/api/attendance`는 경로가 겹치지 않으며 #84에서 정리한다.
- #83·#84: workspace 이벤트를 기록을 볼 수 있는 연결(OWNER, 기록 지점 MANAGER, 직원 본인)에만 보내려면 현재 지점 단위 전달 필터를 확장해야 한다. 이벤트에 기록의 직원을 알 수 있는 정보가 필요하다.
- #83·#84: OWNER는 권한 판정에서 모든 지점을 통과하지만 근무 대상과 출근에는 소속 행이 필요하다. 소속을 따로 검사해야 한다.
- 계정이 비활성·이용 제한되어 닫지 못한 진행 중 근태는 M12 정정으로 처리한다.
- 조기 출근(근무 시작 전 출근)은 M8-D08 문구대로 근무에 연결하지 않는다. 현장 운영에서 불편하면 ADR 조정이 필요하다.
- M12: 휴가 승인·대체근무 승인도 같은 직원 행을 먼저 잠가 근무 등록과 직렬화해야 한다.
