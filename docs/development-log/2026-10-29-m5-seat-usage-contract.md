# 2026-10-29 M5 좌석·입퇴실·이용권 차감 API 계약

관련: Issue [#60](https://github.com/OpenStudyService/OpenStudy-Backend/issues/60), [계약 문서](../design/foundation/seat-usage-api.md), [ADR-M5-001](../db/ADR-M5-001.md), [ADR-M4-002](../db/ADR-M4-002.md), [M5 정책 기록](2026-10-28-m5-seat-usage-policy.md), [M4 계약 기록](2026-10-29-m4-customer-sales-contract.md).

## 1. 배경 / 문제

M5의 정책(M5-D01~D08)은 ADR-M5-001로 확정됐지만 API 계약이 없었다. 좌석(#61), 입퇴실·차감(#62), 이동(#63), 조정·내역(#64)과 Frontend가 같은 기준으로 병행하려면 operation, DTO, 오류, 권한, 지점 범위, 요청 키, 트랜잭션 경계를 먼저 고정해야 했다. M4와 같이 계약을 먼저 두는 방식이다.

V1 스키마에는 `seats`, `seat_sessions`, `seat_session_moves`, `pass_balance_events`, `customer_service_interactions`가 이미 있다. 이슈 범위에서 스키마 변경은 제외되어 있으므로, 계약은 이 열들로 표현할 수 있는 것만 정했다.

## 2. 검토한 선택지

- **시간권·정액권이 이용 중 만료될 때의 계산**: ADR은 기간권(M5-D03)만 정했고 다른 유형은 정하지 않았다. 사용자에게 두 안을 물었다.
  - 만료 후 분을 초과량으로 기록한다(기간권과 같은 규칙).
  - 입실 때 유효했으니 전체를 잔량에서 차감한다. 이미 만료된 이용권의 잔량이 줄어드는 기록이 생긴다.
- **정확 일치 조회의 요청 방식**
  - `GET` + 쿼리: 연락처가 URL·접근 로그·브라우저 기록에 남는다.
  - `POST` + 본문: 조회 기록을 남기는 동작이기도 해서 이쪽을 택했다.
- **배치도**: V1 `seats`에는 좌표 열이 없다.
  - 좌표 열을 추가한다: 이슈 범위(스키마 변경 제외)를 벗어난다.
  - 구역 묶음과 좌석 번호 순서로 그린다: 이쪽을 택했다.
- **배치도 목록의 페이지**
  - 공통 목록 규칙(pageSize 최대 100)을 그대로 쓴다: 좌석이 100개를 넘는 지점의 배치도를 한 화면에 그릴 수 없다.
  - 한 지점 전체를 돌려주는 별도 operation(`GET /seats/board`)을 둔다: 이쪽을 택했다. 일반 목록은 공통 규칙을 그대로 따른다.
- **입실·퇴실 권한**
  - 별도 행위 권한을 요구한다: 기본 프리셋(Q5)의 일반 직원은 현장 응대를 못 한다.
  - 해당 지점의 유효 소속으로 허용한다: 이쪽을 택했다. M4 계약이 "현장 응대(M5 입실)는 지점 직원 누구나의 업무"라고 이미 적어 둔 근거와 같다. 좌석 이동은 FR-SEAT-17·#63이 `operations:write`를 요구한다.

## 3. 결정 및 이유

- **만료 걸침(사용자 결정, 2026-10-29)**: 시간권·정액권도 만료 후 이용분은 차감하지 않고 `overage_minutes`로 남긴다.
  - 기간권과 규칙이 같아져 "이용권으로 처리되지 않은 이용 = 초과량" 하나로 설명된다.
  - 만료를 걸친 1분 미만은 만료 전으로 센다(`C = ceil((E − in) ÷ 1분)`). 입실 때 유효했던 이용권이므로 고객에게 불리하지 않은 쪽이다.
- **차감 계산**은 ADR 문구(분 올림, `ceil(분 × 요율 ÷ 60)`)를 공식과 예시 8개로 풀었다. 잔량 경계, 소진, 정액권 반올림 두 가지, 만료 걸침, 0초 이용을 포함한다.
- **이중 차감 방지**: 퇴실 `USE` 원장의 `event_key`를 `USE-{이용 기록 ID}`로 고정했다.
  - 멱등 기록이 만료되거나 서비스 로직에 결함이 있어도, 이용 기록 하나에 차감 원장은 DB UNIQUE로 하나뿐이다.
  - 다른 요청 키는 M4와 같이 접두어 + SHA-256 해시로 저장한다.
- **차감이 0이면 원장 행을 만들지 않는다**: 기간권, 0분 이용, 잔량이 이미 0인 경우다. 기간권의 사용 이력은 ADR-M4-002대로 `seat_sessions` 존재로 판정하므로 영향이 없다.
- **정확 일치 조회 기록**: `customer_service_interactions`는 (`branch_id`, `employee_id`)가 `branch_memberships`를 참조한다.
  - 조회 지점에 소속 행이 없는 OWNER는 이 표에 쓸 수 없으므로 `audit_events`에 남긴다. ADR에 없던 경우라 계약에서 정했다.
  - 일치 없는 조회는 조회 값 원문 없이 방식만 남긴다. 존재하지 않는 사람의 연락처를 저장하지 않기 위해서다.
- **결과 상한 10명**: 연락처 중복을 막지 않으므로 여러 명이 나올 수 있다. 정확 일치라 정상 업무에서 10명을 넘을 일은 없다고 보고, 대량 노출을 막는 상한으로 정했다.
- **수동 조정**은 증감량이 아니라 조정 후 값과 version을 받는다.
  - 화면에서 보고 있는 값을 기준으로 고치는 업무다. 그사이 퇴실로 잔량이 바뀌면 version 충돌로 알린다.
  - 유효기간만 바꾼 조정도 `ADJUST` 행(증감 0)을 남긴다. #64의 "조정 전후 값이 원장과 감사에 남는다"를 만족하기 위해서다. 원장 표에 만료 시각 열이 없어서 전후 시각은 감사에 남긴다.
- **운영 중단·재개 권한**: #61은 "해당 지점 MANAGER만"이라고 적었다. 기존 계약의 MANAGER 규칙("`role=admin`과 명시한 행위 권한을 모두 만족", 지점 운영 상태 변경 = MANAGER + `operations:write`)에 맞춰 MANAGER + `operations:write`로 정했다. #61 구현 때 이 차이를 확인해야 한다.
- **운영 중지 지점**: 입실과 이동은 409, 퇴실과 조회는 허용한다(FR-BR-08 "진행 중 이용의 종료·정산 허용"). 이동은 새 좌석 배정으로 봤다.
- **다른 지점에서 이용한 기록이 고객 내역에 나올 때**: 원장 합계가 현재 잔량과 맞아야 하므로 증감량은 담는다. 이용 지점이 요청자 scope 밖이면 지점 ID는 null로 숨긴다. 다른 지점 이력 요약은 M6(POL-01)이다.
- OpenAPI 버전을 `0.1.6-m5-seat-usage`로 올렸다. `WorkspaceEvent.resource`에 `seats`·`seat-sessions`를 추가했다.

## 4. 구현 내용

- `docs/design/foundation/seat-usage-api.md` 신규
  - 제공 범위(15개 operation과 담당 이슈), 지점 범위
  - 좌석·배치도·현황(점유율 반올림과 "해당 없음")
  - 정확 일치 조회·기록, 입실 검증 순서
  - 퇴실 계산 규칙·예시·원장, 이동, 입퇴실 이력, 수동 조정, 고객 내역
  - 멱등 키, 트랜잭션 경계, 오류 코드, 제외 범위
- `openapi.json`: 위 15개 operation(`x-implementation-status: design-target`, `x-implementation-issue`)과 schema 20여 개를 추가하고, `seats`·`seat-sessions`·`customer-lookups` 태그를 추가했다. 기존 operation은 바꾸지 않았다.
- `docs/design/foundation/README.md`에 계약 링크를 추가했다.
- `SeatUsageContractTests` 신규
  - operation 15개의 오류 응답과 계약 문서 경로 표기
  - 쓰기 5개의 요청 키 필수·같은 키·다른 입력 409
  - 입실·퇴실·이동 요청에 차감·분·금액 필드 없음
  - 정확 일치 조회가 쿼리 파라미터를 받지 않고, 응답이 최소 정보만 담음
  - 구매·이용 지점 구분과 이벤트 resource

## 5. 검증 결과

- `npx @apidevtools/swagger-cli@4.0.4 validate docs/design/foundation/openapi.json`: valid.
  - 처음에는 퇴실 요청 schema의 빈 `required` 배열이 OpenAPI 3.0 규칙에 걸렸고, 빈 배열을 빼서 해결했다.
- 계약 문서 "제공 범위" 표와 OpenAPI의 M5 operation 목록을 스크립트로 대조했다. 양쪽 모두 15개로 일치했고, operation이 쓰는 오류 코드가 모두 문서 표에 있다.
- OpenAPI 변경은 추가만 있다(`git diff --diff-algorithm=histogram` 기준 2,553줄 추가, 3줄 변경: 버전·설명·이벤트 resource).
- `./gradlew test --tests "com.openstudy.architecture.*"`: `SeatUsageContractTests` 5개를 포함해 모두 통과.
- 전체 테스트(`./gradlew test`): 293개 중 288개 통과. 실패 5개는 `LocalPreviewIntegrationTests`다. 원인은 로컬 `.env`의 미리보기 비활성 설정이며, #56~#58 기록과 같은 환경 원인으로 이번 변경과 무관하다.
- `scripts/validate_foundation_design.py`는 이번 변경 전 `main`에서도 실패한다. Q1~Q12 정책 확인 항목과, 모든 operation이 `design-target`이어야 한다는 가정이 M4 구현 이후 맞지 않는다. 이번 범위에서 고치지 않았고, 대신 Java 계약 테스트로 확인했다.

### 검증하지 못한 것

- 계약이므로 실제 서버 동작은 없다. 계산 예시는 손으로 계산했다. #62 구현 테스트에서 같은 값으로 확인해야 한다.
- Frontend 측 계약 검토는 하지 않았다.

## 6. 남은 작업 / 보류 사항

- #61~#64 구현. 이 문서의 operation을 `implemented`로 바꾸는 일은 각 구현 PR에서 한다.
- 운영 중단 권한(MANAGER + `operations:write`)이 #61 인수 조건("MANAGER만")과 같은 뜻인지 #61 착수 때 확인한다.
- 좌석 자유 배치 좌표, 좌석 번호 자연 정렬 열은 필요해지면 스키마 변경과 함께 후속에서 다룬다.
- 정확 일치 조회 횟수 제한(M5-D08)은 후속 이슈다.
- `validate_foundation_design.py`를 M4·M5 계약에 맞게 갱신하는 일은 별도 작업이다.
