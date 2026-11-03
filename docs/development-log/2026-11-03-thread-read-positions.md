# 2026-11-03 본문·스레드별 독립 읽음 계약·저장·API

관련: Issue [#155](https://github.com/OpenStudyService/OpenStudy-Backend/issues/155), [Frontend #27](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/27), [계약 문서](../design/foundation/collaboration-api.md), [ADR-M7-001](../db/ADR-M7-001.md) M7-D08·D09, 이전 기록 [2026-11-02 채널·메시지 API](2026-11-02-channel-message-api.md)

## 1. 배경 / 문제

#76의 읽음 위치는 채널·직원당 하나였다. 본문 목록 하단을 읽으면 그보다 앞선 답글까지 읽음이 되어, Frontend #27에서 사용자가 고른 "본문과 스레드를 따로 읽는다"는 정책과 맞지 않았다. Frontend는 2026-11-02 정책 유지를 다시 선택했고 live 빌드의 읽음 요청과 미확인 배지를 보류한 채 이 작업을 기다렸다.

구현 전에 정해야 했던 정책 두 가지를 2026-11-03 사용자가 확정했다.

- **과거 답글(M7-D08)**: 전환 시점의 채널 읽음 위치 이하의 답글은 스레드에서도 읽음으로 둔다. 배포 직후 미확인 수가 급증하면 안 된다. 위치가 없던 직원은 지금처럼 전부 미확인이다.
- **재참여(M7-D09)**: 참여 종료 후 다시 참여하면 그 시점의 채널 최신 메시지까지 본문·모든 스레드를 읽음으로 옮긴다. 이전 위치를 유지하던 #76 동작을 바꾼다. 이전 메시지를 다시 볼 수 있는 동작은 유지하고, 메시지 없는 채널의 위치는 그대로이며, 신규 참여자는 전부 미확인이다.

## 2. 검토한 선택지

**재참여 시 본문 위치**

- 최신 원문: 본문 위치가 항상 원문이라 깔끔하다. 최신 원문 뒤의 답글을 다루려면 기준 위치만 다른 값을 가져야 한다.
- 채널 최신 메시지(원문·답글 전체): 두 위치가 같은 값이고 사용자 정책의 "최신 메시지까지"와 그대로 맞는다. 위치가 답글 ID일 수 있으나 전환 전 행도 이미 그렇다.

**스레드 위치 응답**

- 저장된 행의 값: 저장소를 그대로 보여 주지만 기준 위치로 읽음인 스레드가 null로 보여, 클라이언트가 기준 위치까지 알아야 한다.
- 비교에 실제로 쓰이는 값 `max(기준 위치, 스레드 위치)`: 클라이언트가 답글 ID와 비교만 하면 된다. 그 스레드의 답글이 아닐 수 있다.

**첫 스레드 행의 동시 생성**

- `INSERT ... ON DUPLICATE KEY UPDATE`: 잠금 없이도 안전하지만 네이티브 SQL이 늘고 위치 비교가 두 곳으로 갈라진다.
- 기존처럼 가드·`channels.lock` 안에서 JPA로 읽고 쓰기: 전송·본문 읽음과 같은 직렬화 구조이고 코드가 단순하다.

**미확인 집계 인덱스**

- 일회용 MySQL 8.4(`mysql:8.4`, 메시지 40만 건, 답글 2천 건, 20개 채널)에서 전체 스레드 미확인 쿼리를 재봤다. `messages(conversation_id, parent_message_id, id)` 없이 114ms, 추가 후 72ms였고 두 경우 모두 옵티마이저는 새 인덱스가 아니라 기존 `ix_messages_2`의 범위 스캔을 골랐다. 답글 비율이 0.5%인 합성 데이터의 1회 측정이고 답글이 많은 분포는 재지 않았다.

**구분 값 이름**

- 채널의 스레드 합계를 `threadUnreadCount`로 두면 메시지의 같은 이름(그 스레드만)과 뜻이 갈린다. 스레드 갱신 응답에는 둘이 함께 나온다. 채널 값을 `replyUnreadCount`로 나눴다.

## 3. 결정 및 이유

제안 설계를 따르되 아래를 정했다.

- 저장은 `conversation_members.thread_read_baseline_message_id`와 `conversation_thread_reads`다. 기준 위치는 모든 스레드에 함께 적용되는 하한이고 V6와 재참여만 정한다. 스레드 행은 기준을 넘어 읽을 때만 생긴다.
- 재참여는 채널 최신 메시지 하나로 본문 위치와 기준 위치를 함께 옮긴다. 기존 스레드 행은 지우지 않고 기준 위치가 앞서면 무시된다.
- 응답의 스레드 위치는 실제 비교 값을 준다. 두 위치 값은 ID 비교 전용이라고 계약에 적었다.
- 답글의 스레드 필드는 생략하지 않고 null이다. `Message`는 모든 필드가 required이고 `task`도 같은 방식이다.
- `INVALID_READ_TARGET` 하나로 "같은 채널의 메시지지만 그 위치가 될 수 없음"을 나타낸다. 다른 채널·없는 ID는 답글 대상(`parentId`)과 같은 404다.
- 동시성은 기존 가드·채널 잠금에 맡긴다. 같은 채널의 읽기·쓰기가 줄을 서므로 행 충돌이 생길 수 없고 동시 시험으로 확인했다.
- 집계 인덱스는 추가하지 않는다. 위 측정에서 쓰이지 않았고 현재 규모(지점 내부 채널)에서 쓰기 비용만 늘린다.
- 읽음은 감사·workspace 이벤트를 남기지 않는다. #76의 본문 읽음이 그렇고 요청자만의 상태이기 때문이다. 시험으로 고정했다.
- 호환 분기는 두지 않았다. Frontend `src/features/chat/api.ts`의 live `read`는 요청을 보내지 않고 거절하며, `Chat.tsx`의 채널 미확인 배지는 `MOCK` 조건 안에만 있다(origin/main `9883200` 기준으로 확인). `canonical.ts`의 `z.object`는 strict가 아니어서 응답에 필드가 늘어도 파싱이 깨지지 않는다.

## 4. 구현 내용

- `V6__thread_read_positions.sql`: 열 추가, 기존 위치 복사, 표 추가. 스레드 행은 참여자(`conversation_id`, `employee_id`), 원문, 위치 메시지를 복합 FK로 참조해 다른 채널의 값이 저장되지 않는다.
- `ReadPositions`: "무엇이 읽음인가"를 한곳에 모았다. 채널별 원문·답글 미확인, 원문별 스레드 상태, 비교 위치 계산. `ChannelService`와 `MessageService`가 같은 규칙을 쓴다.
- `MessageRepository`: `unreadCounts`를 `unreadOriginals`, `unreadReplies`, `unreadByThread`로 나누고 `latestId`를 추가했다.
- `MessageService.markRead`는 원문만 받고, `markThreadRead`가 새 `POST /channels/{id}/messages/{messageId}/read`다. 메시지 응답의 원문에 스레드 미확인 수와 위치를 담는다.
- `ChannelService.replaceMembers`: 참여 종료된 행만 `rejoin`한다. 전에는 목록에 남은 직원에게도 `rejoin()`을 불렀다. 그 메서드가 위치를 옮기게 되면서 목록을 바꿀 때마다 남은 전원이 읽음이 될 수 있어 조건을 넣고 시험을 추가했다.
- 채널 생성 멱등 재요청: 저장된 응답을 `ChannelView`로 읽는데 새 primitive 필드가 없으면 500이었다. 배포 전 24시간 안의 기록을 재요청하는 경우라 시험으로 재현한 뒤, 이 읽기만 없는 필드를 0으로 받도록 고쳤다. 새 채널이므로 0이 맞다.
- `collaboration-api.md`, `openapi.json`(`0.1.9-m7-thread-read`, operation 1개, schema 2개), ADR-M7-001 M7-D08·D09, `docs/db/README.md`, `docs/db/erd.mmd`(직접 갱신), `schema-full.sql`(`-PfoundationExportDir`로 V6 기준 재추출, 표·열·Flyway 이력 외에는 추출 시각 차이).

### 읽는 경로 확인

| 경로 | 새 의미에서의 동작 |
| --- | --- |
| `GET /channels`, `/channels/{id}`, `/channels/manageable`, 생성·수정 응답(`ChannelService.views`) | 합계와 구분 값. 참여하지 않은 관리자는 0, null |
| 채널 생성 멱등 재요청 | 분리 전 저장 응답도 응답. 위 수정 |
| `POST /channels/{id}/read`(`ChannelReadResult`) | 본문 위치 전용 |
| 메시지 목록·단건·답글·전송·재전송·업무 카드 응답(`MessageService.views`) | 원문에만 스레드 값 |
| 참여 자동 종료(`ChannelParticipationEnding`, 소속 회수·계정 상태) | `left_at`만 기록하고 위치는 건드리지 않는다. 변경 없음 |
| 재참여(`replaceMembers`) | 유일한 재참여 경로. M7-D09 적용. 자격 복구만으로는 재참여하지 않는다 |
| 업무 카드 | 카드도 메시지다. 원문·답글 위치에 따라 같은 규칙으로 센다 |
| DM | `ChannelAccess`가 404. 집계는 참여 채널 ID로만 호출되고 그 목록에 DM이 없다. 새 API도 404 |
| 그 외 | `last_read_message_id`·`unreadCount`를 읽는 코드는 `modules/chat` 밖에 없다 |

## 5. 검증 결과

**HTTP·실제 DB 통합 테스트(Testcontainers MySQL)**: `ThreadReadIntegrationTests` 21개를 추가하고 기존 채널·메시지·마이그레이션 시험을 새 정책에 맞게 고쳤다. 정책이 바뀐 시험은 `joiningAgainClearsLeftAtAndRestoresEarlierMessagesAndMovesTheReadPosition`과 `unreadCountsOriginalsAfterThePositionAndEveryUnopenedReplyFromOthers`로 이름을 바꿨다.

| 인수 조건 | 확인 |
| --- | --- |
| 본문 C를 읽어도 A의 답글 B는 미확인 | `readingALaterOriginalLeavesTheRepliesOfAnEarlierOneUnread` |
| A 스레드를 읽어도 다른 스레드·본문 불변 | `readingAThreadMovesNeitherOtherThreadsNorTheMainFeed` |
| 요청자·채널·대화 범위에서 앞으로만 이동 | `aThreadPositionOnlyMovesForwardAndARepeatChangesNothing`, `aPositionIsTheRequestersOwn...`, 기존 `theReadPositionOnlyMovesForward...` |
| 합산·본인 제외 기준이 계약과 일치 | `theSumAndTheSplitLeaveOut...`, `onlyOriginalsCarryThreadFields...`, `aCardCountsLikeAnyMessage...`, `CollaborationContractTests` |
| 비참여자·참여 종료·다른 채널 거절 | `onlyAnOpenQualifiedParticipationReaches`, `anythingButAReplyOfThatOriginalIsRefused...`, `aDmIsNotAChannelHereEither`, 기존 `shutOut` 경로 확장 |
| 동시·재시도·과거 위치로 역행 없음 | `concurrentUpdatesOfOneThread...`(6개 동시, 첫 행 1개), `concurrentRepeatsOfTheFirstRequest...`(같은 요청 6개, 모두 200) |
| 전환·회수·재참여·호환 문서화 | 계약 읽음 절, `joiningAgainReadsEverythingSentSoFarAndKeepsItOpen`, `joiningAnEmptyChannelAgain...`, `onlyWhoComesBackMoves`, `endingAndRegainingAccess...` |
| OpenAPI와 구현 일치 | `theMainFeedAndEachThreadHaveTheirOwnReadPosition...`, `everyCollaborationOperation...`. 응답 필드 전부를 스키마와 대조하는 자동 비교는 없다 |

**V6 전환**: `v6MakesEachReadPositionTheBaselineOfTheThreadsAndAddsTheirTable`이 V5 DB에 실제 V6를 적용해 값 복사(답글 위치·null 포함)와 FK를 확인한다. API 수준의 `afterTheMigrationNothingThatWasReadTurnsUnread...`, `aFormerPositionOnAReply...`는 V6의 UPDATE와 같은 문장을 시험 데이터에 실행해 응답을 확인한다. 즉 실제 V6를 적용한 DB에 API를 붙인 시험은 아니다.

**전체 테스트**: `./gradlew cleanTest test`(Java 21, Testcontainers MySQL 8.4)

- 1차(`cdd5306`): 844개 중 1개 실패. `OwnerBootstrapTests`가 최신 Flyway 버전을 `"5"`로 고정하고 있었다. `"6"`으로 고쳐 `21beeeb`로 커밋했다.
- 2차(`21beeeb`): **66개 suite, 844개 모두 통과**, 실패·오류·건너뜀 0, BUILD SUCCESSFUL 34분 39초. `build/test-results/test/*.xml` 합계다. 이 기록 커밋은 문서만 바꾼다.

**미검증**

- 실제 서버를 띄운 수동 호출, 운영·개발 DB의 V6 적용.
- Frontend 두 브라우저 연동. Frontend #27의 남은 작업이고 이번 범위에서 수행하지 않았다.
- `scripts/validate_foundation_design.py`는 정책 Q8이 `superseded`라는 기존 내용에서 멈춘다. origin/main의 값과 같아 이번 변경과 무관하고, 이 스크립트의 나머지 검사는 돌지 않았다. OpenAPI `$ref`는 별도 스크립트로 전부 해석됨을 확인했다. swagger-cli 표준 검증은 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- Frontend: live 읽음 요청을 새 API로 연결하고 배지를 켠 뒤 두 브라우저로 검증한다(#27). 위치 값이 답글 ID일 수 있으니 조회 대상으로 쓰지 말고 비교만 한다.
- 인덱스는 답글이 많은 실제 분포에서 집계가 느려지면 다시 측정한다.
- 기준 위치가 null인 직원은 스레드를 열 때까지 과거 답글이 모두 미확인이다. 사용자 정책대로이며 "모두 읽음" API는 범위에 없다.
- `OpenStudy-Foundation-API.xlsx`는 이전 계약 변경들과 마찬가지로 다시 만들지 않았다.
