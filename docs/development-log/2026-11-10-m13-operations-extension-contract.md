# 2026-11-10 M13 운영·거래 확장 API 계약 (#111)

## 1. 배경 / 문제

- M13(지점 휴무일, 상품 판매 기간·한도, 중복 고객 병합, 일별 마감·조정, 결제·집계 고급 필터, 좌석 점유 추이)의 API 설계가 없었다. Backend 구현(#112~#116)과 Frontend 화면(#40~#42)이 같은 계약으로 병행하려면 계약을 먼저 확정해야 한다.
- 정책은 ADR-M13-001(M13-D01~D10)에 있다. 다만 다음 세 가지는 ADR이 정하지 않았고 계약의 모양을 바꾼다.
  - 마감 뒤 "마감된 날에 영향을 주는 변경"이 무엇인지
  - FR-AGG-15의 "이용 지점"을 매출에서 어떻게 다룰지
  - FR-MEM-08의 "중복 고객 의심"의 기준

## 2. 검토한 선택지

착수 전에 사용자에게 세 가지를 물었다.

- **마감 조정**: 환불은 항상 처리 시각(오늘)에 들어가므로, 처리일 기준으로는 지난날 수치가 바뀌지 않고 원결제일 기준으로만 바뀐다.
  - 지난날만 마감 + 원결제일 영향 환불을 조정 이력으로 자동 기록
  - 당일 마감 허용 + 마감 후 같은 날의 결제·환불까지 조정 기록(늦은 결제는 사유가 없다)
  - 지난날만 마감 + 별도 조정 사유 입력(기존 환불 API에 필드 추가)
- **이용 지점**: 매출은 판매 지점에 귀속되고, 이용권은 다른 지점 좌석에서도 쓴다.
  - 그 지점에서 쓴 이용권의 판매만 매출에 포함하는 필터
  - 이용 지점별 사용량 지표 신설
  - 이번 계약에서 제외
- **중복 기준**
  - 같은 연락처
  - 연락처 또는 이름+이메일
  - 이름+연락처
- **기존 operation 변경의 표기**: 이미 구현된 응답 schema에 필드를 바로 넣는 방법과, `x-planned-changes`에 두는 방법을 비교했다. 구현된 응답이 schema의 `properties`와 정확히 같은지 비교하는 통합 테스트(`SalesConditionIntegrationTests` 등)가 있어서, 바로 넣으면 이 PR에서 그 테스트가 실패한다.

## 3. 결정 및 이유

- **사용자 결정(2026-11-10)**: 모두 ADR에 M13-D11~D13으로 추가하고 변경 이력을 남겼다.
  - M13-D11 마감: 지난날만 마감한다. 두 환불 기준을 모두 저장하고, 원결제일 영향 환불을 조정 이력으로 자동 기록한다(추천안). 환불 API를 바꾸지 않고, 조정 대상이 원결제일 기준 한 가지로 명확해진다.
  - M13-D12 이용 지점: 매출 필터가 아니라 이용 지점별 이용권 사용량 지표(입실 수, 시간권 차감 분, 정액권 차감액)로 제공한다. 매출 합계의 의미가 바뀌지 않는다.
  - M13-D13 중복 기준: 같은 숫자 연락처(추천안).
- **판매 불가 판정 순서**: 기존 순서(판매 중지 → 판매 지점)를 유지하고 ADR 순서(판매 기간 → 남은 한도)를 그 뒤에 붙였다. 세 사유가 모두 다른 코드다.
- **판매 한도 동시성**: 한도가 있는 상품만 상품 행을 `FOR UPDATE`로 잠근다. 판매 수는 카운터 칼럼 없이 `승인` 상태 결제 수로 센다. 원거래와 카운터가 어긋날 일을 없애고, 환불하면 자연히 다시 팔 수 있게 한다.
- **마감과 환불의 동시성**: 마감 생성은 지점 행을 잠그고, 환불도 기존 `AuthorizedCommands` 순서에서 지점 행을 잠근다. 그래서 같은 지점의 마감과 환불이 차례로 처리되어, 스냅샷과 조정 이력 둘 다에서 빠지는 환불이 없다.
- **마감 생성 권한**: ADR의 MANAGER 이상에 `sales:read`를 더했다. 응답에 매출 금액이 담기기 때문이다. 기본 관리자 권한 묶음에는 `sales:read`가 있다.
- **고급 필터**: 결제 목록과 집계가 같은 query 이름(`methods`, `productKinds`, `categoryCode`, `operatorIds`, `processingBranchIds`)을 쓰도록 공통 parameter로 정의했다. 집계 응답은 `aggregation.condition`에 적용 필터를, `aggregation.conditionNotes`에 통계 의미가 바뀌는 조건의 안내를 담는다.
- **기존 operation 변경은 `x-planned-changes`**: 구현 이슈와 `design-target`을 붙여 두고, 구현 PR이 본 정의로 옮긴다. 새 operation 12개는 본 정의에 `design-target`으로 넣었다.
- 나머지 계약 판단은 계약 문서의 "정책이 정하지 않아 이 계약에서 정한 것" 표에 이유와 함께 적었다.

## 4. 구현 내용

- `docs/design/foundation/operations-extension-api.md`(신규): operation 12개, 기존 operation 변경, 오류 코드 10개와 대상 정보, 판매 판정 순서, 병합 결과·조회, 마감 스냅샷·조정 이력, 고급 필터와 정합성 규칙, 사용량 지표, 점유 추이, 스키마 영향, 범위 밖, 계약 판단 표.
- `docs/design/foundation/openapi.json` `0.1.14-m13-operations-extension`
  - 새 operation 12개(`design-target`, #112·#114·#115·#116)
  - 스키마 34개(`OperationsConflictError` 포함), 공통 parameter 6개
  - 지표 3개(`x-metric-definitions`)와 `MetricUnit`의 `분`
  - 기존 operation·schema의 `x-planned-changes` 30항목
- `docs/db/ADR-M13-001.md`: M13-D11~D13과 변경 이력
- `docs/design/foundation/README.md`: M13 계약 안내
- `OperationsExtensionContractTests`(신규, 6개): operation·오류·문서 대조, 계획된 변경, 판매 불가 코드 분리, 고급 필터 구조 공유, 권한, 버전
- `AggregationContractTests`: M9 지표 목록 검사에서 M13 지표를 뺐다. M13 지표는 새 테스트가 같은 항목을 검사한다.
- 스키마 마이그레이션은 없다. 테이블·칼럼 추가는 계약 문서의 스키마 영향 표대로 각 구현 이슈에서 한다.

## 5. 검증 결과

- `npx --no-install @apidevtools/swagger-cli validate docs/design/foundation/openapi.json`: valid
- OpenAPI 파일 형식(2칸 들여쓰기, LF)은 원본과 같은 직렬화로 유지했다. 실제 변경은 3,332줄 추가, 14줄 삭제다(`git diff --patience`).
- 아키텍처 테스트, `modules.sales`, `modules.reservation`, `ProfileIntegrationTests`(OpenAPI를 읽는 테스트): 217개 실행, 실패 0, 건너뜀 1
- 전체 `./gradlew test`(18분 17초): 1,076개 실행, 실패 1, 건너뜀 26
  - 실패: `RoomReservationIntegrationTests.simultaneousBookingsThatOverlapOnlyPartlyNeverDoubleBook`. 부분 겹침 동시 예약 중 하나가 409 대신 500(`INTERNAL_ERROR`)을 받았다.
  - 이 PR은 main 코드를 바꾸지 않았다. 같은 테스트는 앞선 범위 실행에서 통과했고, 단독으로 세 번 다시 돌려 모두 통과했다. 전체 병렬 실행 부하에서만 가끔 생기는 실패로 보인다.
  - 다시 돌리면서 실패 당시 로그가 덮여 원인(잠금 대기·교착 등)은 확인하지 못했다. 이번 범위 밖 문제로 기록하고 보고한다.

### 검증하지 못한 항목

- 계약만 정했고 구현은 없다. 동작 검증은 #112~#116의 몫이다.
- OpenAPI 엑셀(`OpenStudy-Foundation-API.xlsx`)은 갱신하지 않았다. M12 계약도 엑셀을 갱신하지 않았다.

## 6. 남은 작업 / 보류 사항

- 구현 이슈마다 `x-planned-changes`를 본 정의로 옮기고 항목을 지운다. 스키마 마이그레이션도 각 이슈에서 한다.
- #116 이슈 본문의 "이용 지점" 관련 인수 조건은 M13-D12(사용량 지표)에 맞게 고쳐야 한다. 이 PR에서는 이슈를 고치지 않았다.
- Frontend #40~#42는 이 계약을 기준으로 한다.
