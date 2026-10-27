# 2026-10-27 JPA 전환 3단계: 지점 관리

관련: [2단계 기록](2026-10-27-jpa-migration-phase2-simple-reads.md), [0단계 기록](2026-10-27-jpa-migration-phase0.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택), M1-04.

## 1. 배경 / 문제

지점 관리(목록·상세·생성·수정·운영 상태 변경, 주간 운영시간)는 `BranchRepository`의 JDBC SQL로 구현되어 있었다. 0단계에서 만든 `Branch`·`BranchWeeklyHour` 엔티티를 이번에 처음으로 실제 쓰기에 사용한다.

옮기기 전에 확인한 기존 SQL의 동작은 다음과 같다.

- 운영시간은 수정 때마다 해당 지점 행을 모두 지운 뒤 7행을 다시 삽입했다. `(branch_id, weekday)` UNIQUE 키가 있다.
- 수정·상태 변경은 값이 같아도 항상 `version=version+1`을 실행했다. 기존 HTTP 테스트도 version 1 → 2 증가와 409 충돌을 확인한다.
- UPDATE 문에는 version 조건이 없었다. 다만 모든 지점 명령은 `AuthorizedCommands`에서 가드 → 직원 → 지점 전체를 `FOR UPDATE`로 잠근 뒤 실행되므로 지점 명령끼리는 이미 직렬화되어 있다.
- 일정이 없는 기존 지점은 7개 요일을 휴무로 채워 보여 주었고, 수정하면 그 7행이 실제로 저장되었다.
- 전화번호가 NULL인 지점은 응답에서 `""`로 보였다. 전화번호를 생략하고 수정하면 `""`가 저장되었다.
- `created_by`/`updated_by`는 채우지 않았고, 요청자는 감사 기록에만 남았다.

## 2. 검토한 선택지

**운영시간 교체**
- 컬렉션을 비우고 새 행 추가(`orphanRemoval`): Hibernate는 flush할 때 INSERT를 orphan DELETE보다 먼저 실행하므로 UNIQUE 키 충돌이 난다.
- 요일별 기존 행을 제자리에서 갱신하고 없는 요일만 추가: 충돌이 없고, 최종 DB 상태(7행과 각 값)가 기존과 같다. 달라지는 점은 행 ID가 유지된다는 것뿐이다(API에 노출되지 않음).

**항상 version +1**
- `@Version` 자동 증가만 사용: 지점 행이 바뀐 경우에만 증가한다. 운영시간만 바꾸거나 값이 같으면 증가하지 않아 기존 계약과 다르다.
- `PESSIMISTIC_FORCE_INCREMENT` 잠금으로 강제 증가: 처음 구현했으나, 같은 영속성 컨텍스트에서 두 번째 명령의 증가가 누락되었다. Hibernate는 이미 그 수준의 잠금을 가진 엔티티에 대한 재요청을 건너뛴다. 아래 테스트가 이 문제를 잡아냈다.
- flush 후 version이 그대로면 JPQL 일괄 UPDATE로 1 증가: 영속성 컨텍스트의 잠금 상태와 무관하게 정확히 1만 증가한다.

**저장소 구조**
- 2단계의 `SessionBranchRepository`와 지점 관리 저장소를 따로 두기, 또는 지점 집합(지점 + 운영시간)당 저장소 하나로 합치기.

## 3. 결정 및 이유

- 운영시간은 요일별 제자리 갱신(`Branch.setWeeklyHour`)으로 바꿨다.
- version은 `@Version`을 유지(JPA-02)하면서, 변경 후 flush했을 때 증가하지 않았으면 JPQL `incrementVersion`으로 정확히 1 올린다. 그 뒤 `refresh`로 DB가 관리하는 `updated_at`을 다시 읽는다. 이 과정은 `BranchService.saveChange`에 모았다.
- 저장소는 Spring Data `BranchRepository` 하나로 합치고 `SessionBranchRepository`를 삭제했다. `SessionBranchService`도 같은 저장소를 쓴다.
- 엔티티 → API DTO 변환은 `BranchViews`(service 패키지 내부)로 옮겼다. 일정 보정, 표시 문구, 전화번호 `""` 변환은 기존과 같다.
- 감사·outbox·멱등 래퍼 메서드는 저장소에서 제거했다. 1단계 기록의 후속 항목에 따라 `BranchService`가 공통 컴포넌트를 직접 호출한다.
- `created_by`/`updated_by`는 기존처럼 비워 둔다. 동작 동등성을 지키는 것이 이 단계의 목적이므로 채우는 것은 별도 결정으로 남긴다.

## 4. 구현 내용

- `branch/entity/Branch`: 0단계 시험용 `rename`·`addWeeklyHour`를 `update(name, address, phone)`·`changeStatus`·`setWeeklyHour`로 대체했다. `BranchWeeklyHour.change`(패키지 내부)를 추가했다.
- `branch/repository/BranchRepository`: JDBC 클래스 → `JpaRepository<Branch, Long>`.
  - `findWithWeeklyHoursById`, `findAllWithWeeklyHoursByOrderByIdAsc`: `@EntityGraph`로 운영시간을 함께 조회한다.
  - `findAccessible`(native EXISTS, 2단계에서 이동), `findByIdIn`, `incrementVersion`.
- `branch/service/BranchService`: 엔티티 기반으로 재작성. 권한·잠금 순서(`AuthorizedCommands`)와 검증 규칙은 바꾸지 않았다.
- `branch/service/BranchViews`: DTO 변환.
- 목록·조회 쿼리 수 감소: 기존에는 지점 ID 조회 1회 + 지점당 2회였다. 이제 운영시간을 포함한 조회 1회다.
- `JpaMappingTests`: 바뀐 엔티티 메서드 이름에 맞춰 호출 2곳만 수정했다. 검증 내용은 같다.
- 신규 `BranchJpaBehaviorTests`(4개): HTTP 테스트가 고정하지 않던 기존 동작을 확인한다.
  - 값이 같은 수정·같은 상태로의 변경도 version이 정확히 1씩 증가
  - 운영시간만 수정 / 이름과 운영시간을 함께 수정 모두 1 증가, 운영시간 행 ID 유지, 표시 문구
  - 일정이 없는 기존 지점: 7일 휴무 표시, 수정 후 7행 저장, 전화번호 `""` 저장
  - 목록 응답과 상세 응답이 같은지

## 5. 검증 결과

- `BranchJpaBehaviorTests` 첫 실행에서 1건 **실패**했다. 같은 트랜잭션에서 두 번째 "같은 상태로 변경"의 version이 증가하지 않았다. `PESSIMISTIC_FORCE_INCREMENT` 방식을 JPQL 증가로 바꾼 뒤 통과했다. 실패 실행은 성공으로 집계하지 않는다.
- 집중 실행: `BranchIntegrationTests` 4개(version 1→2·409 충돌·역할 범위·멱등·롤백), `BranchJpaBehaviorTests` 4개, `JpaMappingTests` 3개, `JpaSimpleReadsTests` 2개 통과.
- 전체 회귀: `test` **224개/31개 suite**(2단계 병합 후 220개 + 신규 4개), 실패·오류·건너뜀 0, 5분46초 BUILD SUCCESSFUL.
- 실제 서버 기동·브라우저 확인은 수행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 지점의 `created_by`/`updated_by`를 채울지는 별도 결정이 필요하다(현재 감사 기록에만 요청자가 남는다).
- `AuthorizationRepository.lockBranches`와 `LocalPreviewConfiguration`은 아직 JDBC로 `branches`를 다룬다. 각각 5단계(employee·authorization)와 7단계(정리)에서 처리한다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
