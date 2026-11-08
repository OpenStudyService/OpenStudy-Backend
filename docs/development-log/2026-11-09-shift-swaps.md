# 2026-11-09 대체근무 신청·승인·반려·취소와 원근무 복원 (#109)

## 1. 배경 / 문제

- M12 계약(#106)의 대체근무 operation 7개를 구현한다.
- 근무의 원래 담당자가 시작 전 본인 근무를 다른 직원에게 넘기고, 근무 지점의 MANAGER가 승인한다(M12-D04·D05).
- 승인 취소는 근무가 승인 직후 그대로이고 원래 담당자에게 새 충돌이 없을 때만 원래대로 되돌린다(M12-D09).
- M8 근무 엔터티는 "담당자는 바뀌지 않는다"(`employee_id` 갱신 금지)로 매핑되어 있었다. 대체근무가 이 원칙의 유일한 예외다.
- V1 `shift_swaps`에는 신청 당시 근무 version이 없다. 그런데 이슈 인수 조건은 "원근무의 버전·담당자가 신청 당시와 다르면 거부"를 요구한다.

## 2. 검토한 선택지

- **신청 당시 버전과의 비교**
  - 신청 version 칼럼을 추가하는 마이그레이션: 스키마 변경이 계약 범위 밖이다.
  - 승인자가 보낸 `shiftVersion`만 비교(계약 원안): 신청 뒤 근무가 바뀌었어도 승인자가 최신 version을 보내면 통과한다. 인수 조건을 다 채우지 못한다.
  - 신청 시각 이후 그 근무의 `shift_events`가 생겼는지 확인(선택): 근무의 모든 변경은 같은 트랜잭션에서 이력을 남기므로(FR-SHIFT-04) 스키마 없이 판정할 수 있다. 승인자 `shiftVersion` 비교, 담당자 = 신청자 확인과 함께 같은 409 `SHIFT_VERSION_CONFLICT`로 돌려준다.
- **승인 취소에 필요한 원래 값과 승인 직후 version**
  - `shift_events`의 `SWAPPED` 행을 시각으로 찾는 안: 승인 시각과 이력 시각이 어긋날 수 있어 불안정하다.
  - `after_json`에 `swapId`와 `swap`(`APPROVED`·`RESTORED`)을 담는 안(선택): 어느 대체근무의 행인지 정확히 찾는다. M8은 `SWAPPED`를 M12 몫으로 남겨 두었다.
- **목록 `from`·`to` 기준**: 계약 원안은 "원래 근무의 workDate"였다. 하지만 승인 뒤에는 근무 시각이 제안 시각으로 바뀌어 기준이 행마다 달라진다. 그래서 제안 시작 시각의 날짜로 정정했다.

## 3. 결정 및 이유

- 근무 엔터티의 `employee_id`를 갱신 가능하게 하고 `reassign(직원, 시작, 종료)`만 열었다. 일반 근무 수정 API(`PATCH /shifts/{id}`)는 기존대로 `employeeId`를 422 `IMMUTABLE_FIELD`로 거부한다.
- **잠금**: 공통 가드 → 신청자·대체 직원 행(ID 순) → 대체근무 행 → 근무 행. 근무 등록·휴가 승인·대체근무가 같은 직원 행에서 직렬화된다.
- **충돌 검사**: M8 근무 등록과 같다. 직원의 모든 지점 예정 근무와 승인 휴가를 대상으로 하고, 볼 수 없는 지점은 숨긴다. 409는 `SHIFT_CONFLICT`이고, 응답 본문은 계약의 `WorkforceRequestConflictError`(`conflicts`)다.
- **신청 취소**: 신청자 본인이면 근무가 시작했거나 소속이 회수돼도 허용한다(계약 "정리 동작").
- 휴가(#108)의 거부 예외 `WorkforceRequestRefusal`과 오류 응답에 `pendingSwapId`, `shiftId`, `approvedVersion`을 더해 재사용했다.

## 4. 구현 내용

- 엔터티·저장소
  - `ShiftSwap` 엔터티와 `ShiftSwapRepository`(잠금 조회, 근무별 대기 신청)
  - 목록 검색: `LeaveSearchRepository.swaps`. 근무 지점이 조회 가능하고, 관리 지점이거나 본인이 신청자·대체 직원인 행
- `Shift.reassign`, `ShiftService.values` 공유, `ShiftEventRepository.existsByShiftIdAndCreatedAtAfter`
- `SwapService`
  - 신청: 본인 근무, 시작 전, 대체 직원 소속, 대기 신청, 대체 직원 충돌을 확인한다.
  - 승인: 근무 version·담당자·신청 이후 변경, 소속, 충돌을 확인한다. 통과하면 담당자·시각을 함께 바꾸고 `SWAPPED`를 남긴다.
  - 반려, 신청 취소
  - 승인 취소: 승인 직후 version, 시작 전 여부, 원래 담당자 소속·충돌을 확인한 뒤 복원하고 `SWAPPED`를 남긴다.
- `ShiftSwapController`: operation 7개와 오류 핸들러
- `ShiftSwapView` DTO, 오류 DTO 필드 추가
- 테스트
  - `SwapIntegrationTests` 8개
  - `ShiftIntegrationSupport` 정리 단계에 `shift_swaps` 삭제 추가
- 문서·계약
  - OpenAPI 대체근무 operation 7개를 `implemented`로 바꾸고 승인·목록 설명을 고쳤다.
  - 계약 문서에 구현 표시, 승인 7단계, `SWAPPED` 표지, 목록 기준을 반영했다.
  - 계약 테스트의 상태 단언을 바꿨다.

## 5. 검증 결과

### 통합 테스트(Testcontainers MySQL)

`SwapIntegrationTests` 8개 통과.

| 인수 조건 | 테스트에서 확인한 것 |
|---|---|
| 원래 담당자만 본인 근무에 대해 신청, 승인 전 근무표 불변 | 관리자 403, 동료(근무를 못 봄) 404, 본인·비소속 대체 422, 휴게 규칙 422, 신청 후 담당자·이력 불변, 대기 신청 409(`pendingSwapId`), 시작한 근무 409 |
| 승인·반려·승인 취소는 근무 지점 MANAGER만 | 대체 직원·신청자 403, 다른 지점 MANAGER 404, 동료의 신청 취소 403, MANAGER 본인 신청 403 `SELF_DECISION_FORBIDDEN`, OWNER 승인 |
| 승인 시 버전·담당자 불일치, 대체 직원 비소속, 대체 직원 근무·승인 휴가 겹침이면 거부 | `shiftVersion` 불일치 409(`shiftId`·`latestVersion`·`reloadUrl`), 신청 후 근무 수정 시 최신 version으로도 409, 소속 회수 422, 신청 시 근무 겹침 409, 승인 시 승인 휴가 겹침 409(`kind=휴가`) |
| 승인되면 담당자·시간이 함께 바뀌고 `shift_events`에 남음 | 근무 상세의 직원·시작 시각 변경, `SWAPPED` 1행, `after_json.swapId` |
| 반려는 처리자·결과를 남기고 근무 불변 | `decision.by`, 담당자·이력 불변, 재승인 409 |
| 승인 취소는 근무 불변·원래 담당자 무충돌일 때만 복원, 아니면 409 | 원래 담당자 새 근무 → 409 `SHIFT_CONFLICT`, 근무 수정 → 409 `SWAP_RESTORE_CONFLICT`(`approvedVersion`·`latestVersion`) |
| 반복·동시 취소가 근무를 두 번 바꾸지 않음 | 동시 4건 중 1건만 200, 나머지 409 `INVALID_STATE`, 원래 담당·시각 복원, `SWAPPED` 2행(승인·복원) |
| 대체로 바뀐 근무가 M8 충돌 검사 기준 | 승인 뒤 대체 직원의 겹치는 근무 등록 409, 원래 담당자의 같은 시간 등록 201 |

- 목록: 관리자는 지점 전체를, 신청자는 `mine`으로 본인 것을, 대체 직원은 자기가 지정된 신청을 본다. 다른 직원과 다른 지점 관리자에게는 보이지 않는다.

### 회귀

- `LeaveIntegrationTests` 12, `ShiftIntegrationTests` 21, `ShiftConcurrencyIntegrationTests` 5 통과
- 아키텍처·계약 테스트 91개 통과
- `swagger-cli validate`: valid
- 전체 `./gradlew test`: 1,061개 중 실패 0, 건너뜀 26(로컬 전용 AI 25, 조건부 측정 1). BUILD SUCCESSFUL(16분 34초)

### 검증하지 않은 것

- Frontend 화면 연동
- 이력 시각 비교(신청 이후 변경)는 DB 시각(`CURRENT_TIMESTAMP(6)`)끼리 비교한다. 같은 마이크로초에 신청과 근무 변경이 겹치는 경우는 확인하지 않았다(직원 행 잠금으로 직렬화되어 실제로는 생기기 어렵다).

## 6. 남은 작업 / 보류 사항

- 근태 정정(#107) 구현
- 스키마 변경은 없다(V11 이후 추가 마이그레이션 없음).
