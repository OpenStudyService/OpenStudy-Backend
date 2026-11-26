# 2026-11-26 audit 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음 중 네 번째 모듈이다. audit 모듈(6개 파일, 약 410줄)은 OWNER 전용 감사 기록 검색·상세 API를 담당한다. 구성은 다음과 같다.

- 검색 조건 검증(`AuditSearchCriteria`)
- 네이티브 검색(`AuditEventSearchRepository`)
- 응답 조립(`AuditEventSearchService`)
- 읽을 때 다시 하는 민감 값 마스킹(`AuditSnapshotMasking`)

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`, `var`
- 한 줄에 몰아 쓴 11~15개 인자 생성
- 이름이 동사로 시작하지 않는 헬퍼(`value`, `date`, `row`, `truthy`, `walk` 등)
- 검색 조건 검증의 매직 넘버(80, 7, 128, 20, 100, 10)

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

- 감사 기록 생성(`AuditEvent.change` 등)의 빌더 전환은 #265에서 별도로 다루므로 이 모듈에서 손대지 않았다.

## 3. 결정 및 이유

- 테스트(`AuditSearchUnitTests`)가 직접 쓰는 이름과 형태는 그대로 둔다.
  - 서비스 `search`·`get`, 저장소 `search`·`find`
  - `Row`·`Page` 레코드
  - `AuditSearchCriteria.parse`와 공개 상수
  - 서비스 생성자 인자 순서
- 모듈 안에서만 쓰는 헬퍼는 동사로 시작하게 바꿨다.
  - 검색 조건: `value`·`date`·`text`·`id`·`number` → `readValue`·`parseDate`·`readText`·`parseId`·`parseNumber`
  - 저장소: `equal`·`bind`·`row`·`nullable`·`truthy`·`text` → `appendEqual`·`bindAll`·`toRow`·`toLong`·`toBoolean`·`toText`
  - 서비스: `item`·`ref`·`id`·`snapshot` → `toItem`·`toRef`·`toId`·`toMaskedSnapshot`
  - 마스킹: `walk` → `maskInPlace`, `lastFourDigits` → `maskAllButLastFourDigits`
- 컨트롤러
  - 메서드 이름은 openapi operationId인 `searchAuditEvents`·`getAuditEvent`로 바꿨다.
  - 두 메서드가 모두 `/api/audit-events` 아래에 있어 이를 클래스 매핑에 두었다. 최종 경로는 같다.
- 검색 조건의 검증 순서는 그대로 둔다. 어떤 오류가 먼저 나는지 바뀌지 않는다.
  - 순서: 허용 파라미터 → 기간 → 대상 유형·ID → 결과 → page·pageSize → action·행위자·지점·요청 ID
- 검색 SQL의 열 목록(`COLUMNS`)은 120자 제한 때문에 줄을 나눴다. 이어 붙인 문자열이 원래와 같은 것을 비교해 확인했다.

## 4. 구현 내용

- `AuditSearchCriteria`
  - 허용 파라미터 검사를 `requireKnownSingleParameters`로 뺐다.
  - 길이·페이지 제한을 상수로 바꿨다.
  - 레코드 구성 요소와 생성 인자를 줄바꿈했다.
- `AuditEventSearchRepository`
  - `var`를 실제 타입으로 바꾸고, 개수 조회와 목록 조회를 이름 있는 변수로 나눴다.
  - 스냅숏 열 번호(13·14)를 상수로 바꿨다.
  - `toRow` 인자를 한 줄에 하나씩 썼다.
- `AuditEventSearchService`: 주입 필드 이름을 타입에 맞추고(`repository`·`policy`·`json` → 타입 이름), 상세·목록 항목 생성을 줄바꿈했다.
- `AuditSnapshotMasking`
  - 객체 처리를 `maskObject`로 나눴다.
  - 비밀 키·연락처 키 판단을 이름 있는 boolean으로 바꿨다. 연락처 키가 문자열이 아니면 안쪽을 계속 살피는 동작은 같다.
- `AuditEventViews`: 레코드 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- audit `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- 감사 API를 다루는 테스트 20개 모두 통과.
  - `AuditSearchUnitTests` 11개
  - `AccountSecurityIntegrationTests` 4개
  - `AccountContractTests` 5개
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- #250의 같은 묶음에서 남은 모듈: retention, home, workspace, notice, inquiry
