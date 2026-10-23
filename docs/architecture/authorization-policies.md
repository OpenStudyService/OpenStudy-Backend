# 현재 권한과 업무 쓰기 경계

작성일: 2026-10-24. B03 구현 범위이며 지점·직원·상품 CRUD 자체는 후속 이슈에서 구현한다.

## 모듈 호출 방향

기존 `auth → employee.service/dto`, `auth → branch.service/dto`를 유지한다. 신규 `authorization.service → authorization.repository/dto`는 자체 JDBC projection만 사용한다. 비밀번호 해시를 받거나 employee/branch의 Repository/Entity에 접근하지 않는다. 현재 요청은 authorization 소속 MVC interceptor에서 검사한다. `localpreview → auth.service/dto + authorization.service/dto`로 샘플도 같은 허용 범위를 사용한다.

후속 employee/branch/ticket 명령은 `→ authorization.service/dto`를 호출할 수 있다. authorization이 이들 모듈을 역참조하지 않아 순환이 생기지 않는다. `ModuleBoundaryTests`는 모듈 간 service/dto 이외 import와 순환을 검사한다.

## 조회 정책

`AuthorizationService.current`는 매 호출 현재 DB 계정 상태를 검사한다. `scope(actor, scope, action)`에서 all은 해당 행위가 가능한 지점 ID의 합집합이며 세션 권한·화면 메뉴·클라이언트 canEdit를 신뢰하지 않는다. 관리 명령은 MANAGER 역할과 명시적 grant를 함께 요구한다. STAFF의 people:manage는 관리자 명령 권한을 만들지 않는다.

지점 상태 SUSPENDED에서도 소속이 유효하면 READ와 이력·설정 조회가 가능하다. NEW_PAYMENT/NEW_OPERATION은 ACTIVE 지점에서만 가능하다. OWNER도 운영 중지의 신규 거래 제한을 우회하지 않는다. 일반 상품 구성 변경과 관리자 전용 판매 상태/범위 변경은 서로 다른 BranchAction이다.

공유 상품은 서버가 조회한 전체 기존 지점과 요청된 새 지점의 합집합을 검사한다. 목록/상세는 `visibleProductBranches` 교집합만 직렬화한다. 이력은 현재·과거의 전체 지점에 MANAGE_PRODUCT를 요구한다. 상품 부모 행을 잠근 뒤 실제 적용 범위를 다시 읽고 검사해야 하며 클라이언트가 보낸 범위로 기존 범위를 대체하면 안 된다.

## 쓰기와 관리자 보호

`AuthorizedCommands.inBranches`, `membershipChange`, `accountChange`는 동기 callback을 같은 Spring 트랜잭션에 묶는다. callback은 본인의 입력/version 검증, 업무 부모 잠금, 실제 변경, 감사/outbox 저장을 담당한다. callback에서 REQUIRES_NEW·비동기 작업·외부 네트워크를 사용하면 원자성을 보장하지 못한다. 사전 조회 권한 검사만 호출하고 별도 트랜잭션에서 쓰지 않는다.

기존 product/invitation 부모가 있는 명령은 `inBranches(actor, ids, action, lockBusinessParent, command)` 오버로드를 사용한다. 부모 잠금 callback이 먼저 실행되고 membership/grants current read와 인가가 이어진다. 최종 command에서 실제 부모 범위를 다시 읽어 추가 검증한다. 부모 잠금 callback에서는 데이터 변경·외부 호출을 하지 않는다. 부모가 없는 명령만 간단한 오버로드를 사용한다.

공유 상품처럼 부모 잠금 뒤 전체 범위가 확정되는 command는 `Function<AuthorizationState,T>` callback을 사용한다. 전달받은 fresh state와 부모의 실제 지점 집합을 `requireSharedProduct(state, persisted, requested, ...)`에 전달한다. 읽기 화면용 actor ID helper를 write callback의 최종 인가로 재호출하면 기존 RR snapshot을 볼 수 있으므로 사용하지 않는다.

잠금은 administration_guard → employees ID 오름차순 → branches ID 오름차순 → 업무 부모 → memberships/grants 순서다. 이 단계는 단일 guard로 기반 쓰기를 직렬화하며 관리자 후보 전체 employees와 branches를 ID순으로 미리 잠근다. 마지막 관리자 계산에서 다른 직원 잠금을 지점 뒤에 잡는 역순을 피하고 구현을 단순하게 유지하기 위한 선택이다. 데이터/쓰기량이 늘면 측정과 경합 테스트 후 범위 잠금을 줄여야 한다.

일반 조회는 읽기 전용 projection, 잠금 이후 쓰기 판정은 FOR SHARE current read를 사용한다. 호출자가 이미 REPEATABLE_READ snapshot을 만든 트랜잭션이어도 회수 전 권한을 재사용하지 않는다. 마지막 활성 OWNER 제거는 LAST_ADMIN_REQUIRED/409로 callback 변경까지 롤백한다. 지점 유효 관리자는 ACTIVE OWNER 또는 ACTIVE 계정의 ACTIVE MANAGER 소속이다.

소속 변경은 현재 역할과 요청 역할을 모두 검사한다. 자기 소속·OWNER 대상 변경은 거부하고 MANAGER는 다른 STAFF만 관리한다. PENDING/REVOKED 소속의 직접 활성화는 거부한다. 계정 전역 변경은 OWNER만 가능하다. 초대 명령도 현재 대상 역할을 포함해 MANAGER 우회 강등을 막는다.

## 현재 연결과 후속 검증

현재 존재하는 API handler에는 요청별 계정/scope 검사가 연결됐다. 로컬 샘플 직원 선택 데이터도 관리 가능한 지점만 반환한다. /me는 기존 승인 제한 응답을 위해 별도 인증 경계를 유지하며, 인증 제한 세션 완성은 B04다.

실제 업무 목록은 반환 items뿐 아니라 COUNT/total에도 동일한 허용 지점 집합을 SQL 조건으로 적용해야 한다. MVC interceptor의 request attribute만으로 업무 서비스가 완료되는 것은 아니다. B05/B06/B07/B10에서 운영 명령 callback, 대상/상태/version 재검사, 감사/outbox와 실제 HTTP 거절 검증을 추가한다. B11은 권한 회수 이후 SSE 전달 직전 검사와 스트림 종료를 구현한다.

검증은 AuthorizationPolicyTests(3역할/2지점/위임), AuthorizationIntegrationTests(실제 MySQL·HTTP·회수와 쓰기 경합·오래된 snapshot·마지막 OWNER 동시 제한), ModuleBoundaryTests 및 기존 전체 회귀를 사용한다. 실제 운영 CRUD나 SSE 경합을 완료했다고 주장하지 않는다.
