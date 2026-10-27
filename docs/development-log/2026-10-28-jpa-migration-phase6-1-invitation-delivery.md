# 2026-10-28 JPA 전환 6-1단계: 초대 메일 발송(워커)

관련: [5-3단계 기록](2026-10-28-jpa-migration-phase5-3-employee-accounts.md), [2단계 기록](2026-10-27-jpa-migration-phase2-simple-reads.md), [설계 선택 기록 JPA-01~04](../design-decisions/decision-register.md#2026-10-27-jpa-전환-착수-전-선택).

## 1. 배경 / 문제

6단계(초대 모듈)는 저장소 2개(약 355줄, JDBC 36회, 잠금 쿼리 10개)다. 사용자와 합의해 다음처럼 나눴다.

- **6-1 메일 발송 워커**: 이 문서.
- **6-2 초대 관리·수락**

두 저장소는 초대·발송 작업 테이블을 함께 쓴다. 다만 워커는 자체 트랜잭션(`TransactionTemplate`, `REQUIRES_NEW`)에서만 돌고, HTTP 명령은 워커 저장소를 호출하지 않는다. 그래서 한쪽만 JPA로 바꿔도 같은 트랜잭션 안에서 JPA와 JDBC가 같은 행을 다루는 일은 없다.

`InvitationDeliveryRepository`의 기존 동작은 다음과 같다.

- 후보 조회는 잠금 없이 `ORDER BY id LIMIT`으로 훑는다. 실제 전이는 가드 → 초대 `FOR UPDATE` → 작업 `FOR UPDATE` 순서로 잠근 뒤 실행한다.
- 전이(점유, 발송 완료, 재시도 예약, 소진, 건너뛰기, 만료)는 모두 조건 없는 UPDATE다. 초대 행은 `updated_by=NULL`, `version=version+1`로 매번 정확히 1 증가한다.
- 서비스는 조회 시점의 값 레코드(`InvitationState`, `Job`)로만 판단한다.
- 워커 테스트 1곳은 감사 기록 실패를 흉내 내려고 저장소 클래스를 상속해 `changed`를 덮어쓴다.

## 2. 검토한 선택지

- **엔티티 상태 변경 + flush**: 3~5단계처럼 "flush 후 version이 그대로면 1 증가" 처리가 전이마다 필요하다. `updated_by`가 이미 NULL인 전이(발송 완료 등)도 많아 이 처리가 거의 모든 전이에 붙는다.
- **기존 UPDATE와 1:1인 JPQL 일괄 UPDATE**: 전이마다 문장 하나라서 version 증가 횟수와 실행 순서가 기존과 정확히 같다. 잠금은 엔티티 `@Lock` 조회로 걸고, 서비스에는 지금처럼 값 레코드를 넘긴다.
- **저장소 구조**: Spring Data 인터페이스로 완전히 바꾸면 워커 테스트의 상속 기반 장애 주입을 쓸 수 없다. `@Repository` 클래스와 공개 API를 유지하고 내부만 Spring Data 저장소 2개로 바꿨다.

## 3. 결정 및 이유

- JPQL 일괄 UPDATE 방식을 택했다. 모든 전이 쿼리는 `flushAutomatically`·`clearAutomatically`로 실행한다. 그래서 같은 트랜잭션의 다음 잠금 조회는 DB의 최신 값을 읽고, 영속성 컨텍스트에 오래된 엔티티가 남지 않는다.
- 가드 잠금(`administration_guard FOR UPDATE`)은 invitation 모듈이 authorization 저장소를 호출할 수 없으므로 같은 문장을 native로 둔다(JPA-01 잠금 순서 예외).
- 새 재시도 작업만 엔티티 저장(`InvitationSendJob.retry`)으로 삽입한다. 실행 순서(이전 작업 종료 → 초대 revision 증가 → 새 작업 삽입)는 그대로다.
- `InvitationDeliveryService`는 바꾸지 않았다.

## 4. 구현 내용

- `invitation/entity`
  - `InvitationSendJob`, `SendJobStatus` 추가
  - `EmployeeInvitation`에 `getTokenHash` 추가, 주석 갱신
- `invitation/repository`
  - `InvitationRepository`(신규): 초대 잠금 조회, 전이 JPQL 5개(토큰 부착, 발송 완료 표시, revision 증가, 토큰 제거, 만료). 6-2에서 확장한다.
  - `InvitationSendJobRepository`(신규): 무잠금 후보 조회(`Limit`), 작업 잠금 조회, 가드 잠금(native), 전이 JPQL 4개
  - `InvitationDeliveryRepository`: `JdbcTemplate` 대신 위 두 저장소를 쓴다. 공개 레코드와 메서드는 그대로다.
- 테스트
  - `InvitationEmailWorkerIntegrationTests`: 저장소를 직접 생성하는 1곳의 생성자 인자만 바꿨다. 검증 내용은 그대로다.
  - 신규 `InvitationDeliveryJpaTests`(3개)
    - 후보 조회가 삭제한 기존 SQL 결과와 같은지(기한·임대 조건, 정렬, LIMIT), 잠금 조회 레코드 값
    - 점유·발송 완료 후 초대·작업 행(version, `updated_by`, 토큰, 시도 횟수, 임대)
    - 재시도 예약·소진·건너뛰기·만료 후 행(revision, 오류 코드, 새 작업의 시도 횟수·예정 시각, 만료 시 열린 작업만 SKIPPED)

## 5. 검증 결과

- 집중 실행: 초대 테스트 47개(워커 동시성·재시도·임대·장애 주입 13개 포함)와 매핑·경계 테스트가 통과했다. 신규 3개도 통과했다.
- 전체 회귀: `test` **244개/36개 suite**(5-3 병합 후 241개 + 신규 3개), 실패·오류·건너뜀 0, 5분47초 BUILD SUCCESSFUL.
- 실제 SMTP 발송과 서버 기동은 수행하지 않았다(테스트 메일 발송기 사용).

## 6. 남은 작업 / 보류 사항

- 6-2: 초대 관리·수락 전환. 초대 권한은 권한 ID 집합으로 매핑하고, 코드 ↔ ID 변환은 authorization 공개 서비스로 받는다.
- 원격 main 반영과 PR 병합은 사용자가 수행한다.
