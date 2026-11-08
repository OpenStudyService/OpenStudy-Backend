# 2026-11-09 M12 근태 정정·휴가·대체근무 API 계약 (#106)

## 1. 배경 / 문제

- M12 정책(ADR-M12-001, M12-D01~D09)은 2026-10-28 확정됐지만, 근태 정정·휴가·대체근무의 API 설계가 없었다.
- 구현 이슈 세 개(#107 정정, #108 휴가, #109 대체근무)와 Frontend가 같은 계약으로 병행하려면 다음을 먼저 고정해야 했다.
  - operation, DTO, 상태 전이, 권한
  - 오류의 대상 정보, 반복·동시 요청의 응답
- 이슈 범위는 계약(문서·OpenAPI·계약 테스트)이다. 서버 구현과 스키마 변경은 제외한다.

## 2. 검토한 선택지

- **본인 신청의 처리**: MANAGER가 자기 신청을 승인할 수 있게 할지. ADR이 정하지 않아 사용자에게 물었다.
  - 금지: 견제가 생기고 OWNER가 항상 처리할 수 있다.
  - 허용: 혼자 운영하는 지점에서 편하다.
- **휴가 승인 취소 시점**: ADR은 승인 취소를 허용하지만 시점을 정하지 않아 사용자에게 물었다.
  - 시작일 전까지
  - 언제든(지난 휴가 포함)
- **승인 취소 명령의 모양**
  - 신청 취소와 같은 경로에서 상태에 따라 효과를 바꾸는 안
  - 별도 경로(`cancel-approval`, 선택): 권한(본인/관리자)과 효과(잔여량·근무 복원)가 달라 별도 경로가 오류와 권한 표현이 명확하다.
- **대체근무 승인의 버전 불일치**: 이슈 인수 조건이 "대체근무 버전 불일치의 대상 정보"를 요구한다.
  - V1 `shift_swaps`에는 신청 당시 근무 version이 없다.
  - 신청 version만 받는 안: 신청 뒤 관리자가 근무를 고쳐도 승인이 덮어쓴다.
  - 승인 때 근무 version(`shiftVersion`)도 받는 안(선택): 관리자가 본 근무와 실제가 다르면 409 `SHIFT_VERSION_CONFLICT`로 근무 ID·최신 version·다시 읽을 경로를 준다.
- **409 오류 본문**
  - 코드마다 별도 스키마를 두는 안
  - 하나의 `WorkforceRequestConflictError`에 code별 선택 필드를 두는 안(선택): operation 하나가 여러 409 코드를 내므로 OpenAPI 응답이 하나로 단순해진다. 휴가 승인의 근무 충돌은 M8의 `ShiftConflict`를 그대로 재사용한다.

## 3. 결정 및 이유

- **2026-11-09 사용자 결정**
  - 본인 신청은 본인이 승인·반려·승인 취소할 수 없다(403 `SELF_DECISION_FORBIDDEN`).
  - 휴가 승인 취소는 시작일(Asia/Seoul) 전까지만 허용한다(409 `LEAVE_ALREADY_STARTED`).
- 그 밖에 ADR이 정하지 않은 항목은 계약 문서의 "정책이 정하지 않아 이 계약에서 정한 것" 표에 근거와 함께 적었다. 리뷰에서 조정할 수 있다. 주요 항목은 다음과 같다.
  - 정정 대상은 퇴근한 근태만, 근태당 대기 정정 하나
  - 대체근무는 근무 시작 전에만 신청·승인·승인 취소
  - 처리 사유는 받지 않음(V1 칼럼 없음)
  - 신청 수정 API 없음(취소 후 재신청)
  - 휴가는 소속과 무관하게 본인이 조회(잔여량이 직원 단위, M12-D02)
- 생성 명령 네 개(부여·휴가 신청·정정 신청·대체근무 신청)는 `Idempotency-Key`가 필수다. 나머지 상태 전이는 `version`과 상태 검사로 처리하고, 검사 순서는 M8과 같이 상태 → version이다.

## 4. 구현 내용

- `docs/design/foundation/workforce-requests-api.md`: 계약 문서
  - operation 26개, 권한, 조회 범위, 세 신청 공통 상태 전이표
  - 추가 오류 코드 18개와 대상 정보
  - 기간 규칙 예시, 차감 배분, 승인·승인 취소 검사 순서
  - 요청 키·동시성·잠금 순서, 스키마 영향, 범위 밖
- `docs/design/foundation/openapi.json`
  - 버전 `0.1.13-m12-workforce-requests`
  - operation 26개(전부 `design-target`, 구현 이슈 #107·#108·#109 표시), 태그 3개
  - 스키마 26개(`LeaveGrant`, `LeaveRequest`, `AttendanceCorrection`, `ShiftSwap`, `WorkforceRequestConflictError` 등)
  - 기존 operation·스키마는 바꾸지 않았다. `info.version`, `info.description`(문장 추가), `tags`(추가)만 바뀌었다.
  - 2칸 들여쓰기·LF·BOM 없는 UTF-8 형식을 유지했다. 원본을 같은 방식으로 다시 쓴 결과가 원본과 바이트 단위로 같음을 먼저 확인했다.
- `WorkforceRequestsContractTests`(7개): 다음을 대조한다.
  - operation 목록·오류 응답·문서 표기
  - 버전·구현 이슈
  - 상태 전이표와 승인 취소 유무
  - 권한과 본인 처리 금지
  - 충돌 오류의 대상 필드, 멱등 키·version
  - 휴가 규칙
- `WorkforceContractTests.leaveSwapsAndCorrectionsStayOutOfThisContract`: "명세 전체에 휴가·대체근무·정정 경로가 없다"를 "그런 경로는 모두 M12 구현 이슈(#107~#109)에 속하고 M8 operation에는 없다"로 바꿨다. M12 계약 이전의 범위 검사였고, 그대로 두면 이 계약이 실패한다.
- `docs/design/foundation/README.md`: 안내 한 단락

## 5. 검증 결과

- `./gradlew test --tests 'com.openstudy.architecture.*'`: 90개 통과(새 계약 테스트 7개 포함). 처음 실행에서 M8 범위 테스트 1개가 실패해 위와 같이 고쳤다.
- OpenAPI 파일을 읽는 통합 테스트: `ProfileIntegrationTests`(10), `RoomReservationIntegrationTests`(40), `SalesConditionIntegrationTests`(8) 통과
- `swagger-cli validate`
  - 그대로 검증하면 `AiExecution must match exactly one schema in oneOf`로 실패한다.
  - main(`0.1.12-m10-ai-chat`)도 같은 오류로 실패한다. M10 계약(#92)이 OpenAPI 3.1 문법(`type: ["string","null"]`, `{"type": "null"}`)을 3.0.3 문서에 넣은 것이 원인이다.
  - 임시 사본에서 이 32곳만 3.0 형식(`nullable`)으로 바꿔 검증하니 valid였다. 이번 추가분에는 다른 오류가 없다.
  - 기존 문제는 이 PR에서 고치지 않았다.
- 전체 테스트는 실행하지 않았다. 이번 변경은 문서·OpenAPI·아키텍처 테스트뿐이다.

### 검증하지 않은 것

- 요구사항 ID의 operation별 대응
  - SRS 원본이 저장소 밖에 있어 FR-CORR·FR-LEAVE·FR-SWAP의 세부 문구를 대조하지 못했다.
  - 그래서 operation에는 해당 계열 ID 전체를 붙였다.
- Frontend 화면 흐름과의 대조

## 6. 남은 작업 / 보류 사항

- **구현**: #107(정정), #108(휴가, `leave_requests.branch_id` 마이그레이션 포함), #109(대체근무)
- **기존 문제**: OpenAPI의 M10 스키마가 3.0.3 검증을 통과하지 못한다. 별도 이슈로 다루는 것이 좋다.
- **범위 밖으로 둔 것**(계약 문서 "범위 밖")
  - 관리자 대리 신청
  - 휴게 추가·삭제 정정
  - 처리 사유 저장
  - 부여 기간 수정
  - M8 충돌 응답의 휴가 항목 공개 범위
  - 알림, 홈 요약의 대기 신청 수
