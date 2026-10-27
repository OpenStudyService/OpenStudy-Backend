# 이용권 상품 조건·판매 지점·분류·revision (#16)

관련 이슈: [Backend #16](https://github.com/OpenStudyService/OpenStudy-Backend/issues/16). 계약: [상품 API 구현 계약](../design/foundation/product-api.md).

## 1. 배경 / 문제

#13~#15로 직원 초대 흐름이 main에 병합되어 M2 백엔드가 진행되었지만, 상품 쪽은 DB(V2/V3의 `product_categories`·`product_branches`·`product_revisions`)와 OpenAPI 계약, 공개 인가 정책(`requireSharedProduct`, `EDIT_PRODUCT`/`MANAGE_PRODUCT`)만 있고 `modules/ticket`에는 구조 파일뿐이었다. 상품 화면(Frontend #7)과 이후 고객 구매·결제(M4)가 쓸 상품 조건·판매 지점·분류·변경 이력 API가 없었다.

작업 시작 시 원래 Backend 체크아웃에는 V1 통합 스키마·OWNER 초기화 관련 미커밋 변경이 있었다. 갱신한 원격 main `b650acd`에서 별도 worktree와 `feature/pass-product-conditions`를 만들어 분리했고 그 변경은 포함하지 않았다.

## 2. 검토한 선택지

- **공유 상품의 권한 기준**: 요청의 `canEdit`·`branchIds`로 판정하는 방법과 잠금 후 다시 읽은 저장 지점 집합으로 판정하는 방법을 비교했다. 앞의 방식은 동시 지점 변경과 위조에 약하다. 기존 `AuthorizedCommands`의 잠금 순서(가드 → 직원 → 지점 → 상품 행)에 상품 행 잠금을 부모 잠금으로 끼우고, 잠근 뒤의 저장 집합을 `requireSharedProduct`에 넘기는 방법을 택했다.
- **볼 수 없는 상품의 응답**: 403과 404를 비교했다. 읽을 수 있는 지점에 하나도 속하지 않는 상품은 404로 숨기고(ID로 존재를 알 수 없게), 일부만 보이는 공유 상품의 쓰기는 403으로 거부했다.
- **멱등 재전송 응답 저장**: `Product`에 다형 `terms`(유형별 record)가 있어 응답 JSON을 다시 읽으려면 타입 정보가 필요하다. 저장 JSON에 별도 타입 필드를 넣는 대신, 필드 집합이 서로 다른 세 유형에 Jackson `DEDUCTION`을 적용해 API 응답의 모양을 바꾸지 않았다.
- **변경 없는 PATCH**: 항상 revision을 올리는 방법과 내용이 같으면 변경하지 않는 방법을 비교했다. 재시도·이중 클릭이 의미 없는 revision과 감사를 쌓지 않도록 후자를 택했다. 낡은 version은 이 경우에도 409다.
- **공유 상품의 감사 지점**: 지점마다 감사 행을 만드는 방법과 한 건에 `branch_id`를 비우는 방법을 비교했다. 상품은 소유 지점이 하나가 아니고 전체 내용은 revision에 있으므로 한 건으로 했다. 화면 갱신용 이벤트는 지점 단위여서 변경 전·후 모든 지점에 기록했다.
- **구매 연결**: 구매 API는 범위 밖이므로 만들지 않았다. 다만 "판매 중지 후 신규 판매 차단"을 검증하려면 호출 가능한 지점이 필요해, 후속 구매 트랜잭션이 부를 서비스 메서드(`requireSellable`)만 제공했다.

## 3. 결정 및 이유

- 정책은 확정 사항을 그대로 따랐다. Q6(operations:write STAFF의 등록·가격·구성 수정, 판매 중지·지점 변경은 MANAGER), Q7(공유 상품은 모든 적용 지점 권한), Q9(유형 변경 금지), Q2(기간권은 일수만 저장), SRS v1.1(물리 삭제 API 없음, 판매 중지·재개). 새 정책은 정하지 않았다.
- 입력은 `StrictInput`과 유형별 정확한 필드 집합으로 검증한다. 다른 유형의 필드를 조용히 버리지 않고 422로 거부해 "유형별 허용/금지 조합"을 계약으로 고정했다. `validDays`는 필수 키이며 null만 무기한이다.
- 분류는 `kind` 일치와 활성 상태를 서버가 확인한다. DB에는 이 일치를 강제하는 제약이 없어(V3 사전 점검은 기존 데이터만 확인) 서비스에서 막는다.
- 0원은 허용하지 않는다. V3의 `ck_products_positive_price`와 OpenAPI의 `minimum: 1`이 이미 정한 기준이다.
- 상품 revision의 `changed_at`은 `CURRENT_TIMESTAMP(6)`로 기록해 같은 변경의 `updated_at`과 같은 기준을 쓴다. 이력 정렬은 시각 문자열이 아닌 version으로 한다.
- `GET /workspace/lookups`의 `products`·`productCategories`는 바꾸지 않았다. 기존 시험이 빈 배열을 전제하고 있고, 이 이슈의 인수 조건이 아니다.

## 4. 구현 내용

- `modules/ticket`에 컨트롤러 2개(`ProductController`, `ProductCategoryController`), 서비스(`ProductService`, `ProductCategoryService`, 입력 검증 `ProductInputs`), JDBC 저장소 2개, DTO(`Product`, `ProductTerms`, `ProductCategory`, `ProductSnapshot`, `ProductRevision`)를 추가했다. `DELETE` 매핑은 두지 않았다.
- 등록은 서버가 `PD-` 코드와 version 0의 `CREATED` revision을 만들고 `Idempotency-Key`(사용자+operation+키)로 재전송을 막는다. 같은 키·같은 입력은 저장된 결과를, 다른 입력은 409를 반환하며 재사용 전에 권한을 다시 검증한다.
- 수정·판매 상태·판매 지점 교체는 상품 행 잠금 아래에서 저장 지점 권한을 판정하고, version을 확인한 뒤 상품 갱신·revision·감사·지점별 이벤트를 한 트랜잭션에 기록한다.
- 이력은 현재와 과거 revision의 모든 지점에 대해 MANAGER 권한을 요구하고, `before`는 직전 revision 스냅샷이다.
- 분류는 OWNER 전용 등록·이름/상태 변경이며 code·kind는 불변이다. 상품 revision이 분류 code·name을 복사하므로 이름 변경이 과거 이력에 영향을 주지 않는다.
- `ProductService.requireSellable(productId, branchId)`: 상품 행 공유 잠금 후 판매 중지·판매 지점 아님을 409로 거부하고 구매 시점 조건 스냅샷을 반환한다.
- 문서: OpenAPI의 10개 operation 구현 상태, [상품 API 구현 계약](../design/foundation/product-api.md), 설계 README, `ticket` 패키지 설명. 요청·응답 모델과 계약 버전은 바꾸지 않았다. 기존 Flyway 마이그레이션도 바꾸지 않았다.

## 5. 검증 결과

### 신규 통합 시험 (`ProductIntegrationTests`, 10개)

Testcontainers MySQL과 실제 HTTP 서버, 서비스 직접 호출(동시성·롤백)로 실행했다.

| 인수 조건 / 위험 | 확인 결과 |
| --- | --- |
| 3유형·양수 단위·무기한 nullable | 시간권(무기한·기한)·기간권·정액권 등록 201. 0원·음수·문자열·소수·안전 범위 초과, 0분·32비트 초과, 0일 유효기간, 유효일 누락, 다른 유형 필드, 알 수 없는 유형·필드, 빈 이름·긴 이름, 잘못된 상태 등 26개 입력이 422이고 상품 수는 그대로 |
| 분류 kind·활성 검증 | 유형 불일치·없는 분류 422. 비활성 분류에는 새 상품을 지정할 수 없고 기존 상품은 유지 |
| Q6 STAFF 등록·수정 | operations:write STAFF가 단일 지점 상품을 등록·수정. 권한 없는 STAFF·두 지점 중 한 곳만 가진 STAFF의 등록은 403 |
| 공유 상품 전체 범위 권한 | 일부 지점만 가진 STAFF·MANAGER의 공유 상품 수정·상태 변경 403. 모든 지점 권한자는 수정, 상태·지점 변경은 MANAGER 또는 OWNER |
| 일부 지점 사용자·범위 회수 | 공유 상품은 보이는 지점의 `branchIds`만 반환하고 `canEdit=false`. 볼 수 없는 상품은 404, 권한 밖 `scope`는 403. 소속 회수 직후 목록 0건·상세/수정 404 |
| 변경 이력·최초 revision | 등록 시 `CREATED`(version 0, 작업자 기록), 수정마다 `UPDATED`. `before`/`after`가 직전·현재 스냅샷이며 최초 이력의 `before`는 null |
| 구매 조건 보존·참조 | 고객·결제·발급 행을 만든 뒤 상품 이름·가격·조건을 바꿔도 `passes.snapshot_*`와 `payments.product_name_snapshot`이 그대로이고, 참조 중인 상품 물리 삭제는 FK로 거부 |
| 유형 변경 금지 | `terms.kind` 변경 422, 다른 유형의 분류로 변경 422 |
| 삭제 API 없음·보존 | `DELETE /products/{id}`·`DELETE /product-categories/{id}` 405. 판매 중지·재개 후에도 상품 ID가 유지되고 revision 3건(등록·중지·재개) |
| 판매 중지 후 신규 판매 차단·재개 | 중지된 상품은 `requireSellable`이 409 `PRODUCT_NOT_ON_SALE`, 재개 후 조건 스냅샷 반환, 판매 지점 아닌 지점은 409. 중지 중에도 조회 가능 |
| 동시 가격/지점/상태 변경 409 | 같은 version의 가격×가격, 가격×지점 교체, 중지×중지 동시 요청에서 정확히 하나만 성공하고 나머지는 409. revision은 2건씩 |
| 롤백 | 외부 트랜잭션 롤백 시 상태 변경과 등록의 상품·revision·감사·이벤트·멱등 기록이 모두 원래대로 |
| 멱등 | 같은 키·입력은 저장 결과, 다른 입력 409, 키 누락·짧은 키 422, 권한 회수 후 같은 키 재사용 403 |
| 버전 충돌 | 낡은 version 409(`latestVersion`, `reloadUrl`). 내용이 같은 PATCH·같은 상태 요청은 version·revision·감사를 만들지 않음 |
| 지점 교체·이력 권한 | 지점 집합 교체의 숨은 지점 우회 403, 빈 집합 422, 없는 지점 403. 과거에 다른 지점을 포함한 이력은 그 지점 권한이 없는 MANAGER·STAFF가 열람할 수 없음(403), OWNER는 열람 가능 |
| 분류 | OWNER만 등록·수정(STAFF 403), 중복 코드 409, 멱등 재전송, code/kind 변경 422, 이름 변경 후에도 과거 revision의 분류 이름 유지 |

### 전체 실행

Windows·Gradle Wrapper 9.8.1·Docker Desktop의 기존 Testcontainers MySQL 8.4.11 설정으로 실행했다. 사용자 DB에는 접근하지 않았다.

```bash
./gradlew test bootJar --no-daemon
```

- 신규 시험만 먼저 실행해 `ProductIntegrationTests` 10개와 `ModuleBoundaryTests`가 통과했다(모듈 경계: 다른 모듈은 service·dto만 사용, 순환 없음).
- 첫 전체 실행에서 기존 `LocalPreviewIntegrationTests.previewKeepsExistingUiSortOptionsButRejectsPrivateFields` 1개가 실패했다(206개 중 1개). local 프로필의 샘플 `GET /api/products?sort=status`가 200이어야 한다는 기대였는데, 이제 같은 경로를 실제 상품 API가 먼저 처리하고 `sort=status`는 허용 정렬이 아니라서 422가 된다. 구현 결함이 아니라 시험이 임시 샘플 경로를 전제한 것이므로, 같은 의도(샘플의 기존 정렬 옵션 유지·비공개 필드 정렬 거부)를 아직 샘플로 제공되는 `members`로 검증하도록 시험 대상 리소스만 바꿨다. 지점(`/api/branches`)도 같은 방식으로 이미 실제 API가 샘플을 대체하고 있다.
- 수정 후 전체 실행: **BUILD SUCCESSFUL (10분 11초), JUnit XML 26개 suite 합산 206개, 실패·오류·건너뜀 0**. 실행 JAR `build/libs/openstudy-backend-0.0.1-SNAPSHOT.jar`가 생성됐다(첫 전체 실행에서 빌드된 JAR과 소스가 같아 두 번째 실행에서는 최신 상태로 건너뛰었다).
- 기존 초대·인증·세션·조직·SSE·분기·모듈 경계 회귀 시험을 함께 통과했다.

### 수행하지 않은 검증

- 구매·결제·발급 흐름에서의 실제 호출: 해당 API가 없다. `requireSellable`의 판매 중지·판매 지점 거부와 스냅샷 반환만 서비스 시험으로 확인했다.
- 브라우저·Frontend #7 연동, 실제 SSE 스트림으로의 `products` 이벤트 전달: 이벤트 행의 기록만 확인했고 스트림 전달은 시험하지 않았다.
- 운영 TLS/프록시, 다중 인스턴스: 이 이슈 범위 밖이다.
- OpenAPI 스키마와 실제 응답의 기계적 대조: 도구를 쓰지 않았다. 응답 필드는 시험의 주요 항목으로만 확인했다.
- graphify: worktree에 `graphify-out/graph.json`이 없어 질의·갱신을 하지 않았다.

## 6. 남은 작업 / 보류 사항

- `GET /workspace/lookups`의 `products`·`productCategories`는 빈 배열이다. Frontend #7이 선택 데이터를 목록 API로 충분히 쓸 수 있는지 확인한 뒤 필요하면 별도 이슈로 다룬다.
- 목록은 전체 상품을 읽어 메모리에서 필터·정렬한다. 지점 목록 등 기존 API와 같은 방식이며 상품 수가 많아지면 SQL 페이징으로 바꿔야 한다.
- 구매·결제(M4)에서 `requireSellable`을 같은 트랜잭션에서 호출하고, 구매 당시 revision 버전을 `passes`/`payments`에 연결하는 방식을 정한다.
- 이력 열람은 과거에 한 번이라도 판매한 모든 지점의 관리 권한을 요구한다. 지점이 늘어난 상품은 일반 MANAGER가 이력을 볼 수 없다. 현재 계약(OpenAPI)을 따른 것이며 변경하려면 정책 결정이 필요하다.
- 통합 검증 #18의 상품 시나리오(실제 두 브라우저, 구매 후 snapshot)는 Frontend #7과 M4 이후에 수행한다.
