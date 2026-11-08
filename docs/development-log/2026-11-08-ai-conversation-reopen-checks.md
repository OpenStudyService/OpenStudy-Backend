# 2026-11-08 AI 대화 인수 조건 마무리 (#168)

## 1. 배경 / 문제

- AI 대화 저장·목록·다시 열기·삭제는 PR #172로 구현됐다. 결과 미저장(#173/#176)과 실행 본인 확인(#178)도 병합됐다.
- #168을 닫기 전에 인수 조건을 main 기준으로 하나씩 대조했다. 다음이 비어 있었다.
  - 다시 열 때 모델을 호출하지 않는다는 검증이 없다. 이슈는 "고정 생성기 호출 횟수"로 확인하라고 했다.
  - 변경 의도 질문이 실행되지 않는다는 검증이 로컬 전용 AI 테스트(`@EnabledIf localAiTestsEnabled`)에만 있어, 일반 `./gradlew test`에서는 건너뛴다.
  - 의미 카탈로그가 바뀌어 저장된 분석을 더 제공하지 않는 경우를 확인한 테스트가 없다.
  - 목록이 마지막 질문 시각 순인지, 제목 변경이 순서를 바꾸지 않는지 확인한 테스트가 없다.
- 카탈로그 변경 경우를 테스트로 재현하자 **다시 열기 전체가 500**으로 실패했다.
  - 분석 실행(`OpenStudyAnalyses`, 읽기 전용 트랜잭션)이 알 수 없는 분석 ID에 `EngineException`을 던진다. 이때 트랜잭션이 롤백 전용으로 표시된다.
  - 바깥에서 예외를 잡아도 커밋 시점에 `UnexpectedRollbackException`이 나서 다른 질문까지 보이지 않는다.
  - 실행 상세(`GET /api/executions/{id}`)·업무 기록·CSV 내보내기도 같은 경로를 쓴다.

## 2. 검토한 선택지

- **카탈로그에서 빠진 분석 처리**
  - 실행 후 `EngineException`을 잡아 사유로 바꾸기: 처음 시도했다. 위의 롤백 전용 표시 때문에 500이 그대로 났다.
  - 실행 전에 카탈로그가 그 분석을 제공하는지 확인하기: 실패가 트랜잭션 안으로 들어가지 않는다.
- **모델 미호출 확인 방법**
  - 모델 게이트웨이(`ModelGateway`)를 직접 세기: 모델 호출은 `OpenStudyPlaybackGateway` 내부에서만 일어나 바깥에서 바꿔 끼우기 어렵다.
  - `OpenStudyPlaybackGateway.planQuestion`을 세기: 질문 처리가 모델로 들어가는 유일한 입구다. Spy로 감싸 다시 열기에서는 0회, 질문하기에서는 1회임을 대조한다.

## 3. 결정 및 이유

- 실행 전 확인으로 정했다. `OpenStudyAnalyses.offers(analysisId)`가 현재 제공하는 분석인지 알려준다.
  - 다시 열기: 해당 질문만 `REFUSED`, 사유 코드 `ANALYSIS_UNAVAILABLE`(httpStatus 422)로 표시한다. 나머지 질문은 그대로 다시 실행된다.
  - 실행 상세·업무 기록·CSV: 422 `ANALYSIS_UNAVAILABLE`로 응답한다(업무 기록은 기존대로 `snapshotUnavailableReason`에 안내 문구가 들어간다).
  - 대체 분석을 찾으려고 모델을 다시 부르지 않는다(M10-D12).
- 다시 열기의 실행 불가 사유는 이제 세 가지 코드로 구분된다.
  - 권한·지점 범위: 권한 판정의 오류 코드(예: 매출 권한 없음)
  - 카탈로그 변경: `ANALYSIS_UNAVAILABLE`
  - 그 밖의 실행 실패: `REOPEN_NOT_EXECUTABLE`
- AI API의 `openapi.json`·`nl-analysis-api.md` 계약은 #92 범위(SSE·실패 유형·QuerySpec 스키마 포함)라 이번 작업에 넣지 않았다. 사유 코드는 #92 문서에 옮겨야 한다.

## 4. 구현 내용

- `OpenStudyAnalyses.offers`: 현재 제공하는 분석 ID 10개(POC-001~010)인지 확인한다.
- `AiWorkspaceService`
  - 다시 열기(`rerun`): 권한 확인 다음, 실행 전에 `offers`를 확인한다.
  - 저장 실행 재실행(`rerunSaved`): 같은 확인 후 422 `ANALYSIS_UNAVAILABLE`.
- `AiConversationHistoryIntegrationTests`에 4개 테스트를 더했다.
  - `reopeningNeverCallsTheModelWhileAskingDoes`: 다시 열기 2회 동안 `planQuestion` 0회, 이어서 질문 1번에 1회
  - `aSavedAnalysisTheCatalogNoLongerOffersShowsAReasonCodeInsteadOfFailing`: 빠진 분석은 사유 코드, 같은 대화의 다른 질문은 정상 실행, 실행 상세는 422
  - `aQuestionThatAsksForAChangeIsNotRunAndEndsAsUnsupported`: "예약 전부 취소해줘"는 400 `UNSUPPORTED_MODIFICATION_INTENT`, 결과 없음, 실행된(`READY`) 기록 없음
  - `theListFollowsTheLastQuestionAndARenameDoesNotMoveIt`: 마지막 질문 시각 순, 제목 변경은 순서를 바꾸지 않고, 새 질문은 맨 앞으로 올린다

## 5. 인수 조건 대조

| 인수 조건 | 확인 테스트 |
| --- | --- |
| 본인만 조회·수정·삭제, 남의 대화 ID는 404 | `renameAndDeleteAreTheOwnersAndAnotherEmployeeGetsA404`(기존) |
| 목록이 마지막 질문 시각 순 | `theListFollowsTheLastQuestionAndARenameDoesNotMoveIt`(신규) |
| 결과 행·합계·건수 미저장 | `noResultIsStoredAndEveryResultIsRunAgainUnderTheCurrentPermissions`(기존) |
| 다시 열면 현재 권한으로 재검증·재실행, 새 기준 시각, 모델 미호출 | `theListIsTheEmployeesOwnAndReopeningRerunsSavedAnalysesWithoutData`(기존) + `reopeningNeverCallsTheModelWhileAskingDoes`(신규) |
| 권한 회수·카탈로그 변경 시 사유 코드 | `aSavedQuestionTheEmployeeMayNoLongerRunShowsTheReasonInsteadOfData`(기존) + `aSavedAnalysisTheCatalogNoLongerOffers...`(신규) |
| 삭제한 대화는 목록·다시 열기에서 빠짐 | `renameAndDeleteAreTheOwnersAndAnotherEmployeeGetsA404`(기존) |
| 변경 의도 질문은 실행하지 않고 지원 불가 | `aQuestionThatAsksForAChangeIsNotRunAndEndsAsUnsupported`(신규) |

## 6. 검증 결과

- `AiConversationHistoryIntegrationTests` 9개, 모듈 경계·영속성 접근 테스트 통과
- 전체 `./gradlew test`: 87개 클래스 998개 중 실패 0, 건너뜀 25(로컬 전용 AI 테스트)
- **미검증**
  - 실제 모델(live 모드)에서의 다시 열기. 테스트는 playback 모드의 Spy로 입구 호출 횟수만 확인했다.
  - "지점 소속 해제"만으로 실행할 수 없게 된 경우는 별도 테스트가 없다. 권한 회수와 같은 판정(`authorizeQuestion`)을 지난다.

## 7. 남은 작업 / 보류 사항

- #92: AI 대화 operation과 다시 열기 사유 코드(`ANALYSIS_UNAVAILABLE`, `REOPEN_NOT_EXECUTABLE`)를 `openapi.json`·`nl-analysis-api.md`에 정의한다.
- 다시 열 때 원래 질문의 부가 조건 보존은 #172 이후 반영됐다(`reopeningKeepsTheConditionsTheQuestionAskedFor`).
- 업무 기록의 `snapshotUnavailableReason`은 코드 없이 안내 문구만 준다. 업무 기록은 #168 범위가 아니어서 그대로 뒀다.
