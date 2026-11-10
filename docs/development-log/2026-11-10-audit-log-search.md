# 2026-11-10 감사 로그 검색과 변경 전후 상세 (#122)

## 1. 배경 / 문제

- OWNER가 대상·행위·작업자·지점·결과·기간으로 감사 기록을 찾고, 주요 변경의 전후 값을 확인할 수 있어야 한다(FR-AUD-03·04).
- 계약은 #118(PR #208, `account-collab-api.md` "감사 로그 검색·상세")에서 확정됐다.
  - M14-D04: OWNER 전용
  - M14-D12: 기본 7일·최대 92일
- 감사 기록은 V1 `audit_events`에 이미 쌓이고 있다. 새 테이블은 필요하지 않다.

## 2. 검토한 선택지

- **조회 방식**
  - JPA 엔티티 `AuditEvent`로 읽고 작업자·지점 이름을 따로 조회하는 방법이 있었다.
  - 결제 검색(`PaymentSearchRepository`)처럼 저장소 클래스에서 SQL 한 번에 이름까지 조인하는 방법도 있었다.
  - 조건이 모두 선택 사항이고 페이지가 필요해 후자를 택했다. 고정 조각만 이어 붙이고 값은 모두 바인딩한다.
- **query 검증**
  - Spring `@RequestParam`으로 받으면 계약에 없는 query가 조용히 무시된다.
  - 계약이 "표에 없는 query는 422"이므로 전체 query map을 받아 직접 검증했다.

## 3. 결정 및 이유

- **OWNER 확인을 query 검증보다 먼저 한다.** 다른 직원이 422 응답으로 허용 조건이나 형식을 알아낼 수 없게 하기 위해서다.
- **기간 계산**
  - Asia/Seoul 날짜이고 양 끝을 포함한다.
  - "92일"은 포함 일수로 센다. `from`~`to`가 92일이면 허용하고, 93일이면 `PERIOD_TOO_LONG`이다.
- **같은 query를 두 번 보내면 422로 막는다.** 어느 값이 쓰였는지 모호해지기 때문이다.
- **시각 처리**: 다른 native 검색과 같이 `occurred_at`을 `DATE_FORMAT`으로 UTC 문자열로 읽는다. 경계값은 `Instant`로 바인딩한다.
- **지워진 작업자·지점**
  - FK가 `ON DELETE SET NULL`이라 ID가 비면 `actor`·`branch`는 null이다.
  - ID는 있는데 이름을 찾지 못하면 이름을 빈 문자열로 둔다.

## 4. 구현 내용

새 모듈 `modules/audit`를 만들었다.

- `dto/request/AuditSearchCriteria`: query 검증과 기간 계산
- `service/AuditSnapshotMasking`: 조회 때 키 이름 기준으로 가림
  - `password`·`token`·`secret`·`hash`·`csrf`·`session`·`credential`은 `***`
  - `phone`·`contact`가 들어간 키의 문자열은 끝 네 자리만 남긴다
  - 대소문자를 무시하고, 중첩 객체와 배열까지 적용한다
- `repository/AuditEventSearchRepository`: 검색(건수 + 최신순 페이지), 상세(전후 JSON 포함)
- `service/AuditEventSearchService`: OWNER 확인, 결과 변환, 상세의 전후 값 가림
- `controller/AuditEventController`: `GET /api/audit-events`, `GET /api/audit-events/{id}`. 상세는 query를 받지 않는다.

계약 갱신:

- OpenAPI의 두 operation을 `implemented`로 바꿨다.
- 계약 문서에 구현 사실을 기록했다.
- `AccountContractTests`가 이슈별 구현 상태를 검사하게 했다.

## 5. 검증 결과

- 실행함
  - `./gradlew test --tests "com.openstudy.modules.audit.*" --tests "com.openstudy.architecture.*"`: 통과
  - 감사 단위 테스트 11건(`AuditSearchUnitTests`, DB 없음)이 확인한 내용:
    - 기본 7일과 서울 날짜의 UTC 경계
    - 한쪽 날짜만 있을 때 채우기
    - 92일 허용과 93일 `PERIOD_TOO_LONG`
    - 날짜 형식 오류
    - 허용 조건 전체
    - 알 수 없는·중복·범위 밖 조건 거부, `entityType` 없는 `entityId` 거부
    - OWNER가 아니면 query와 상관없이 거부되고 저장소를 호출하지 않음
    - 없는 기록은 404
    - 중첩·배열·대소문자를 포함한 가림
    - 전후 값이 없는 상세
    - 검색 결과의 기간·페이지 메타
- 실행하지 않음
  - 실제 MySQL에서의 SQL 실행(조건별 검색, 정렬, 페이지, 이름 조인, `DATE_FORMAT` 결과)
  - HTTP 경로의 403·404·422 응답
  - 전체 `./gradlew test`
  - 사용자 지시(2026-11-10)에 따라 통합·전체 테스트는 마일스톤 검증 때 실행한다. #122 인수 조건의 "HTTP·DB 통합 테스트"도 그때 확인한다.
- `graphify update .`는 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- **마일스톤 검증 때 통합 테스트로 확인할 것**: 권한별 HTTP 응답, 조건별 검색 결과, 기간 경계(서울 0시), 페이지·정렬, 가림 결과
- **기간만으로 검색할 때의 인덱스**
  - `audit_events`에는 `occurred_at` 단독 인덱스가 없다. 조건 없이 기간만 주면 전체 스캔이 될 수 있다.
  - 기본 7일·최대 92일이라 당장은 괜찮다고 판단했다. 기록이 많아지면 인덱스 추가 마이그레이션을 검토한다.
- **가림 규칙이 넓다**
  - 계약의 키 이름 규칙상 `session`이 들어간 키(예: 좌석 이용 기록의 `seatSessionId`)도 `***`로 가려진다.
  - 비밀값이 아니어도 가려지는 쪽으로 안전하게 둔 것이다. 화면에서 불편하면 계약을 고쳐 규칙을 좁힌다.
