# 2026-11-27 ticket 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 두 번째 모듈이다. ticket 모듈(41개 파일, 약 2,500줄)은 다음을 담당한다.

- 상품·상품 분류 관리와 변경 이력
- 이용권 발급·환불·조회
- 좌석 이용 정산과 수동 조정

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성 메서드
- 한 줄짜리 수동 getter 수십 개와 직접 쓴 생성자
  - `Product`·`ProductCategory`·`ProductRevision`
  - `ProductService`·`ProductCategoryService`·두 컨트롤러
- 120자를 넘는 줄
  - JPQL·네이티브 SQL 한 줄 문자열
  - 18~20개 인자를 몰아 쓴 응답 생성
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `ofCustomerFamily`, `locked`, `usedIds`, `soldQuantity` 등
  - 헬퍼: `ProductViews.record`·`category`·`kind`, `ProductInputs.terms`·`id` 등
  - 서비스 private: `views`, `status`, `expired`, `snapshot` 등
- 한 선언에 상수 네 개(`AVAILABLE, NOT_YET, ...`)

## 2. 검토한 선택지

- `Product.Terms`(5개 인자)는 `ProductViews.toColumns`에서 세 번, 유형마다 쓰지 않는 칸을 `null`로 채워 만든다. 빌더 적용 기준(반복 생성, null 채움 인자)에 맞아 `@Builder`를 붙였다.
- 다음 생성자는 생성 지점이 한 곳뿐인 전체 매핑이라 생성자를 유지하고 인자만 한 줄에 하나씩 썼다.
  - `Pass.Snapshot`(10개 인자)
  - `IssuedPass`·`Product`·`PassLedger` 응답
- 엔티티 정적 생성 메서드(`Pass.issue`, `PassBalanceEvent.use`·`adjust`)는 이미 동사라 그대로 두었다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - 서비스 public 메서드 전부
    - `PassService`: `issue`, `refund`, `lockOwned`, `requireUsable`, `settle`, `sessionPass(es)`, `usableBy`, `heldBy`, `byPayments`, `list`, `get`, `ledgers`, `view`
    - `ProductService`: `list`, `get`, `create`, `update`, `changeStatus`, `replaceBranches`, `history`, `requireSellable`, `sellable`
    - `ProductCategoryService`: `list`, `create`, `update`
    - `PassAdjustmentService`: `adjust`
  - DTO, `PassSettlementCalculator.settle`(단위 테스트)
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `PassRepository`: `ofCustomerFamily` → `findByCustomerFamily`, `heldByCustomerFamily` → `findHeldByCustomerFamily`, `usableByCustomerFamilies` → `findUsableByCustomerFamilies`, `mergedInto` → `findMergedInto`, `purchaseBranchOf` → `findPurchaseBranch`, `locked` → `findLocked`, `usedIds` → `findUsedIds`
  - `ProductRepository`: `salesLimitOf` → `findSalesLimit`, `soldQuantity` → `countSold`, `soldQuantities` → `countSoldByProduct`
  - 모든 메서드에 `@Query`가 붙어 있어 이름을 바꿔도 Spring Data의 메서드 이름 해석은 일어나지 않는다.
  - `ProductViews`: `toRecord`, `toCategory`, `toColumns`, `toApiKind`, `toKind`
  - `ProductInputs`: `parseInteger`, `parsePrice`, `parseTerms`, `parseBranchIds`, `parseId`, `requireExactFields`
- 컨트롤러
  - 메서드 이름은 openapi operationId 13개로 바꿨다.
  - 클래스 매핑(`/api/passes`, `/api/products`, `/api/product-categories`)은 그대로 둔다.
- 엔티티 `Product`·`ProductCategory`·`ProductRevision`
  - 수동 getter와 `protected` 기본 생성자를 `@Getter`·`@NoArgsConstructor(access = PROTECTED)`로 바꿨다.
  - 정렬된 복사본을 돌려주는 `Product.getBranchIds()`만 `@Getter(AccessLevel.NONE)`로 두고 직접 쓴다.
  - 모든 getter의 반환 타입이 필드 타입과 같아, Lombok이 만든 getter와 시그니처가 같다.
- 서비스의 직접 쓴 생성자는 `@RequiredArgsConstructor`로 바꿨다. 주입 필드 이름은 타입에 맞췄다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증
  - 상품 수정 명령: 잠금 → 권한 → 버전 → 검증 → 변경
  - 판매: 한도 조회 → 잠금 → 상태·지점·기간·한도
  - 상세 응답: 판매 수 조회는 권한 범위 조회 뒤
  - 계산은 하지 않고 순서만 따라가야 하는 부분은 단락 평가를 그대로 유지했다. 예: 병합 고객 확인, 환불 가능 판정.
- 람다가 길던 `PassAdjustmentService.adjust`는 잠금 안의 처리를 `adjustLocked`로 뺐다. 순서와 인자는 같다.
- `AuditEvent.change` 호출은 위치 인자 그대로 둔다(#265).
- DomainLens 어노테이션은 손대지 않았다(#250에서 domainlens 제외).

## 4. 구현 내용

- 서비스 4개
  - 멱등 재실행을 `findStoredResult`로 뺐다(`ProductService`·`ProductCategoryService`).
  - 유효하지 않은 키 검사를 이름 있는 조건으로 바꿨다.
  - 201·200·24시간·키 길이·이름 길이·`SHA-256`을 상수로 바꿨다.
  - 응답 변환을 `toIssuedPass`·`toUsablePass`·`toSessionPass`·`toLedger`·`toProduct`·`toRevision`·`toSnapshot`으로 나눴다.
  - 검색 문자열 비교를 `matches`로 뺐다.
  - 상품 코드 생성을 `createCode`로 뺐다.
- 리포지토리
  - JPQL·네이티브 SQL을 text block으로 옮겼다.
  - `PassSearchRepository`의 WHERE 조립을 `appendWhere`로 뺐다. 바인딩 순서와 SQL 조각은 그대로다.
- 엔티티
  - 필드 어노테이션을 한 줄에 하나씩 썼다.
  - 한 줄 대입을 풀었다.
  - enum 상수를 한 줄에 하나씩 썼다.
  - `Pass`의 24시간을 `HOURS_PER_DAY` 상수로, `PassBalanceEvent`의 `USE-` 접두어를 상수로 바꿨다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다. `ProductTerms`의 한 줄 생성자·팩토리를 풀었다.
- 컨트롤러: 정렬 필드·기본 정렬을 상수로 바꿨다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- ticket `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석과 DomainLens 어노테이션 제외)이 남아 있지 않은 것을 확인했다.
- SQL 리터럴 토큰 비교(`origin/main` 대비)
  - `PassRepository`·`ProductRepository`: 차이 없음.
  - `PassSearchRepository`: 두 번 쓰던 `" FROM passes x WHERE "`가 상수 하나로 모인 것뿐이다. 실행되는 SQL은 같다.
- 상품·이용권과 이를 쓰는 모듈의 31개 테스트 클래스, 346개 테스트: 실패 0, 건너뜀 2(환경 변수가 있어야 실행되는 `AiLiveParityIntegrationTests`·`SalesQueryPlanMeasurementTests`).
  - ticket 모듈 전체(상품, 판매 한도, JPA 동작, 정산 계산 단위 테스트)
  - payment, passhistory, seat, sales 모듈 전체
  - `CustomerMergeIntegrationTests`, `BranchClosureIntegrationTests`, `RoomReservationIntegrationTests`
  - 계약 테스트 4개: `CustomerSalesContractTests`, `SeatUsageContractTests`, `OperationsExtensionContractTests`, `AggregationContractTests`
  - `OpenStudySemanticCatalogTests`(엔티티 정리 뒤 DomainLens 카탈로그가 그대로인지 확인)
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 다음 모듈: seat, locker, facility, reservation, task, chat
