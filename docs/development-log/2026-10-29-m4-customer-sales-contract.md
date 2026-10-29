# 2026-10-29 M4 고객·결제·이용권 API 계약과 V2 마이그레이션

관련: Issue [#55](https://github.com/OpenStudyService/OpenStudy-Backend/issues/55), [ADR-M4-002](../db/ADR-M4-002.md), [ADR-M3M4-001](../db/ADR-M3M4-001.md), [계약 문서](../design/foundation/customer-sales-api.md), [마이그레이션 버전 규칙](2026-10-29-migration-version-rule.md).

## 1. 배경 / 문제

M4(고객·이용권 판매·환불)의 정책은 ADR-M4-002로 확정됐지만 API 계약과 스키마 변경이 없었다. 고객(#56)·결제/발급(#57)·환불(#58)과 Frontend(#19/#20)가 같은 기준으로 병행하려면 operation, DTO, 오류, 권한, 지점 범위, 멱등 키, 트랜잭션 경계를 먼저 고정해야 했다. V1의 `customers`에는 등록 지점과 응대 메모를 저장할 열이 없어 M4-D05(고객 조회 범위)와 M4-D06(메모)을 구현할 수 없었다.

## 2. 검토한 선택지

- **V1 고객 행의 `registered_branch_id`**
  - NOT NULL + 임의 지점(예: 가장 작은 ID) 채우기: 스키마는 단순하지만 그 지점 직원에게 관계없는 고객 정보가 보인다. NFR-SEC-06과 충돌한다.
  - NOT NULL + 결제 지점으로 채우고 결제 없는 고객이 있으면 마이그레이션 실패: V1 적용 DB에 결제 없는 고객이 있으면 V2가 적용되지 않아 인수 조건("V1 적용 DB에서 적용")을 만족하지 못한다.
  - NULL 허용 + 첫 결제 지점으로 채우고 나머지는 NULL: 이 방식을 택했다.
- **고객·이용권 읽기 권한**: 별도 읽기 권한 코드가 없다. `members:write` 보유자로 제한하면 기본 프리셋(Q5)의 일반 직원이 현장 응대를 못 한다. ADR-M7-001 M7-D02("지점 직원 누구나 문의 등록, 고객은 M4-D05 범위로 찾는다")와 M5 입실 흐름에 맞춰 "해당 지점의 유효 소속"으로 정했다.
- **멱등 키 저장**: `payments.idempotency_key`·`refunds.idempotency_key`는 전역 UNIQUE·120자다. 클라이언트 키 원문(16~128자)을 그대로 넣으면 길이를 넘을 수 있고 다른 직원의 같은 키와 충돌한다. 공통 `idempotency_records`로 재생·충돌을 판정하고, 거래 행에는 서버가 만든 값을 넣도록 계약에 적었다.

## 3. 결정 및 이유

- `registered_branch_id`는 NULL 허용, `branches` FK(`ON DELETE RESTRICT`)와 인덱스를 둔다. 기존 행은 첫 결제(`created_at`, `id` 순)의 지점으로 채우고 결제 없는 고객은 NULL로 두어 OWNER만 보게 한다. 보정은 업무 변경이 아니므로 `updated_at`·`version`을 유지한다. 신규 등록은 API 필수 필드로 항상 등록 지점을 받는다.
- `memo`는 `VARCHAR(1000) NULL`. 이력 분리는 ADR-M4-002대로 필요해질 때 후속에서 한다.
- 결제 생성 요청은 고객·지점·상품·수단·`mockOutcome`만 받는다. 금액·수량·상품명 필드는 없고 정의되지 않은 필드는 422다. 환불 요청은 사유만 받는다.
- 결제 생성·환불·고객 등록은 `Idempotency-Key` 필수, 같은 키·다른 입력은 409 `IDEMPOTENCY_CONFLICT`. Mock 실패도 201 결과로 저장한다.
- Mock 결제는 요청 안에서 확정되므로 `PENDING`은 저장·응답하지 않는다. 승인이면 결제+이용권 1장을 한 트랜잭션으로, 실패면 실패 결제만 남긴다.
- 이용권 상태는 응답·검색 모두 요청 시각 기준 실효 상태(환불 > 만료 > 소진 > 활성)다. M4-D04·미사용 판정과 같은 규칙이고 만료 배치가 필요 없다.
- 이용권 조회 범위는 구매 지점 기준으로 두고, 고객이 보여도 다른 지점 이용권은 숨긴다. 다른 지점 최소 요약은 M6(FR-MEM-03)이다.
- 결제 생성 대상 고객은 요청자의 고객 조회 범위 안이어야 한다. 범위 밖 고객 판매는 M5 정확 일치 조회와 함께 다룬다.
- 정상이 아닌 고객(이용 제한·비활성)과 운영 중지 지점의 결제는 409로 거절한다. ADR에 명시가 없는 계약 수준 결정이므로 PR에서 리뷰를 요청한다.
- OpenAPI 버전을 `0.1.5-m4-customer-sales`로 올리고 `WorkspaceEvent.resource`에 `customers`·`payments`·`passes`를 추가했다.

## 4. 구현 내용

- `src/main/resources/db/migration/V2__customer_branch_and_memo.sql`: 두 열·FK·인덱스 추가와 기존 행 보정.
- `docs/design/foundation/customer-sales-api.md`: 계약 문서. ADR-M4-002의 D01~D08을 operation별로 반영했다.
- `docs/design/foundation/openapi.json`: 고객 5개·결제 4개·이용권 2개 operation과 17개 schema 추가. 각 operation의 사용 오류 코드는 `x-error-codes`, 근거 결정은 `x-adr-decisions`. 기존 내용은 바꾸지 않았다(추가 1876줄, 변경 3줄: 버전·설명·이벤트 enum).
- 문서 동기화: `docs/design/foundation/README.md`, `docs/db/README.md`(Flyway 이력 확인 항목), `docs/db/erd.mmd`(customers 열·관계).
- 테스트
  - `FoundationMigrationTests`: 빈 DB에서 V1·V2 두 건 적용으로 기대값 변경, V1만 적용한 DB에 데이터를 넣고 V2를 적용하는 테스트 추가(첫 결제 지점 보정, 결제 없는 고객 NULL, updated_at·version 유지, 열 타입, 메모 1000자 경계, FK RESTRICT).
  - `DatabaseInitializationTests`, `OwnerBootstrapTests`: 최신 Flyway 버전 기대값 1 → 2.
  - `CustomerSalesContractTests`(신규): 11개 operation의 오류 응답·문서 기재, 결제 생성·환불·고객 등록의 멱등 키 필수와 409 정의, 결제·환불 요청 schema에 금액 계열 필드가 없음을 검사.

## 5. 검증 결과

실행 환경: Windows 11, Java 21, Docker Desktop(Engine 27.2.0), Testcontainers MySQL 8.4(고정 digest 이미지).

- `./gradlew test` 전체: 41개 클래스 261개 테스트 통과(실패·건너뜀 0). Testcontainers MySQL에서 실행했다.
  - 빈 DB: V1·V2 두 건 적용, 테이블 65개, 재기동 시 재적용 없음(`FoundationMigrationTests`, `DatabaseInitializationTests`).
  - V1만 적용한 DB에 고객·결제 데이터를 넣고 V2 적용: 첫 결제 지점 보정, 결제 없는 고객 NULL, `updated_at`·`version` 유지, FK·메모 길이 경계 확인.
  - `ddl-auto: validate`: V2까지 적용한 DB로 Spring 컨텍스트가 기동하는 모든 `@MySqlIntegrationTest`가 통과했다. 아직 `customers`를 매핑한 엔티티는 없으므로 새 열의 매핑 검증은 #56에서 엔티티를 추가할 때 이루어진다.
  - V1 파일은 변경하지 않았다(`git diff`에 없음).
  - `CustomerSalesContractTests`, `MigrationVersionTests` 통과.
- OpenAPI 형식: `swagger-cli validate docs/design/foundation/openapi.json` → valid.
- 검증 중 확인한 사항
  - 처음 전체 실행에서 7건이 실패했다. 2건은 이번 변경 때문이었다. V2의 한글 열 주석이 스키마 추출·복원 비교에서 달라져(V1에는 열 주석이 없음) 열 주석을 빼고 SQL 상단 주석으로 옮겼다. `OwnerBootstrapTests`의 "대기 중 마이그레이션" 탐침이 V2 번호를 쓰고 있어 적용된 최신 버전 + 1을 쓰도록 바꿨다.
  - 나머지 5건(`LocalPreviewIntegrationTests`)은 개발자 PC의 git 무시 파일 `.env`에 있는 `openstudy.local-preview.enabled=false`가 테스트에도 읽혀 생긴 환경 문제였다. `.env`가 없는 main worktree에서는 통과했고, 이 브랜치에서도 `OPENSTUDY_LOCAL_PREVIEW_ENABLED=true`로 덮어쓰면 통과했다. 위 261개 통과 결과는 이 덮어쓰기 상태에서 낸 것이다. 코드는 바꾸지 않았다.
- 실행하지 않은 검증: 서비스 구현이 없으므로 HTTP 동작·권한·동시성 검증은 하지 않았다(#56~#58). 공유 개발 DB와 운영 환경에는 V2를 적용하지 않았다.

## 6. 남은 작업 / 보류 사항

- 서비스·컨트롤러 구현: #56(고객), #57(결제·발급), #58(환불). Frontend #19/#20.
- 리뷰 확인 필요(계약 수준 결정): 고객·이용권 읽기를 지점 유효 소속 전체에 허용, 정상이 아닌 고객 결제 거절, 고객 쓰기 권한을 등록·결제 지점 중 하나의 `members:write`로 판정.
- 등록 지점이 NULL인 기존 고객에게 지점을 지정하는 경로는 정하지 않았다. 개발 DB는 재생성 가능한 테스트 데이터라 필요해질 때 결정한다.
- `schema-full.sql`은 통합 V1 추출본으로 두었다. 이 경로로 설치하면 앱 기동 시 Flyway가 V2를 적용한다.
- `scripts/validate_foundation_design.py`는 main에서도 이미 실패한다(Q 정책 상태 검사가 현재 OpenAPI와 맞지 않음). 이번 변경과 무관하므로 고치지 않았고, OpenAPI 형식은 swagger-cli로 따로 검증했다.
- V2 번호는 병합 직전 main의 가장 큰 버전 + 1로 다시 확인한다.
