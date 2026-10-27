# 이용권 상품·분류 API

Backend [#16](https://github.com/OpenStudyService/OpenStudy-Backend/issues/16)의 구현 범위다. 요청·응답 모델은 기존 [OpenAPI](openapi.json)(`0.1.4-product-retention`)를 그대로 사용하며 계약 버전은 바꾸지 않았다. 정책은 [decisions.md](decisions.md)의 Q2·Q6·Q7·Q9와 SRS v1.1의 물리 삭제 미제공을 따른다. 구매·결제·발급은 이 범위가 아니다.

## 제공 범위

| API | 권한 | 동작 |
| --- | --- | --- |
| `GET /product-categories` | 활성 계정 | 분류 목록(kind·status·q, 정렬 createdAt/name/code) |
| `POST /product-categories` | OWNER | 분류 등록. `Idempotency-Key` 필수 |
| `PATCH /product-categories/{id}` | OWNER | 이름·상태 변경. code·kind는 받지 않는다(422) |
| `GET /products`, `GET /products/{id}` | 읽을 수 있는 지점 중 하나라도 판매 지점인 사용자 | 목록(status·kind·categoryId·q, 정렬 createdAt/name/priceKrw)·상세 |
| `POST /products` | 요청한 모든 지점의 `operations:write` | 상품 등록. 서버가 code와 최초 revision을 만든다. `Idempotency-Key` 필수 |
| `PATCH /products/{id}` | 현재 모든 판매 지점의 `operations:write` | 명칭·가격·조건·분류 변경 |
| `POST /products/{id}/status` | 현재 모든 판매 지점의 MANAGER + `operations:write` | 판매 중지·재개 |
| `PUT /products/{id}/branches` | 현재·변경 후 모든 지점의 MANAGER + `operations:write` | 판매 지점 집합 교체 |
| `GET /products/{id}/history` | 현재·이력의 모든 지점의 MANAGER + `operations:write` | 변경 이력(기본 최신순) |

OWNER는 모든 조건을 만족한다. `DELETE /products/{id}`와 분류 삭제는 없다(405).

## 조건 검증

- `terms`는 유형별로 정확히 정해진 필드만 받는다. 다른 유형의 필드, 누락, 알 수 없는 필드, 문자열·소수 숫자는 422다.
- 시간권 `includedMinutes`·기간권 `periodDays`는 1~4294967295, `validDays`는 null(무기한) 또는 1~4294967295다. 0은 무기한이 아니다. 기간권에는 `validDays`가 없다(Q2).
- 가격·`creditKrw`·`hourlyRateKrw`는 1~9007199254740991이다. 0원 상품은 DB 제약(`price_krw>0`)과 같은 기준으로 거부한다.
- 분류는 상품 유형과 같은 kind의 활성 분류여야 한다. 비활성 분류는 새 지정만 막고, 이미 지정된 상품은 그대로 유지한다.
- `terms.kind` 변경은 422다(Q9). 변경하지 않은 필드는 유지하고 `terms`는 해당 유형 전체를 교체한다.

## 권한·가시성

- 상품은 읽을 수 있는 지점 중 하나라도 판매 지점이면 보인다. 보이는 지점이 없으면 404이며 상품 ID로 존재를 알 수 없다. 응답의 `branchIds`는 읽을 수 있는 지점만, `canEdit`은 모든 판매 지점의 편집 가능 여부다.
- 쓰기는 요청 본문이 아니라 잠금 후 다시 읽은 저장 지점 집합으로 판정한다. 일부 지점만 가진 사용자는 공유 상품에 403이다. 존재하지 않거나 권한 밖인 `branchIds`도 403이다.
- 지점 교체와 이력 열람은 숨은 지점을 통한 우회를 막기 위해 저장 지점과 변경 후(또는 이력의 모든) 지점을 함께 검사한다.
- 모든 쓰기는 기존 `AuthorizedCommands` 잠금 순서(가드 → 직원 → 지점 → 상품 행)를 따르고, 권한 회수는 다음 요청부터 반영된다.

## 변경·이력·충돌

- 가격·조건·분류·판매 상태·판매 지점 변경은 상품 version을 올리고 `product_revisions`에 전체 스냅샷을 남긴다. 스냅샷은 분류 code·name을 복사하므로 분류 이름을 바꿔도 과거 이력이 바뀌지 않는다.
- 이력 `before`는 직전 revision의 스냅샷이며 최초 이력은 null이다. 정렬은 version 순서이고 같은 시각이어도 안정적이다.
- 낡은 version은 409 `VERSION_CONFLICT`(`latestVersion`, `reloadUrl=/api/products/{id}`)다. 같은 version을 동시에 보낸 쓰기는 하나만 성공한다.
- 내용이 달라지지 않는 요청(같은 값 PATCH, 이미 같은 상태의 판매 상태 요청, 같은 지점 집합)은 현재 상태를 돌려주며 version·revision·감사·이벤트를 만들지 않는다. 낡은 version이면 이 경우에도 409다.
- 상품 변경의 감사는 한 건이며 공유 상품은 소유 지점이 하나가 아니므로 `branch_id`를 비운다. 지점별 무효화 이벤트(`products`)는 변경 전·후 모든 지점에 대해 같은 트랜잭션에 기록한다. 분류 변경은 모든 지점에 기록한다.

## 판매 중지와 구매 연결

판매 중지는 신규 판매만 막고 상품 ID, 분류, revision, 판매 지점, 기존 구매 조건과 거래 참조를 삭제하지 않는다. 거래 유무와 관계없이 물리 삭제 경로가 없고, 결제·발급 참조는 DB의 `ON DELETE RESTRICT`가 보호한다.

후속 구매 구현은 같은 트랜잭션에서 `ProductService.requireSellable(productId, branchId)`를 호출한다. 상품 행에 공유 잠금을 걸고 판매 중지(409 `PRODUCT_NOT_ON_SALE`)나 판매 지점 아님(409 `PRODUCT_NOT_AVAILABLE_AT_BRANCH`)을 거부하며, 구매 시점의 조건 스냅샷(`ProductSnapshot`)을 반환한다. 이 스냅샷으로 `passes.snapshot_*`, `payments.product_name_snapshot`을 채우면 이후 상품 수정이 기존 구매에 소급되지 않는다.

## 이번 범위에서 제외

- 구매·결제·발급 API와 실제 구매 시점 호출(`requireSellable`은 호출 가능한 서비스 계약만 제공).
- `GET /workspace/lookups`의 `products`·`productCategories`는 계속 빈 배열이다. 상품 선택 데이터는 위 목록 API를 쓴다.
- 이전 스키마의 `DELETED` revision은 만들지 않는다.
