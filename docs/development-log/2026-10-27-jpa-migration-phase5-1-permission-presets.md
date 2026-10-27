# 2026-10-27 JPA 전환 5-1단계: 권한 프리셋

관련: [4단계 기록](2026-10-27-jpa-migration-phase4-ticket.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

5단계(직원·권한)는 저장소 4개(약 380줄, JDBC 호출 63회, 잠금 쿼리 17개)에 걸쳐 있다. 소속·소속 권한 테이블은 employee 모듈이 쓰고 있어 JPA-03에 따른 경계 이동도 필요하다. 그래서 사용자와 합의해 3개 PR로 나눴다.

- **5-1 권한 프리셋**: 독립적이고 작은 범위. 이 문서.
- **5-2 소속·권한의 authorization 이관**: JPA-03 경계 이동.
- **5-3 직원 계정**

5-1의 대상은 `PermissionPresetRepository`(권한 정의·프리셋·프리셋 권한)다. 이 테이블을 쓰는 곳은 이 저장소 하나뿐이다. 다른 모듈(직원 관리, 초대)은 `PermissionPresetService.validateCopy`로만 접근한다.

옮기기 전에 확인한 기존 동작은 다음과 같다.

- 수정은 이름·권한이 같아도 항상 `version=version+1`이었다.
- 권한은 삭제 후 재삽입했다.
- `(name, role)` UNIQUE 위반은 JDBC `DuplicateKeyException`을 잡아 `DUPLICATE_PRESET`(409)으로 바꿨다.
- 잠금 조회(`find(id, true)`)는 프리셋 행을 `FOR UPDATE`, 권한 행을 `FOR SHARE`로 읽었다.
- 기존 테스트는 프리셋 이름 수정 1건(초대 테스트 안)만 다루고, 생성·중복·같은 내용 수정은 다루지 않았다.

## 2. 검토한 선택지

- **권한 연결**: 요소 컬렉션(권한 ID) / 권한 정의 엔티티와의 `@ManyToMany`. 권한 정의는 같은 authorization 모듈의 엔티티이고 화면에는 코드가 필요하므로 `@ManyToMany Set`을 택했다. Hibernate는 Set에서 바뀐 행만 삭제·삽입한다.
- **잠금 조회**
  - 프리셋에 `@Lock(PESSIMISTIC_WRITE)`를 걸고 권한을 함께 조회: 조인된 권한 정의·연결 행까지 `FOR UPDATE`가 되어 기존보다 강한 잠금이 된다.
  - 프리셋만 `@Lock`, 권한은 native `FOR SHARE`: 기존과 같은 잠금이다. 이 방식을 택했다.
- **중복 판정**: 3단계 상품 분류와 같은 이유로, 가드 잠금 아래에서 저장 전에 `exists` 확인으로 바꿨다.

## 3. 결정 및 이유

- 엔티티: `PermissionDefinition`(읽기 전용 사전), `PermissionPreset`(`@Version`, JPA-02), `BranchRole`(5-2의 소속에서도 사용).
- `PermissionPresetRepository`는 Spring Data 인터페이스로 바꾸고, `PermissionDefinitionRepository`를 추가했다.
  - 권한 행 `FOR SHARE` 조회만 native다(JPA-01 잠금 예외).
- version은 3·4단계와 같은 방식이다. flush 후 그대로면 JPQL로 정확히 1 올리고 `refresh`한다.
- 허용 권한 목록(`CODES`), 검증, 멱등·감사·outbox 처리는 저장소에서 `PermissionPresetService`로 옮겼다. 서비스의 공개 메서드 시그니처는 그대로이므로, `validateCopy`를 호출하는 직원 관리·초대 코드는 바뀌지 않는다.

## 4. 구현 내용

- `authorization/entity`: `PermissionDefinition`, `PermissionPreset`, `BranchRole`. `.gitkeep`은 제거했다.
- `authorization/repository`: `PermissionPresetRepository`(JDBC 클래스 → 인터페이스), `PermissionDefinitionRepository`(신규).
- `authorization/service/PermissionPresetService`: 엔티티 기반으로 재작성. 처리 순서(소유자 명령 → 입력 검증 → 권한 검증 → 멱등 재요청 → 중복 확인 → 저장 → 감사·이벤트)는 그대로다.
- 신규 `PermissionPresetJpaBehaviorTests`(4개)
  - 생성: 권한 정렬, 연결 행, 같은 키 재요청, 같은 이름·역할 중복 거절, 다른 역할은 허용
  - 같은 내용 수정의 version +1, 권한 일부 교체 후 연결 행
  - 다른 프리셋 이름으로 변경 시 중복 거절
  - 목록 결과가 삭제한 기존 SQL·변환 코드의 결과와 같은지

## 5. 검증 결과

- 집중 실행: 프리셋을 쓰는 기존 테스트(권한 6·2, 직원 관리 10, 초대 관리 16·수락 7·발송 13 등)를 포함한 80개와 신규 4개가 통과했다. 모듈 경계 테스트도 통과했다.
- 전체 회귀: `test` **232개/33개 suite**(4단계 병합 후 228개 + 신규 4개), 실패·오류·건너뜀 0, 5분38초 BUILD SUCCESSFUL. 기존 테스트는 수정하지 않았다.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 5-2: 소속·소속 권한 엔티티를 authorization에 두고, employee 모듈의 소속 쓰기를 authorization 공개 서비스로 옮긴다. `AuthorizationRepository`의 잠금·권한 상태 조회는 native query로 옮긴다.
- 5-3: 직원 계정 전환.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
