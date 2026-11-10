# 2026-11-10 V16 추가 뒤 스키마 버전 고정 테스트 수정

## 1. 배경 / 문제

- #120(PR #211) 작업 중 클래스패스 전달 방식을 바꾼 것을 확인하려고 `OwnerBootstrapTests`를 실행했다. 10건 중 1건이 실패했다.
  ```
  createsOneActiveOwnerAndAuditWithoutMembershipOrSecretAuditData
  expected: "15"
   but was: "16"
  ```
- 원인
  - #119(PR #210, 병합됨)가 `V16__password_reset_tokens.sql`을 추가했다.
  - 그런데 최신 스키마 버전·마이그레이션 수·테이블 수를 고정한 테스트를 함께 고치지 않았다.
  - #119 때 마이그레이션을 추가하고도 `slowTest`를 실행하지 않아 놓쳤다. 개발 기록에는 "마일스톤 때 `slowTest` 실행"으로 미뤄 두었다.
- 앱 동작에는 영향이 없다. main에서 해당 테스트의 기대값만 틀린 상태였다.

## 2. 검토한 선택지

- #120 PR에 함께 넣는 방법이 있었다. 그러나 #120과 관계없는 #119의 누락이라 커밋 범위 규칙에 맞지 않았다.
- 그래서 main 기준 별도 fix 브랜치로 고쳤다. 사용자 결정(2026-11-10)이다.

## 3. 결정 및 이유

- 이전 마이그레이션 PR(V12~V15)처럼 고정값을 V16 기준으로 올렸다.
- 다른 마이그레이션처럼 V16 제약을 확인하는 테스트를 추가했다. #119에서 빠졌던 DB 검증을 채우기 위해서다.

## 4. 구현 내용

- `FoundationMigrationTests`
  - 실행 마이그레이션 15→16, 버전 목록에 `"16"`, 테이블 77→78
  - V6부터의 부분 마이그레이션 10→11
  - V15 전용 테스트가 최신 버전까지 올리면서 실행 건수 1을 기대하던 것을 `flyway("15")`로 고정했다.
  - 새 테스트 `v16KeepsAResetTokenHashOnlyWhileTheLinkCanBeUsed`
    - `SENDING`·`SENT`에만 해시·만료가 있다는 CHECK 두 개
    - 해시 유일 키
    - 직원 삭제 시 함께 삭제
- `DatabaseInitializationTests`: 버전 `"16"`, 테이블 78
- `OwnerBootstrapTests`: 버전 `"16"`

## 5. 검증 결과

- 실행함: `./gradlew test --tests FoundationMigrationTests --tests OwnerBootstrapTests --tests DatabaseInitializationTests`
  - `FoundationMigrationTests`: 23건 통과(새 V16 테스트 포함)
  - `OwnerBootstrapTests`: 10건 통과
  - `DatabaseInitializationTests`: 4건 통과
- 실행하지 않음: 전체 `./gradlew test`. 통합·전체 테스트는 마일스톤 검증 때 실행한다(사용자 지시 2026-11-10).

## 6. 남은 작업 / 보류 사항

- 마이그레이션을 추가하는 PR은 이 세 테스트의 고정값을 함께 올려야 한다.
- 앞으로는 마이그레이션 PR에서 통합 테스트를 미루더라도 이 세 클래스만은 실행한다. 이번처럼 main이 깨진 채 병합되는 것을 막기 위해서다.
