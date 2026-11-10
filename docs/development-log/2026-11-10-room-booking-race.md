# 2026-11-10 스터디룸 부분 겹침 동시 예약의 간헐적 500 (#201)

## 1. 배경 / 문제

- PR #200 작성 중 전체 `./gradlew test`(4 fork 병렬)에서 `RoomReservationIntegrationTests.simultaneousBookingsThatOverlapOnlyPartlyNeverDoubleBook`가 한 번 실패했다.
  - 이 테스트는 한 시간씩 겹치는 2시간 예약 8건을 동시에 보낸다.
  - 진 요청 중 하나가 409 `ROOM_TIME_CONFLICT` 대신 500 `INTERNAL_ERROR`를 받았다.
- 당시 로그가 덮어써져 예외 종류는 알 수 없었다.
- 운영에서도 동시 예약 때 "잠시 후 다시 시도" 오류가 날 수 있는지 확인이 필요했다.

## 2. 재현과 원인 확인

### 방법

- 실패한 요청의 예외 스택을 남기려고 `ApiExceptionHandler`와 `ApiRequestFilter`에 진단 로그를 임시로 넣었다.
  - 운영 로그는 예외 클래스 이름만 남기기 때문이다.
  - 진단 로그는 확인 후 되돌렸고 커밋하지 않았다.
- 실행마다 테스트 결과 XML을 별도 폴더에 복사해 덮어쓰지 않게 했다.

### 결과

| 시도 | 횟수 | 결과 |
|---|---|---|
| 대상 테스트만 반복 (`--tests ... --rerun`) | 20회 | 모두 통과, 500 없음 (1회 39~89초) |
| 전체 `./gradlew test` (4 fork) | 2회 | 대상 테스트는 2회 모두 통과 |

전체 실행 1회차(1276초)에서 다른 동시성 테스트 두 개가 같은 유형의 500으로 실패했다.

- `LockerAssignmentIntegrationTests.onlyOneOfManySimultaneousOverlappingAssignmentsWins`: 409 대신 500
- `RoomCancelUsageIntegrationTests.simultaneousRetriesWithOneKeyRefundOnce`: 8건 중 3건이 500 (`[200, 500, 500, 200, 200, 200, 200, 500]`)

두 실패의 예외 스택(요약):

```
org.springframework.transaction.CannotCreateTransactionException: Could not open JPA EntityManager for transaction
Caused by: org.hibernate.exception.JDBCConnectionException: Unable to acquire JDBC Connection
Caused by: java.sql.SQLTransientConnectionException: HikariPool-8 - Connection is not available,
           request timed out after 1001ms (total=4, active=4, idle=0, waiting=3)
```

- 같은 실행에서 `ApiContractHttpTests.safeErrorsDoNotLeakExceptionTextAndLogsLinkRequestWithoutQueryOrBody`도 두 회 모두 실패했다.
  - 임시 진단 로그가 예외 문구를 찍었기 때문이고, 이번 원인과는 관계없다.
- 교착(1213), 잠금 대기 시간 초과(1205), 409로 바뀌지 않은 유일 키 위반은 한 건도 관찰되지 않았다.

### 원인

- 예약 생성(`RoomReservationService.create`)은 `AuthorizedCommands.inBranches` 안에서 실행된다.
  - 트랜잭션을 연 다음 가드 행, 직원, 지점을 `FOR UPDATE`로 잠그므로 모든 명령이 하나씩 차례로 실행된다.
  - 그래서 슬롯 유일 키 경합으로 이어질 여지는 거의 없다.
- 테스트 프로필의 Hikari 설정은 풀 4개, `connection-timeout: 1000`이다.
  - 동시 요청 8건 중 4건은 커넥션을 쥔 채 가드 잠금 차례를 기다린다.
  - 나머지는 커넥션을 1초만 기다린다.
  - 4 fork 부하로 앞 처리가 느려지면 이 대기가 1초를 넘고, 트랜잭션을 시작하지 못해 500이 된다.
- 트랜잭션이 시작되기 전에 실패하므로 아무것도 쓰지 않는다. 이중 예약이나 결제만 남는 부분 성공은 생기지 않는다.
- 운영 `application.yaml`에는 풀 설정이 없어 Hikari 기본값(풀 10개, 대기 30초)을 쓴다. 같은 500이 나려면 30초 넘게 기다려야 하므로, 운영 영향은 낮다고 판단했다.
- 대상 테스트 자체의 실패는 재현하지 못했다.
  - 원인은 같은 실행에서 같은 조건(동시 8요청, 가드 직렬화)을 가진 두 테스트의 스택으로 추정했다.

## 3. 검토한 선택지

1. **테스트 프로필의 `connection-timeout`을 30초(Hikari 기본값)로 늘린다.**
   - 메인 코드와 계약이 바뀌지 않는다.
   - 같은 원인의 다른 동시성 테스트 실패도 함께 사라진다.
   - 단점: 테스트에서 커넥션 부족이 드러나는 데 시간이 더 걸린다.
2. **테스트 풀 크기를 늘린다.**
   - fork마다 MySQL 연결이 늘어나고, 풀이 바닥나는 상황 자체는 남는다.
3. **커넥션 획득 실패를 503 등 별도 응답으로 매핑한다.**
   - 계약이 바뀌고, 그래도 409는 되지 않는다.

## 4. 결정 및 이유

- 1번을 선택했다(사용자 결정).
- 원인이 애플리케이션 동작이 아니라 테스트 환경의 짧은 커넥션 대기였기 때문이다.
- 1초 값은 2026-10-19 테스트 초기 구성 커밋(`681a8da`)에서 들어왔고, 따로 남은 근거는 없었다.
  - 컨테이너가 없을 때 바로 실패하게 하는 동작은 `initialization-fail-timeout: 1`과 존재하지 않는 기본 URL이 담당한다. 이번 변경은 이 동작에 영향을 주지 않는다.

## 5. 구현 내용

- `src/test/resources/application-test.yaml`: `spring.datasource.hikari.connection-timeout`을 1000에서 30000으로 바꾸고 이유를 주석으로 남겼다.
- 메인 코드는 바꾸지 않았다.

## 6. 검증 결과

- 실행함
  - 원인 확인용 반복 실행: 대상 테스트 20회와 전체 테스트 2회(변경 전 코드 기준, 2절).
- 실행하지 않음
  - 변경 후 대상 테스트 반복 실행과 전체 `./gradlew test`.
  - 사용자 지시(2026-11-10)에 따라 통합·전체 테스트는 기능마다 돌리지 않고 마일스톤 기능 테스트 때 돌린다.
  - 이번 변경은 테스트 설정 한 줄이라 대응하는 단위 테스트가 없다.

## 7. 남은 작업 / 보류 사항

- 마일스톤 검증 때 대상 테스트 반복 실행과 전체 병렬 실행에서 안정적으로 통과하는지 확인한다(#201 인수 조건 3).
- 운영 풀 크기와 대기 시간은 이번 범위가 아니다. 동시 요청이 가드에서 줄을 서는 구조는 그대로이므로, 트래픽이 커지면 운영 풀 설정을 따로 검토해야 한다.
