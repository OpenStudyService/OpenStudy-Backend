# 2026-10-29 M4 고객 등록·검색·수정·상태와 지점 범위

관련: Issue [#56](https://github.com/OpenStudyService/OpenStudy-Backend/issues/56), [계약 문서](../design/foundation/customer-sales-api.md), [ADR-M4-002](../db/ADR-M4-002.md) M4-D05·D06, [#55 계약 기록](2026-10-29-m4-customer-sales-contract.md).

## 1. 배경 / 문제

#55에서 고객 API 계약과 V2 스키마(`registered_branch_id`, `memo`)를 확정했지만 `modules/customer`는 빈 골격이었다. 결제·발급(#57)은 판매 전에 고객의 조회 범위와 상태를 확인해야 하므로, 고객 원장의 등록·검색·수정·상태 변경과 지점 범위를 먼저 구현해야 했다.

## 2. 검토한 선택지

- **목록·검색 처리 위치**
  - 상품 목록처럼 전체 행을 읽은 뒤 메모리에서 거르기: 기존 패턴과 같지만, 고객 원장은 상품보다 훨씬 커지고 "전체 추출 방지"가 인수 조건이다.
  - SQL에서 범위·조건·정렬·페이지를 처리: 쿼리를 직접 조립해야 하지만 요청마다 한 페이지만 읽는다.
- **쓰기 권한 판정**
  - 기존 `AuthorizedCommands.inBranches`: 전달한 지점 **모두**에서 권한을 요구한다. 계약은 등록 지점 또는 결제 지점 중 **하나 이상**의 `members:write`다.
  - `inAnyBranch` 추가: 같은 잠금 순서를 유지하면서 "하나 이상" 조건을 표현한다.
- **감사 기록 내용**
  - 상품처럼 변경 전후 스냅샷 전체 저장: 추적은 쉽지만 이름·연락처·이메일·메모가 append-only 감사 테이블에 복제된다. 이후 개인정보 파기(M11) 범위가 넓어진다.
  - 변경된 필드 이름과 상태값만 저장: 값은 남지 않지만 "누가·언제·무엇을" 바꿨는지는 남는다.

## 3. 결정 및 이유

- **목록·검색은 SQL(`CustomerSearchRepository`)에서 처리한다.** 정렬 열은 닫힌 enum에서만 오고 값은 모두 바인딩한다. 지점이 비어 있으면 `IN ()`을 만들지 않고 빈 결과를 돌려준다.
- **쓰기는 `AuthorizedCommands.inAnyBranch`로 처리한다.** 고객 행 잠금 뒤에 관련 지점(등록 지점 + 결제 지점)을 다시 읽어 판정하므로, 판정 기준이 클라이언트 값이 아니라 저장된 값이다. OWNER는 관련 지점이 없는 고객(V2 이전 미지정 행)도 수정할 수 있다.
- **감사에는 필드 이름·상태값만 남긴다.** FR-MEM-04가 요구하는 것은 변경자와 시각이다(행위자·발생 시각은 감사 행 자체에 있다). 상태 변경 사유는 `customer_status_events`에 남는다.
- **조회 범위 판정(404)을 권한 판정(403)보다 먼저 한다.** 범위 밖 고객에 대한 수정 요청이 403을 받으면 존재 여부가 드러나기 때문이다.
- **미지정 고객(`registered_branch_id` NULL)은 OWNER의 `scope=all`에서만 보인다.** 특정 지점 scope는 그 지점과 관련된 고객만 의미하기 때문이다.
- **결제 지점은 `payments` 테이블을 고객 저장소에서 직접 읽는다.** 아직 결제 모듈에 엔티티·서비스가 없다(#57). 결제 모듈이 진입점을 제공하면 그쪽으로 옮긴다.
- **회원번호는 `M-` + 10자리 무작위 문자(혼동 문자 제외)로 만든다.** 형식은 계약이 아니므로 바꿀 수 있다.
- 기존 코드 스타일과 달리 새 클래스에는 Lombok(`@RequiredArgsConstructor`, `@Getter`)을 썼다. 저장소 방침(#130 이후)을 따랐다.

## 4. 구현 내용

- `modules/customer`
  - `entity`: `Customer`(version 낙관적 잠금, 등록 지점 변경 불가), `CustomerStatusEvent`(append-only), `CustomerStatus`
  - `repository`: `CustomerRepository`(행 잠금, 결제 지점 조회), `CustomerSearchRepository`(범위·검색·정렬·페이지 SQL), `CustomerStatusEventRepository`
  - `service`: `CustomerService`(목록·상세·등록·수정·상태 변경), `CustomerInputs`(입력 규칙과 상태 표시값)
  - `controller`: `CustomerController`(`/api/customers`, DELETE 매핑 없음)
- 공통
  - `AuthorizationService.permitsAny/requireAny`, `AuthorizedCommands.inAnyBranch` 추가
  - `WorkspaceEventService`가 `customers` 이벤트를 전달하도록 리소스 매핑 추가. 이벤트의 branchId는 등록 지점이며, 미지정 고객은 이벤트를 만들지 않는다.
- 문서: OpenAPI의 고객 operation 5개를 `implemented`로 바꾸고, 계약 문서 머리말에 구현 상태를 적었다.

동작 요약:

- 등록: 이름(1~100자)·연락처(하이픈·공백 제거 후 숫자 9~11자리) 필수, 이메일·메모 선택(빈 문자열은 null). `Idempotency-Key` 필수, 같은 키·같은 입력은 최초 응답 재생, 다른 입력은 409 `IDEMPOTENCY_CONFLICT`. 운영 중지 지점은 409 `BRANCH_NOT_ACTIVE`.
- 검색: 이름 부분 일치, 회원번호 정확 일치, 숫자 4자리 이상이면 연락처 부분 일치. pageSize 상한 100. 목록 항목은 7개 필드만 담는다.
- 수정: version 불일치 409 `VERSION_CONFLICT`. 회원번호·등록 지점·상태는 422 `IMMUTABLE_FIELD`. 같은 값이면 version·감사·이벤트를 만들지 않는다.
- 상태 변경: 사유 필수, `customer_status_events`에 이전·새 상태·사유·처리자 기록. 같은 상태면 이력을 만들지 않는다. 결제·이용권 행은 바꾸지 않는다.

## 5. 검증 결과

- `CustomerIntegrationTests`(Testcontainers MySQL, HTTP·DB 통합) 9개 통과
  - 등록 입력 검증·연락처 정규화·멱등 재생/충돌·감사에 연락처 미포함
  - `members:write` 없음·다른 지점·없는 지점 403, 운영 중지 지점 409, 비로그인 401
  - 범위 밖 상세 404·목록과 total 제외, 결제(실패 결제 포함)로 범위 확장, 미지정 고객은 OWNER `scope=all`에서만 조회
  - 검색 조건(이름·회원번호 정확 일치·연락처 4자리 이상), 이메일·`%`로 검색 불가, 목록 필드 7개, pageSize 101·허용 외 정렬·상태 422
  - 수정 version 충돌·불변 필드·권한 없는 변경 403·DELETE 405, 결제 지점의 작성자 수정 허용
  - 같은 version 동시 수정은 1건만 성공
  - 상태 변경 사유 필수·이력 기록·같은 상태 무변경·비활성 후 결제 행 유지
  - 상위 트랜잭션 롤백 시 고객·이력·감사·이벤트가 함께 롤백
- 전체 테스트(`./gradlew test`, 42개 클래스 270개): 265개 통과, `LocalPreviewIntegrationTests` 5개 실패
  - 첫 실행에서는 `PersistenceAccessTests.nativeSqlLivesOnlyInRepositories`도 실패했다. 네이티브 SQL은 `*Repository.java`에만 둘 수 있는 규칙이었다. 검색 클래스 이름을 `CustomerSearch`에서 `CustomerSearchRepository`로 바꿔 통과시켰다.
  - LocalPreview 실패는 이번 변경과 무관하다. 작업 디렉터리의 개인 `.env`(git 무시)에 `openstudy.local-preview.enabled=false`가 있다. 애플리케이션이 이 파일을 읽으므로 미리보기 지점 시드가 만들어지지 않는다. `.env`가 없는 `origin/main` 체크아웃에서는 같은 5개 테스트가 모두 통과했다.
- 미검증: Frontend 연동, 실제 운영 DB 데이터량에서의 검색 성능(인덱스 사용 여부 확인 안 함), SSE로 `customers` 이벤트가 클라이언트에 도착하는지(매핑만 추가, 별도 테스트 없음).

## 6. 남은 작업 / 보류 사항

- 결제 모듈(#57)이 생기면 `CustomerRepository.paymentBranchIds`와 `CustomerSearchRepository`의 `payments` 직접 조회를 결제 모듈 진입점으로 옮길지 정한다.
- #57은 결제 전에 고객 조회 범위·상태 확인이 필요하다. 이번에는 다른 모듈용 공개 메서드를 만들지 않았다. #57에서 필요한 형태로 추가한다.
- 이름 부분 일치(`LIKE '%q%'`)는 인덱스를 쓰지 못한다. 고객 수가 많아지면 검색 방식을 다시 검토한다.
- 미지정 고객에게 등록 지점을 지정하는 경로는 계약대로 후속 결정 사항이다.
- 타 지점 고객 최소 요약(M6), 구매·사용 내역 탭(M5), 중복 병합(M13), 파기(M11)는 범위 밖이다.
