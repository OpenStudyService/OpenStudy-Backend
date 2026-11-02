# 2026-11-02 룸 시간 단위 스키마 변경과 타 지점 응대 권한 정의

관련: Issue [#68](https://github.com/OpenStudyService/OpenStudy-Backend/issues/68), [ADR-M6-001](../db/ADR-M6-001.md) M6-D01·M6-D06, [계약 문서](../design/foundation/space-operations-api.md), 선행 계약 PR #149(#67)

## 1. 배경 / 문제

V1의 룸 스키마는 30분 슬롯과 30분당 요금이다. M6-D01은 룸을 정시에 시작하는 1시간 단위로 예약하고 요금을 `시간당 요금 × 시간 수`로 정했다. 서버 검증만 두면 DB가 30분 예약을 그대로 받아 원장이 요구사항과 어긋날 수 있어, 제약을 DB에도 둔다. M6-D06의 타 지점 최소 요약 권한 `customers:cross-branch-read`도 `permission_definitions`에 없었다. 룸·예약 API(#69)와 타 지점 요약 API(#72)가 이 스키마와 권한 위에서 구현되므로 먼저 바꾼다.

## 2. 검토한 선택지

기존 행 처리에 두 가지를 비교했다.

- **조용히 변환**: 30분 단위 예약은 반올림하거나 슬롯을 합쳐 새 제약에 맞춘다. 마이그레이션은 항상 통과하지만, 돈과 이용 시간이 담긴 행의 의미를 임의로 바꾼다.
- **정시 정렬 행만 변환하고 아니면 중단**: 정시 시작·정시 종료·1시간 배수인 행은 금액을 그대로 두고 단위만 바꾼다. 그렇지 않은 행이 있으면 마이그레이션을 실패시킨다.

예약 API가 아직 없어 운영 DB에 예약 행이 없을 것으로 예상되지만 확인하지는 못했다.

## 3. 결정 및 이유

- 새 버전 SQL 한 개(V4)로 처리하고 V1~V3은 바꾸지 않는다. 번호는 작업 시점의 다음 번호이며 병합 직전 규칙(README)에 따라 다시 맞춘다.
- 기존 행은 **정시 정렬 행만 변환하고 아니면 중단**한다. 중단하는 쪽이 데이터를 바꾸지 않는 안전한 방향이다.
- 중단은 첫 문장에서 일어나게 한다. MySQL의 DDL은 트랜잭션으로 되돌릴 수 없어, 변환 도중 실패하면 반쯤 바뀐 DB가 남는다. 그래서 `room_reservations`에 정시 시작·정시 종료·1시간 배수 CHECK를 가장 먼저 한 문장으로 추가한다. 정렬되지 않은 행이 있으면 이 ALTER가 원자적으로 실패하고(오류 3819) 그 시점까지 아무것도 바뀌지 않는다.
- 슬롯은 새 모델에서 예약 시간마다 한 행이므로, 정렬된 예약의 `:30` 슬롯은 삭제한다. 예약마다 정시 슬롯이 시간 수만큼 남고 유일 키 `(room_id, slot_start_at)`는 그대로 점유를 막는다.
- 새 권한은 관리자 전용이 아닌 값(`is_admin_only = 0`)으로 추가하고 기본 프리셋에는 넣지 않는다. 관리자가 지정 직원에게 부여한다.
- 열 이름은 `rooms.rate_per_hour_krw`, `room_reservations.rate_per_hour_snapshot_krw`, `room_reservations.hours`로 했다. 계약 문서가 열 이름을 정하지 않아 `hours`는 문서의 수식(`amountKrw = 시간당 요금 × hours`)을 따랐다.
- `schema-full.sql`은 아래 구현 내용에 적은 이유로 V4 기준으로 다시 추출했다.

## 4. 구현 내용

`V4__room_hourly_and_cross_branch_permission.sql`

- `room_reservations`: 정시 시작·정시 종료·1시간 배수 CHECK 추가(`ck_room_reservations_13~15`, 가장 먼저 실행하는 가드). 이후 30분용 CHECK 7개를 제거하고, 열 이름을 `rate_per_hour_snapshot_krw`·`hours`로 바꾸고, 값을 변환(요금 × 2, 시간 수 ÷ 2)한 뒤 `이용 시간 = hours × 3600초`, `hours > 0`, `요금 > 0`, `금액 = 시간당 요금 × hours` CHECK를 다시 건다.
- `rooms`: `rate_per_30min_krw`를 `rate_per_hour_krw`로 바꾸고 값을 2배로 옮기며 `> 0` CHECK를 유지한다.
- `room_reservation_slots`: `:30` 슬롯을 삭제하고 슬롯 CHECK를 `minute = 0`으로 교체한다. 유일 키는 바꾸지 않는다.
- `permission_definitions`: `customers:cross-branch-read`(타 지점 고객 최소 요약 조회, 관리자 전용 아님)를 추가한다.
- 값 변환의 `UPDATE`는 `updated_at`을 유지하고 `version`을 건드리지 않는다. 업무 변경이 아니라 단위 변환이기 때문이다(V2의 이관 방식과 같다).
- 테이블 설명(COMMENT)은 시간 기준으로 고쳤다.

코드

- `PermissionPresetService`의 권한 코드 허용 목록에 새 코드를 추가했다. 이 목록에 없으면 `GET /permissions`에서 걸러지고 프리셋·소속 부여가 422가 된다.
- `BranchAction`에는 M6-D06용 동작을 넣지 않았다. 타 지점 요약 권한 판정은 #72 범위다.
- 기본 프리셋 마이그레이션은 건드리지 않아 MANAGER 기본 권한은 그대로 9종이다. 전체 권한 정의는 10종이 된다.

문서

- `docs/db/erd.mmd`, `docs/design/foundation/database.md`(권한 코드 목록), `docs/db/README.md`를 갱신했다.
- `docs/db/schema-full.sql`은 `FoundationMigrationTests`의 추출 기능(`-PfoundationExportDir`)으로 V4 기준으로 다시 추출했다. 이 파일은 Flyway 이력을 포함하고 "설치 후 앱을 시작하면 이후 버전만 적용"하는 대체 설치 경로다. V4 변경만 손으로 고치면 V2·V3 변경이 빠진 채 이력은 V1뿐이라, 이 파일로 설치한 DB에서 Flyway가 V2~V4를 다시 실행해 실패한다. 그래서 일부러 전체를 추출했고, 그 결과 V2·V3 변경(`customers` 열, `conversations` 열)과 이력 1~4, 시각 값, `customers`가 `branches`를 참조하게 되어 생긴 테이블 순서 변화가 같은 파일 변경에 섞여 있다.

테스트

- `FoundationMigrationTests`: 적용 개수·이력을 4개 기준으로 고치고(V3 테스트는 `target 3`으로 고정), V4 테스트 5개를 추가했다.
- `SchemaConstraintsTests`, `DatabaseInitializationTests`, `OwnerBootstrapTests`, `EmployeeManagementIntegrationTests`를 새 열 이름·버전·권한 10종에 맞췄다. 소속에 새 권한을 부여하는 검증도 추가했다.

## 5. 검증 결과

실행한 것

- `FoundationMigrationTests`(추가분 포함 전부), `MigrationVersionTests`, `SchemaConstraintsTests`: 통과.
- 확인한 항목: 빈 DB에 V1~V4 적용과 재시작 검증, 정시 정렬된 V3 데이터의 변환(룸 요금 4,000→8,000, 예약 요금·시간·금액 보존, 슬롯 4개→2개, `updated_at`·`version` 유지), `:30` 시작·`:30` 종료·90분 V3 예약이 있으면 마이그레이션이 실패하고 열·값·권한이 V3 그대로인 것, 30분 시작 행·1시간 배수가 아닌 행·시간 수와 기간 불일치·금액 불일치·요금 0 행·`:30` 슬롯의 거부, 슬롯 유일 키 유지, 새 권한의 비관리자 전용 등록과 프리셋 미포함, 추출한 `schema-full.sql` 복원 후 컬럼·FK 동일.

- `./gradlew test` 전체 1회: 60개 suite, 645개 테스트, 실패 1, 건너뜀 0(약 27분). 실패한 테스트는 `WorkspaceIntegrationTests.lookupsAreCanonicalScopedAndMinimumAndPendingOrAnonymousAreDenied`였고 `/lookups`의 권한 정의 개수를 9로 기대하고 있었다. 새 권한으로 10종이 되는 것이 정상이라 기대값을 10으로 고쳤다.
- 고친 뒤에는 `WorkspaceIntegrationTests` 클래스(9개)만 다시 실행해 모두 통과했다. **전체 테스트를 수정 후에 다시 돌리지는 않았다.**

실행하지 못한 것

- 운영(공유) DB에서의 V4 적용. 운영 DB에 예약 행이 있는지는 확인하지 못했다.
- JPA `validate`는 DB 기반 통합 테스트가 앱을 기동할 때 함께 수행된다. 룸 엔티티가 아직 없어 새 열에 대한 매핑 검증은 해당하지 않는다.

**커밋 후 재실행**: 위 수정 후 전체 재실행이 빠져 있어 커밋 `9aa3df0` 그대로 `./gradlew test` 전체를 다시 실행했다. 60개 suite, 645개 테스트 통과(실패·오류·건너뜀 0)다.

## 6. 남은 작업 / 보류 사항

- 룸·예약 API 구현은 #69, 타 지점 요약과 권한 판정(`BranchAction` 등)은 #72에서 한다.
- 24시간 이용 상한은 계약상 서버 규칙이며 DB CHECK로 두지 않았다. 필요하면 #69에서 결정한다.
- V4 번호는 임시이며 병합 직전에 main의 가장 큰 버전 + 1로 맞춘다. 번호를 바꾸면 `schema-full.sql`의 이력과 이 기록의 파일 이름, 테스트의 버전 기대값(`DatabaseInitializationTests`, `OwnerBootstrapTests`, `FoundationMigrationTests`)도 함께 고친다.
- `schema-full.sql`은 마이그레이션이 늘 때마다 다시 추출해야 한다(README에 적음).
