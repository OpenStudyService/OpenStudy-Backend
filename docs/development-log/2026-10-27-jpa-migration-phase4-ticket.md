# 2026-10-27 JPA 전환 4단계: 상품(ticket) 모듈

관련: [3단계 기록](2026-10-27-jpa-migration-phase3-branch-management.md), [1단계 기록](2026-10-27-jpa-migration-phase1-shared-records.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

상품(`products`), 상품 분류(`product_categories`), 변경 이력(`product_revisions`), 판매 지점(`product_branches`)은 `ProductRepository`·`ProductCategoryRepository`의 JDBC SQL로 구현되어 있었다.

옮기기 전에 확인한 기존 동작은 다음과 같다.

- 상품 변경은 `UPDATE ... version=version+1 WHERE id=? AND version=?`였다. 0행이면 409를 반환했다. 판매 지점만 바꿔도 상품 행의 version이 1 증가했다.
- 내용이 같은 요청은 version·이력·감사·이벤트를 만들지 않았다(서비스에서 판단).
- 변경 이력의 `event_type`은 생성 때만 `CREATED`이고, 수정·상태 변경·지점 변경은 모두 `UPDATED`다. 구분은 감사 기록의 action이 맡는다.
- `product_revisions.changed_at`은 컬럼 기본값이 없고, INSERT 문의 `CURRENT_TIMESTAMP(6)`로 DB 시각을 넣었다.
- 분류 코드 중복은 JDBC `DuplicateKeyException`을 잡아 `CATEGORY_CODE_EXISTS`(409)로 바꿨다.
- 이용권 조건 컬럼(`included_minutes`, `period_days`, `valid_days`)은 `INT UNSIGNED`이고, 입력 검증은 `U32_MAX`(4,294,967,295)까지 허용한다.
- 상품 잠금(`FOR UPDATE`)과 구매 시 공유 잠금(`FOR SHARE`)은 `AuthorizedCommands`의 잠금 순서 안에서 쓰인다.

## 2. 검토한 선택지

- **판매 지점**: 별도 엔티티 / `@ElementCollection Set<Long>`. 지점은 branch 모듈 소유(JPA-03)라 상품에서는 지점 ID만 다루면 되고, 복합 PK 연결 테이블에 추가 컬럼이 없으므로 요소 컬렉션을 택했다. Hibernate는 Set 요소 컬렉션에서 바뀐 행만 삭제·삽입하므로 PK 충돌이 없다.
- **분류 코드 중복**
  - JPA 예외(`DataIntegrityViolationException`)를 잡는 방식: 다른 무결성 오류와 구분하기 어렵다.
  - 저장 전 `existsByCode` 확인: 분류 생성은 `ownerCommand`의 가드 잠금으로 이미 직렬화되어 있어 경합이 없다. 이 방식을 택했다.
- **`INT UNSIGNED` 매핑**: `Integer`는 `U32_MAX`를 담지 못한다. `Long`은 기본적으로 `BIGINT`를 기대해 `validate`에서 걸린다. `Long` + `columnDefinition = "int unsigned"`로 매핑했다.
- **이력 시각**
  - 애플리케이션 `Clock`: 기록 시각의 기준이 바뀐다.
  - Hibernate `@CurrentTimestamp(source = DB)`: 기존처럼 DB 시각을 쓴다. 이 방식을 택했다.

## 3. 결정 및 이유

- 엔티티: `Product`, `ProductCategory`, `ProductRevision`과 enum `ProductKind`, `CategoryStatus`, `SaleStatus`, `RevisionEvent`.
  - 상품과 분류는 version을 노출하고 409로 충돌을 거절하므로 `@Version`(JPA-02).
  - 이력은 추가 전용이다.
  - 엔티티 이름이 API DTO(`Product`, `ProductCategory`, `ProductRevision`)와 같다. 3단계 지점과 같은 방식으로, 서비스의 몇 곳에서만 정규화된 이름을 쓰고 나머지는 `var`로 처리했다.
- 상품 잠금 2개(`FOR UPDATE`, `FOR SHARE`)는 잠금 순서가 계약의 일부라 native query로 유지했다(JPA-01).
- 상품 변경은 기존 조건부 UPDATE의 의미를 그대로 옮겼다.
  - flush할 때 Hibernate의 `WHERE version=?` 검사가 실패하면 `OptimisticLockingFailureException`을 기존과 같은 409로 바꾼다.
  - flush 후 version이 그대로면(지점 행만 바뀐 경우 등) JPQL로 정확히 1 올린 뒤 `refresh`한다. 3단계 지점과 같은 방식이다.
- 분류 수정은 이름이나 상태가 실제로 바뀔 때만 실행되므로, 행이 바뀌어 `@Version`이 1 증가한다. 별도 증가 처리가 필요 없다.
- 감사·outbox·멱등 래퍼는 저장소에서 제거하고, 서비스가 공통 컴포넌트를 직접 호출한다(1단계 후속 항목).
- `ProductRecord`(서비스 내부 값)는 `service` 패키지로 옮겼다. 엔티티 ↔ 값 변환은 `ProductViews`에 모았다.

## 4. 구현 내용

- `ticket/entity`: 엔티티 3개와 enum 4개. `.gitkeep`은 제거했다.
- `ticket/repository`: JDBC 클래스 2개 → Spring Data 인터페이스 3개.
  - `ProductRepository`: `@EntityGraph`로 분류·판매 지점을 함께 조회, native 잠금 2개, `incrementVersion`.
  - `ProductCategoryRepository`: `@Lock(PESSIMISTIC_WRITE)` 조회, `existsByCode`.
  - `ProductRevisionRepository`
- `ticket/service`
  - `ProductService`·`ProductCategoryService`: 엔티티 기반으로 전환했다. 권한 판정, 잠금 순서, 입력 검증, 동일 요청 판단, 스냅샷 JSON 형식은 바꾸지 않았다.
  - `ProductViews`, `ProductRecord`(이동)
- 목록 조회: 기존에는 판매 지점 전체 조회 1회 + 상품·분류 조인 1회였다. 이제 엔티티 그래프 조회 1회다.
- 신규 `ProductJpaBehaviorTests`(4개): HTTP 테스트가 고정하지 않던 DB 수준 효과를 확인한다.
  - 이력 행의 version·event_type(`CREATED`→`UPDATED`)·작성자·JSON 문서 저장·기록 시각
  - 판매 지점 교체 시 `product_branches` 행과 version +1
  - `U32_MAX` 조건값의 저장·조회 왕복
  - 분류 수정의 `updated_by`와 version +1

## 5. 검증 결과

- 집중 실행 결과
  - `ProductIntegrationTests` 10개가 첫 실행에서 통과했다. 이 테스트는 version 증가, 409, 동시 같은 version 쓰기에서 1건만 성공, 분류 코드 중복, 이력, 구매 스냅샷 보존, 롤백을 다룬다.
  - `ProductJpaBehaviorTests` 4개, `JpaMappingTests` 3개, `JpaSimpleReadsTests` 2개, `ModuleBoundaryTests` 1개 통과. 새 엔티티 3개의 `validate`도 통과했다.
- 이력 기록 시각 테스트는 값이 저장되고 현재 시각에 가까운지만 확인한다. DB 시각인지 애플리케이션 시각인지는 테스트로 구분하지 못했다. DB 시각이라는 근거는 `@CurrentTimestamp(source = DB)` 매핑이다.
- 전체 회귀: `test` **228개/32개 suite**(3단계 병합 후 224개 + 신규 4개), 실패·오류·건너뜀 0, 5분45초 BUILD SUCCESSFUL. 기존 테스트는 수정하지 않았다.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 구매(`passes`) 흐름은 아직 구현되지 않았다. `requireSellable`은 기존 테스트가 판매 중지·미판매 지점·없는 상품·스냅샷 반환을 확인한다. 공유 잠금이 동시 판매 중지를 기다리게 하는지는 동시성 테스트로 확인하지 않았다(이번 변경 전에도 없었다).
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
