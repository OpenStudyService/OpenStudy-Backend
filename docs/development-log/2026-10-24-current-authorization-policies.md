# B03 현재 권한 판정·범위·관리자 보호

## 배경 / 문제

기존 로그인 세션은 계정 상태를 검사하지만 업무별 MANAGER/STAFF 역할과 행위 권한, 공유 상품 전체 범위, 관리자 회수의 동시성을 재사용 가능한 공개 정책으로 제공하지 않았다. 운영 중지 지점은 세션 지점 목록에서도 사라져 이력 조회와 신규 거래 제한이 섞여 있었다.

## 검토한 선택지

authorization에서 기존 employee/branch 서비스를 재사용하는 방법과 자체 비밀값 없는 JDBC projection을 만드는 방법을 검토했다. 이후 employee/branch 업무 명령이 authorization을 호출할 때 역방향 의존으로 순환하는 문제가 있어 자체 projection을 선택했다. 기존 snapshot의 일반 SELECT만으로 잠금 뒤 인가하는 방법은 InnoDB REPEATABLE_READ에서 오래된 권한을 볼 수 있으므로 쓰기 판정에는 locking current read를 사용했다.

## 결정 및 이유

기존 auth → employee/branch 호출을 유지하고 authorization은 자기 repository/dto만 사용한다. 현재 요청은 별도 MVC interceptor로 검사하며 /me와 인증 제한 세션의 동작 변경은 B04에서 처리한다. 업무 쓰기는 동기 callback을 같은 트랜잭션에서 인가·실행하는 AuthorizedCommands로 연결한다.

초기 기반 업무는 administration_guard로 직렬화한다. 마지막 관리자 계산의 추가 직원 잠금이 지점 뒤에 발생하지 않도록 employees 전체 ID순 → branches 전체 ID순 → 자식 순서로 미리 잠근다. 구현과 잠금 순서를 명확히 하는 선택이며 데이터/쓰기량 증가 시 범위 축소를 측정·검증해야 한다. 외부 네트워크, 별도 트랜잭션, 비동기 callback을 허용하지 않는다.

## 구현 내용

- 비밀번호 없는 AuthorizationState와 9종 grants의 BranchAction, 현재 계정·역할·grants·지점 범위 정책을 추가했다. all은 허용 지점의 합집합이다.
- MANAGER 역할과 grant를 함께 검사하며 STAFF의 people:manage로 관리자 명령을 호출할 수 없다. 자기 권한·OWNER 대상 소속 변경, 현재 MANAGER를 STAFF로 바꿔 편집하는 우회, 승인 전/회수 소속 직접 활성화를 거부한다. 계정 전역 변경과 MANAGER 초대 명령은 OWNER 전용이다.
- 공유 상품의 서버상 기존 지점과 요청 범위 전체 검사를 제공하고 목록/상세에 노출할 지점 교집합을 계산한다. 실제 상품 행 잠금과 전체 범위 재조회는 후속 상품 서비스가 수행한다.
- 읽기 가능한 중지 지점은 /me에 계속 표시한다. 신규 운영/결제 정책은 OWNER도 중지 지점을 우회하지 못한다.
- 현재 API handler의 요청별 scope 검사와 로컬 샘플 직원 선택 데이터의 관리 범위 필터를 연결했다.
- 같은 트랜잭션 인가와 변경, 마지막 활성 OWNER 보호 및 롤백을 제공한다. V1~V3와 DB 스키마는 변경하지 않았다.
- 모듈 간 service/dto 이외 참조와 순환을 검사하는 테스트, 역할/위임 정책·실제 MySQL 경합·실제 HTTP 조작 테스트를 추가했다.

## 검증 결과

- 전체 `test bootJar --no-daemon`: 73개 테스트, 실패 0건. Docker Testcontainers MySQL을 사용했으며 로컬 업무 DB에 접속하지 않았다.
- 최종 부모 잠금 callback 오버로드 추가 뒤 인가 정책·DB/HTTP 경합·모듈 경계 9개 테스트와 bootJar를 추가 확인했다. 전체 실행과 마지막 변경의 집중 검증을 구분한다.
- 실제 HTTP 로그인 후 scope/query/path/body 조작, STAFF 관리자 호출, 권한 회수 뒤 같은 세션의 새 쓰기 요청 거부를 확인했다.
- 두 DB 연결의 마지막 OWNER 동시 제한은 모두 409이고 계정 변경이 롤백됐다. OWNER가 없는 경우 두 MANAGER가 서로의 소속을 동시에 회수하려는 요청도 현재·요청 역할 정책으로 거부됐다.
- 쓰기와 회수를 직렬화하고 회수 커밋 이후 새 쓰기를 거부했다. 회수 전 만들어진 REPEATABLE_READ snapshot을 가진 트랜잭션도 잠금 후 새 권한으로 거부됐다.
- 최초 검증에서 테스트 fixture의 지점 코드 길이/상태값 오류를 수정했다. 전체 회귀에서 MVC slice의 새 인가 서비스 의존성이 누락된 문제를 찾아 명시적인 MockitoBean으로 수정했다.

## 남은 작업 / 보류 사항

지점/직원/초대/상품 업무 CRUD 자체는 B05/B06/B07/B10이다. 해당 명령에서 실제 대상 범위를 서버에서 읽고 공개 정책·트랜잭션 가드를 사용하며 version·감사·outbox와 SQL의 items/total 범위까지 검증해야 한다. 인증 제한 세션 완성과 유효 활동 시간은 B04, SSE 전달 직전 재검사와 스트림 폐기는 B11이다. 실제 운영 CRUD/SSE 경합 완료를 의미하지 않는다. 전체 범위 잠금의 성능 최적화와 데드락 재시도는 후속 업무 부하/잠금 통합 검증과 함께 판단한다.
