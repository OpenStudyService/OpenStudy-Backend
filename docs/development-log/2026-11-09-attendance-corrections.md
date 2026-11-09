# 2026-11-09 근태 정정 신청·승인·반려·취소 (#107)

## 1. 배경 / 문제

- M12 계약(#106)의 근태 정정 operation 6개를 구현한다(FR-CORR-01~04, M12-D01·D05·D08).
- 직원은 본인의 퇴근한 근태에서 출퇴근 시각 또는 휴게 구간 하나의 정정을 신청한다. 근무 기록 지점의 MANAGER 이상이 승인하면 그때만 근태가 바뀌고, 실근무 분은 M8 계산으로 다시 나온다.
- M8에서 `AttendanceCorrection` 엔터티는 읽기 전용(`insertable=false`)으로만 매핑되어 있었고, 근태 엔터티의 `clock_in_at`과 휴게 `started_at`은 갱신 금지였다.
- 이슈 인수 조건 5는 "반려는 사유와 처리자를 남긴다"인데, 계약은 "V1에 저장 칼럼이 없어 반려 사유를 받지 않는다"고 정했다. 실제로 `attendance_corrections`에는 `decided_by`, `decided_at`만 있다.

## 2. 검토한 선택지

- **반려 사유 저장 위치**(착수 전에 사용자에게 물었다)
  - `audit_events`에 저장: V1의 `reason` 칼럼을 쓴다. 마이그레이션이 필요 없고, 좌석 퇴실이 같은 방식(`AuditTrail.recorded`)으로 처리자·사유를 남기고 다시 읽는다. 응답을 만들 때 감사 행을 한 번 더 조회해야 한다.
  - `attendance_corrections`에 칼럼 추가(V12): 정정 행만으로 응답이 끝난다. 마이그레이션과 번호 고정 테스트 3개, 스키마 추출 문서 갱신이 따라온다.
  - 계약대로 사유를 받지 않음: 인수 조건 5를 채우지 못해 PR을 "관련"으로만 연결해야 한다.
- **사유 필수 여부**: 필수(빈 값 422) 또는 선택.
- **겹침 응답의 숨김 규칙**: M8 `ShiftConflicts`를 확장하는 안과 근태용을 따로 두는 안. 대상 필드(`attendanceRecordId`)와 진행 중 근태의 끝("요청 시각") 처리가 달라 근태용 `AttendanceConflicts`를 따로 두고 같은 병합 규칙을 썼다.

## 3. 결정 및 이유

- **반려 사유: `audit_events`에 저장, 필수**(2026-11-09 사용자 결정, 추천안 채택).
  - 반려 본문은 `version`, `reason`(1~500자)이다. 감사 행은 `entity_type=attendance-corrections`, `action=REJECTED`, 처리자, 근태 기록 지점, 사유를 담는다.
  - 응답 `decision`은 정정 전용 스키마 `AttendanceCorrectionDecision`(`by`, `at`, `reason`)으로 바꿨다. 휴가·대체근무가 쓰는 `RequestDecision`은 그대로 둬 다른 응답에 `reason` 필드가 생기지 않게 했다.
  - 휴가·대체근무 반려는 계속 사유를 받지 않는다. 계약 문서의 공통 규칙에 이 예외를 적었다.
- **잠금 순서**는 계약 그대로다. 공통 가드 → 신청자 직원 행 `FOR UPDATE` → 정정 행 `FOR UPDATE` → 근태 행 `FOR UPDATE`(신청은 `FOR SHARE`) → 휴게 행(잠금 읽기). 반려·취소도 같은 틀로 잠가 잠금 순서를 하나로 유지했다(#108과 같은 판단).
- **검사 순서**는 #108·#109와 같다. 본문 형식(422) → 404 → 403 → 403 `SELF_DECISION_FORBIDDEN` → 409 `INVALID_STATE`(`latestVersion`·`reloadUrl`) → `VERSION_CONFLICT` → 겹침 → 휴게 위치.
- **신청 때 검사 순서**는 계약 표를 따랐다. 시각 규칙(422) → `breakId` 소속(422) → 변경 없음(422) → 퇴근 전(409) → 대기 정정(409) → 겹침(409) → 휴게 위치(409).
- **겹침(M12-D08)**: 같은 직원의 다른 근태 전부(지점 무관, 진행 중이면 끝을 요청 시각으로 본다)를 반열린 구간으로 비교한다. 휴게 정정은 근무 구간이 바뀌지 않으므로 겹침 검사 없이 휴게 위치만 본다.
- **휴게 위치**: 정정 뒤 모든 휴게가 `출근 ≤ 시작 < 종료 ≤ 퇴근`이고 서로 겹치지 않아야 한다. 문제가 된 휴게 ID를 오름차순으로 `breakIds`에 담는다.
- **근태 version**: 출퇴근 정정은 `correct()`로, 휴게 정정은 기존 `touch()`로 근태 version을 하나 올린다. M8 근태 엔터티는 version을 손으로 세므로 같은 방식을 따랐다.
- 정정 엔터티 상태는 기존 `AttendanceCorrection.Status`를 유지했다. AI 분석 질의가 필드 이름과 enum을 문자열로 참조하고 있어서다.

## 4. 구현 내용

- **엔터티**
  - `AttendanceCorrection`: 쓰기 가능 매핑(IDENTITY, `@Version`, 생성 시각 DB 기본값), 종류별 생성 팩토리(`clock`, `pause`), `approve`·`reject`·`withdraw`.
  - `AttendanceRecord.correct(출근, 퇴근)`, `AttendanceBreak.correct(시작, 종료)`. 진행 중 근태·열린 휴게는 정정하지 않는다.
- **저장소**
  - `AttendanceCorrectionRepository`(`locked`, 근태별 대기 정정 잠금 읽기).
  - `AttendanceRecordRepository`에 `locked`, `shared`, 겹칠 수 있는 근태 조회(`meeting`)를 더했다.
  - 목록 질의는 `LeaveSearchRepository.corrections`다. 근태 기록 지점으로 조회 범위를 판정하고, `from`·`to`는 근태의 현재 출근 시각(Asia/Seoul 날짜)으로 거른다.
- **서비스·컨트롤러**: `CorrectionService`, `AttendanceCorrectionController`(오류 핸들러는 #108에서 겪은 문제 때문에 컨트롤러 안에 둔다), `AttendanceConflicts`.
- **409 대상 정보**: `WorkforceRequestRefusal`과 `WorkforceRequestConflictError`에 `attendanceConflicts`, `breakIds`, `pendingCorrectionId`를 더했다.
- **계약**
  - OpenAPI: 정정 operation 6개를 `implemented`로 바꿨다. `AttendanceCorrectionReject`, `AttendanceCorrectionDecision` 스키마를 추가하고 `info.description`에 구현 문장을 더했다.
  - 계약 문서: 머리말, 공통 규칙(사유 예외), 반려 절, 응답 절, 범위 밖, 결정 표를 고쳤다.
  - 계약 테스트 `WorkforceRequestsContractTests`: 모든 M12 operation이 `implemented`인지, 정정 반려 본문·결정 스키마를 단언한다.
- **테스트 정리**: `ShiftIntegrationSupport.clean`이 근태·휴게보다 먼저 정정 행을 지운다(외래 키).

## 5. 검증 결과

- `CorrectionIntegrationTests` 8개 통과(MySQL Testcontainers, HTTP·DB). 인수 조건별로 다음을 확인했다.
  1. 본인의 퇴근한 근태만 신청: 관리자 대리 403, 다른 직원 404, 퇴근 전 409 `CORRECTION_TARGET_OPEN`, 대기 정정 409 `CORRECTION_PENDING`(`pendingCorrectionId`). 시각 규칙 422(순서, 24시간 초과, 초 미만, 미래)와 변경 없음 422를 확인했다. 같은 키 재요청은 같은 행을 재생하고 다른 입력은 409 `IDEMPOTENCY_CONFLICT`다. 신청 뒤 근태 값은 그대로다.
  2. 승인·반려 권한: 일반 직원 403, 볼 수 없는 직원·다른 지점 관리자 404, 본인 신청 403 `SELF_DECISION_FORBIDDEN`, OWNER는 승인 가능, `canApprove` 표시.
  3. 겹침·휴게 위치: 신청 때 앞 근태와 겹침 409(보이는 대상은 ID 포함), 반열린 구간 경계는 겹침이 아님, 승인 때 다른 지점 근태 두 개가 `visible=false` 한 항목으로 합쳐짐, 휴게가 근무 밖이거나 다른 휴게와 겹치면 `breakIds`를 담은 409.
  4. 승인 반영: 출근 시각 변경, 근태 version +1, M8 상세의 `workedMinutes`가 480 → 510으로 바뀜, DB에 원본·정정 값·승인자가 남음. 승인 뒤 새 휴게 정정은 바뀐 값을 원본으로 저장하고 승인되면 540분이 된다.
  5. 반려: 사유 누락·공백 422, 응답과 상세의 `decision.reason`, `audit_events` 행(처리자·지점·사유) 확인, 근태 값·version 그대로, 다시 승인하면 409 `INVALID_STATE`(`latestVersion`, `reloadUrl`).
  6. 취소: 관리자 403, 다른 직원 404, 낡은 version 409 `VERSION_CONFLICT`, 승인된 정정 취소 409 `INVALID_STATE`, 정정에는 승인 취소 경로가 없음(404).
  7. 동시 처리: 관리자·OWNER가 승인 2건·반려 2건을 동시에 보내면 하나만 200이고 나머지는 409 `INVALID_STATE`다. 근태 version은 승인이 이겼을 때만 한 번 오른다. 이어서 같은 승인을 다시 보내도 근태는 그대로다.
  - 목록: 관리 지점 전체, 본인 `mine`, 같은 지점 다른 직원·다른 지점 관리자에게는 빈 목록, `attendanceRecordId`·`from`/`to`·`status` 필터, 소속 회수 뒤 본인 상세 404.
- 아키텍처 테스트 91개(계약 테스트 포함) 통과.
- `npx --no-install @apidevtools/swagger-cli validate docs/design/foundation/openapi.json`: valid. 파일 형식(2칸 들여쓰기, LF, BOM 없음)은 원본과 같은 직렬화로 유지했다.

### 전체 테스트

- `./gradlew test`: BUILD SUCCESSFUL(18분 2초). 1,069개 실행, 실패 0, 건너뜀 26(기존과 같은 건너뜀 수).

### 검증하지 못한 항목

- 실제 운영 DB·배포 환경에서의 동작은 확인하지 않았다.
- Frontend 화면 연동은 확인하지 않았다.
- `REQUEST_IN_PROGRESS`(같은 키의 동시 신청)는 기존 멱등 저장소 동작을 그대로 쓰며, 정정 전용 테스트는 쓰지 않았다.

## 6. 남은 작업 / 보류 사항

- **범위 밖 발견(고치지 않음)**: AI 분석(`OpenStudyAnalyses`의 근무시간 집계)은 승인된 `CLOCK` 정정의 `after_*` 값을 근태 원본 위에 덮어써 "보정 후" 시간을 계산한다. 정정이 근태를 바꾸지 않던 시절의 가정이다.
  - #107 이후에는 승인 때 근태 자체가 바뀌므로 "원본"과 "보정 후"가 같아진다.
  - 한 근태에 승인된 출퇴근 정정이 여럿이면 질의 순서에 따라 오래된 정정 값으로 덮어쓸 수 있다.
  - 별도 이슈로 다루는 것이 맞다고 판단해 사용자에게 알렸다.
- 휴게 구간 추가·삭제 정정, 관리자 대리 정정은 계약의 범위 밖 그대로다.
