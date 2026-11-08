# 2026-11-09 휴가 부여·신청·승인·반려·취소·잔여량 구현 (#108)

## 1. 배경 / 문제

- M12 계약(#106, `workforce-requests-api.md`)이 확정되어 휴가 operation 13개를 구현한다.
  - 대상: 부여·보정·잔여량 6개, 신청·승인·반려·신청 취소·승인 취소 7개
- M8은 승인 휴가를 근무 충돌 검사에 쓰고 있었다. 하지만 휴가를 만드는 API가 없어 테스트가 행을 직접 넣었다.
- 휴가 신청에는 지점이 필요하다(M12-D02). V1 `leave_requests`에는 그 칼럼이 없어 마이그레이션이 필요했다.

## 2. 검토한 선택지

- **기존 행이 있을 때의 V11**
  - 기본 지점을 추측해 채우는 안
  - 실패시키는 안(선택): 계약이 "조용히 지점을 추측하지 않는다"고 정했다.
  - NOT NULL 칼럼과 외래 키를 한 `ALTER`로 추가했다. 행이 있으면 새 칼럼이 0이 되어 외래 키에서 실패하고, InnoDB가 문장 전체를 되돌린다.
- **409 대상 정보의 응답 방식**
  - 공통 `ApiException`에는 `conflicts`·`requiredDays` 같은 필드가 없다.
  - `@RestControllerAdvice(assignableTypes=...)`로 처리하는 안을 먼저 시도했다. 그러나 전역 `ApiExceptionHandler`의 `@ExceptionHandler(Exception.class)`가 먼저 잡아 500이 됐다.
  - M8 `ShiftController`와 같이 컨트롤러 안의 `@ExceptionHandler`로 바꿨다(선택). 컨트롤러 안의 핸들러가 advice보다 우선한다.
- **직원 잔여량 경로의 권한**
  - 공통 인터셉터(`AuthorizationWebConfiguration`)는 `/api/employees/**` 전체에 `MANAGE_PEOPLE`(MANAGER + `people:manage`)을 요구한다. 그래서 `people:manage`가 없는 MANAGER가 계약의 `GET /employees/{employeeId}/leave-balance`를 403으로 받았다.
  - 경로를 바꾸는 안: 이미 병합된 계약을 바꿔야 한다.
  - 인터셉터에서 그 경로만 `READ`로 분류하고 판정을 휴가 서비스에 맡기는 안(선택): 계약을 지키고 변경이 한 줄이다.

## 3. 결정 및 이유

- 계약과 ADR-M12-001(M12-D02·D03·D06·D07), 2026-11-09 사용자 결정 두 건을 그대로 구현했다.
  - 본인 신청의 본인 처리 금지
  - 휴가 승인 취소는 시작일 전까지
- **계약에 더한 것: 승인 때 다른 승인 휴가와의 겹침 검사**(409 `LEAVE_OVERLAP`)
  - 이슈 인수 조건("다른 승인 휴가와 겹치면 거부")을 따랐다. 신청 때 이미 막지만, 직접 기록된 행이나 동시 처리에 대비해 승인 때 다시 확인한다.
  - `overlappingRequestIds`에는 승인자가 볼 수 있는 신청만 담는다.
  - 계약 문서와 OpenAPI에 반영했다.
- **잠금**
  - 모든 명령은 공통 가드 → 신청자(또는 부여 대상) 직원 행 `FOR UPDATE` → 신청·부여 행 순서로 잠근다. M8 근무 등록과 휴가 승인이 같은 직원 행에서 직렬화된다(M12-D03).
  - 반려·신청 취소도 같은 틀을 써서 잠금 순서를 하나로 유지했다. 계약 문서의 "신청 행만 잠근다"는 문장은 이에 맞게 정정했다.
- **중복 반영 방지(M12-D07)**
  - 상태(version) 검사가 첫 방어선이다.
  - 원장 `event_key` 유일 키(`leave:{id}:use:{grantId}`, `leave:{id}:restore:{grantId}`)가 마지막 방어선이다. 위반이 나면 409 `INVALID_STATE`로 돌려준다.

## 4. 구현 내용

- `V11__leave_request_branch.sql`: `leave_requests.branch_id`(NOT NULL, 외래 키), 인덱스 `(branch_id, starts_on)`
- 엔터티
  - `LeaveRequest`: M8의 읽기 전용 매핑을 전체 매핑으로 확장했다.
  - 새 엔터티: `LeaveGrant`, `LeaveAllocation`, `LeaveBalanceEvent`
- 저장소
  - 잠금 조회(`locked`, `usable`, `lockedAll`, `lockedFor`)
  - 겹침 조회(`overlapping`)
  - 잔여량 조회 권한용 활성 소속 지점(native)
  - 목록 `LeaveSearchRepository`: 관리 지점 ∪ 본인 행. 본인 행은 소속과 무관하다.
- `LeaveService`
  - 부여·보정·잔여량, 신청·승인·반려·신청 취소·승인 취소
  - 승인은 만료일이 빠른 부여부터 차감하고 `leave_allocations`와 `USE` 원장을 남긴다.
  - 승인 취소는 같은 부여로 복원하고 `RESTORE` 원장을 남긴다.
  - 근무 충돌은 M8 `ShiftConflicts`를 재사용하고, 볼 수 없는 지점의 근무는 숨긴다.
- `LeaveController`: operation 13개와 `WorkforceRequestConflictError` 핸들러
- `AuthorizationWebConfiguration`: `/api/employees/{id}/leave-balance`를 `MANAGE_PEOPLE`에서 제외
- 테스트
  - `LeaveIntegrationTests` 12개
  - `FoundationMigrationTests`에 V11 테스트 2개 추가(빈 표 성공, 기존 행이 있으면 거부·무변경)
  - 마이그레이션 번호를 고정한 테스트 3개를 11로 맞췄다.
  - `ShiftIntegrationSupport.leave()`가 지점을 넣고, 새 휴가 표를 정리하도록 고쳤다.
- 문서·계약
  - OpenAPI 휴가 operation 13개를 `implemented`로 바꾸고 `LEAVE_OVERLAP`을 추가했다.
  - 계약 문서에 구현 표시와 승인 검사 순서를 반영했다.
  - 계약 테스트의 상태 단언을 바꿨다.

## 5. 검증 결과

### 통합 테스트(Testcontainers MySQL)

`LeaveIntegrationTests` 12개 통과. 이슈 인수 조건과 대응은 다음과 같다.

| 인수 조건 | 테스트에서 확인한 것 |
|---|---|
| 부여는 원부여 지점 MANAGER만, 0.5일 단위·기간·사유 기록 | 직원·다른 지점 MANAGER 403, 0.3일·0일·기간 역전 422, 비소속 대상 422, 원장 `GRANT`, 멱등 재생·다른 입력 409, 운영 중지 409 |
| 신청은 본인 활성 소속 지점, 0.5일·달력 일수 이하·같은 연도, 신청만으로 잔여 불변 | 다른 지점·OWNER 403, 3.5/3일·0.3일·역전·연도 넘김 422, 잔여 부족 409, 신청 후 잔여 그대로, 겹침 409 |
| 승인·반려·승인 취소는 신청 지점 MANAGER만 | 동료·다른 지점 MANAGER 404, 직원 403, MANAGER 본인 신청 403 `SELF_DECISION_FORBIDDEN`, OWNER 승인 |
| 잔여 부족·다른 승인 휴가·예정 근무와 겹치면 거부하고 대상 반환 | `LEAVE_SHIFT_CONFLICT`(보이는 근무 ID, 다른 지점 근무 숨김), `LEAVE_OVERLAP`, `LEAVE_BALANCE_INSUFFICIENT`(필요 2, 가용 1.5) |
| 만료일 빠른 부여부터 차감·배분 기록, 사용 한 번 | 12-31 부여 1일 → 다음 해 3-31 부여 1일 순서, `USE` 2행, 재승인 409에도 원장 불변 |
| 승인 취소는 같은 부여로 한 번만 복원 | 복원 후 잔여 원복, 재요청 409, 동시 4건 중 1건만 200·`RESTORE` 1회 |
| 반려는 잔여 불변 | 반려·신청 취소 후 잔여 그대로, 배분 없음 |
| 잔여 = 원장 합계, 본인·관리 범위 조회 | 모든 테스트의 원장 합계 비교, 관리자 조회·다른 지점 MANAGER 404·동료 404 |
| 승인 휴가가 M8 근무 등록에 걸림 | 승인 뒤 같은 날 근무 등록 409 `SHIFT_CONFLICT`(휴가 항목), 승인 취소 뒤 등록 201 |
| V1 무수정, 새 SQL만 | V11 추가, 마이그레이션 테스트 |

- 동시 승인: 잔여 2일 부여에 2일 신청 두 건을 서로 다른 관리자가 동시에 승인했다. 한 건만 200, 다른 건은 409 `LEAVE_BALANCE_INSUFFICIENT`, 잔여는 0이다.
- 시작일 당일 승인 취소는 409 `LEAVE_ALREADY_STARTED`다.

### 회귀

- workforce·authorization·employee 모듈, 마이그레이션, 아키텍처·계약 테스트 211개 통과
- 아키텍처 테스트는 상태 갱신 뒤 91개를 다시 실행해 통과했다.
- `swagger-cli validate`: valid(#193 이후 파일 전체가 유효)
- 전체 `./gradlew test`: 1,053개 중 실패 0, 건너뜀 26(로컬 전용 AI 25, 조건부 측정 1). BUILD SUCCESSFUL(18분 17초)
- `docs/db/schema-full.sql`·`erd.mmd`를 V11 기준으로 다시 추출했다. 변경은 `leave_requests`의 지점 칼럼·인덱스·외래 키(외래 키 순서로 테이블 위치 이동), Flyway 이력 11, 추출 시각뿐이다. 추출 테스트의 복원·Flyway 검증이 통과했다. `docs/db/README.md`의 V10 표기를 V11로 고쳤다.

### 검증하지 않은 것

- 실제 브라우저·Frontend 화면 연동
- 운영 DB에서의 V11 적용(운영 DB에 휴가 행이 없다는 전제)
- 날짜 경계는 테스트 실행일 기준이다. 승인 취소 가능 여부는 서버 시각(Asia/Seoul)으로 판정하며, 시각을 고정한 테스트는 하지 않았다.

## 6. 남은 작업 / 보류 사항

- 근태 정정(#107), 대체근무(#109) 구현
- V11은 임시 번호다. 병합 직전에 main의 최신 번호 + 1로 맞춘다. 이때 번호를 고정한 테스트 3개와 `FoundationMigrationTests`의 V11 테스트(`flyway("10")`)도 함께 바꾸고 `docs/db/schema-full.sql`을 다시 추출한다.
- M8 근무 충돌 응답의 `kind=휴가` 항목은 휴가에 지점이 생겼어도 계약대로 숨긴 채 두었다(계약 "범위 밖").
