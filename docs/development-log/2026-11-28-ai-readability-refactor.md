# 2026-11-28 ai 모듈 가독성 정리

## 1. 배경 / 문제

#250의 마지막 모듈이다. ai 모듈(33개 파일, 약 8,300줄)은 다음을 담당한다.

- AI 질문 접수·계획·실행과 대화·실행 기록(`AiWorkspaceService`, `OpenStudyPlaybackGateway`)
- 등록 분석 10종의 집계와 결과 카드(`OpenStudyAnalyses`, `ResultCards`)
- 생성 경로, 계획 캐시, 질문 정규화·의도 분석, 사용자 문맥

세 파일(`OpenStudyAnalyses` 2,117줄, `AiWorkspaceService` 1,741줄, `OpenStudyPlaybackGateway` 1,200줄)이 모듈의 대부분이고, 다음 문제가 많았다.

- `var` 345곳, 120자를 넘는 줄 약 200곳
- 한 줄 `if`·`for`·메서드 본문, 한 줄 enum·레코드, 필드 어노테이션 여러 개를 한 줄에 쓴 엔티티
- 코드 중간의 완전한 이름(`java.util.Optional`, `com.openstudy.common.export.CsvFile` 등 66곳)
- 주입 필드 이름이 타입과 다름(`registry`, `analyses`, `intents`, `repository`, `json`, `audit` 등 53개)
- 이름이 동사로 시작하지 않는 private 메서드(`ownExecution`, `savedView`, `conditionsOf`, `krw`, `seatCounts` 등)
- 문자열 연결로 쓴 JPQL

## 2. 검토한 선택지

- 앞선 모듈처럼 파일을 손으로 다시 쓰는 방법은 이 크기(약 8,300줄)에서는 실수 위험이 커서, 이름과 타입을 컴파일러가 확인하는 도구로 바꾸는 방법을 택했다.
  - `var`: javac Tree API로 각 `var`의 추론 타입을 구해 단순 이름으로 바꾸고, 필요한 import를 더했다. 이름이 겹치는 import가 생기는 경우는 없었다.
  - 필드·private 메서드 이름: javac로 심볼을 해석해, 같은 이름의 지역 변수·다른 객체의 메서드는 건드리지 않고 해당 심볼의 선언과 참조만 바꿨다.
- 한 줄 제어문, 한 줄 메서드, enum·레코드, 긴 줄은 앞선 모듈에서 쓴 형식 스크립트(문장 단위 줄바꿈 포함)로 풀고, 결과를 컴파일과 눈으로 확인해 어색한 곳(조건식이 `if (`에서 끊긴 곳, 연결된 메서드 호출 앞의 빈칸 등)을 손으로 고쳤다.
  - 형식 스크립트가 줄 안 주석이 있는 레코드 하나(`PlanDecision`)를 잘못 나눈 것을 컴파일 오류로 발견해 원래대로 되돌렸다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - `AiWorkspaceController`의 메서드 이름: 테스트가 `controller.executeQuery(...)` 등으로 직접 부른다. 테스트가 부르지 않는 메서드만 operationId로 바꾸면 한 컨트롤러 안에서 이름 규칙이 갈리므로 모두 그대로 두었다. 클래스 매핑도 추가하지 않았다(`AiContractTests`가 메서드 매핑 값이 `/api/`로 시작하는지 읽는다).
  - `OpenStudyAnalyses`의 공개 상수·레코드·메서드, `AiWorkspaceService.AiQueryRequest` 등 테스트가 쓰는 공개 API
  - 파생 쿼리 이름(`findTop20ByEmployeeIdAndOrigin...`): 이름 자체가 쿼리라 바꿀 수 없어 이 줄만 120자를 넘는다.
- 주입 필드는 타입 이름으로 바꿨다(예: `registry` → `semanticRegistry`, `analyses` → `openStudyAnalyses`, `json` → `objectMapper`, `audit` → `auditTrail`). 생성자로 주입되는 순서는 바뀌지 않는다.
- private 메서드 36개를 동사로 시작하게 바꿨다.
  - `AiWorkspaceService`: `ownExecution` → `findOwnExecution`, `savedView` → `readSavedView`, `conditionsOf` → `readConditions`, `replayed` → `toReplayedResponse`, `withRerunResult` → `appendRerunResult` 등
  - `OpenStudyAnalyses`: 열 생성 `integer`·`krw`·`decimal`·`string`·`timestamp` → `toIntegerColumn` 등, `seatCounts` → `countSeats`, `branchName` → `findBranchName`
  - 그 밖에 `collectConjunction`, `calculateElapsed`, `toQuestionKey`, `toHit`, `parseIds`, `findPeriods`, `toDays`, `findDomainForAnalysisId` 등
- JPQL 15개는 text block으로 옮겼다. 원래 연결 지점에서 `\` 줄 이음을 써서 쿼리 문자열이 그대로다.
- LLM 시스템 프롬프트 text block은 내용이 곧 모델 입력이라 줄 길이를 맞추려고 바꾸지 않았다.
- 매직 값 상수화는 이번 범위에서 크게 하지 않았다. 등록 분석의 문자열 대부분이 화면 제목·열 이름·질문 문구로 한 번씩만 쓰여, 상수로 빼면 오히려 읽는 위치가 멀어진다.
- DomainLens 어노테이션과 domainlens 패키지는 건드리지 않았다.

## 4. 구현 내용

- `var` 345곳을 명시 타입으로, 완전한 이름 66곳을 import로 바꿨다.
- 주입 필드 53개(선언·참조 377곳), private 메서드 36개(선언·참조 257곳)의 이름을 바꿨다.
- 한 줄 `if`·`for`·`else`와 한 줄 메서드·익명 클래스 메서드를 블록으로 풀었다.
- 120자를 넘는 줄을 인자 한 줄에 하나, 메서드 연결 한 줄에 하나, 삼항·논리 연산자 앞 줄바꿈으로 나눴다. 읽기 어려운 조건 두 곳은 이름 있는 boolean(`followUpAsked`, `generationAbstained`)으로 뺐다(단락 평가 순서는 그대로).
- #250 인수 조건에 맞춰 중첩·연쇄 삼항 연산자 12곳을 `if`/`else if`로 바꿨다(`AiWorkspaceService`의 초기화 사유·단계 상태·생성 라벨·기간 문구·환불 기준·기간, `AiReuse`, `OpenStudyIntentAnalyzer`, `OpenStudyPlaybackGateway`). 각 분기의 호출(`isPermitted`, `defaultPeriod`, `findPreviousConditions` 등)은 원래처럼 해당 분기에서만 실행된다. `OpenStudyPlaybackGateway`의 프롬프트 문자열 연결은 서로 독립된 괄호 삼항이라 그대로 두었다.
- 엔티티 7개: 필드 어노테이션을 한 줄에 하나씩 썼다.
- enum·레코드: 상수·구성 요소를 한 줄에 하나씩, 빈 본문은 두 줄로 썼다.
- import 그룹 사이에 빈 줄을 넣었다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- ai `src/main`에 `var`, 중괄호 없는 `if`·`for`·`else`, 중첩 삼항 연산자가 남아 있지 않은 것을 확인했다. 120자를 넘는 코드 줄은 파생 쿼리 이름 두 줄과 LLM 프롬프트 text block뿐이다.
- JPQL·SQL 리터럴 토큰 비교(`origin/main` 대비): `OpenStudyAnalyses`, `OpenStudyUserContextResolver`, `AiPlanCacheRepository` 모두 차이 없음.
- ai·domainlens·architecture 테스트 전체와 AI 매출 동등성(`AiSalesEquivalenceIntegrationTests`), 근태 정정 통합 테스트: 45개 클래스, 259개 테스트, 실패 0, 건너뜀 27. 삼항 연산자 정리 뒤 같은 범위를 다시 실행해 같은 결과를 확인했다.
  - 건너뜀 27은 모두 로컬 AI 환경 변수(`OPENSTUDY_AI_LOCAL_TESTS`)나 실제 모델 연결로 켜는 테스트다.
- `OPENSTUDY_AI_LOCAL_TESTS=true`로 켜서 다시 실행한 결과
  - `DomainLensPipelineIntegrationTests`(11개, 건너뜀 1)·`AiGeneratedCongestionIntegrationTests`(6개, 건너뜀 1): 통과.
  - 검증하지 못한 항목: `OpenStudyAiLifecycleAndNegativeCasesTests`·`OpenStudyNegativeCasesIntegrationTests`·`OpenStudyGoldenAggregationsIntegrationTests`는 저장소에 없는 기대값 파일(`docs/05_TEST_CASES/…`, `docs/04_TEST_DATA/…`, 다른 PC 경로)을 읽다가 초기화에서 실패했고, `OpenStudyRetrievalEvaluationTests`는 Testcontainers가 아닌 로컬 MySQL 접속을 요구해 컨텍스트를 띄우지 못했다. 모두 이번 변경과 무관한 환경 문제다.
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- #250의 모든 모듈 정리가 끝났다. 남은 PR 병합 뒤 #250을 닫을 수 있다.
- `AiWorkspaceController` 메서드 이름을 operationId로 맞추려면 테스트 호출도 함께 바꿔야 한다. 필요하면 별도 작업으로 다룬다.
- 환경 변수로 켜는 AI 평가 테스트가 저장소 밖 파일·로컬 DB에 의존하는 문제는 이번 범위 밖이다.
