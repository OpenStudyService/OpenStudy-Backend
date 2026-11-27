# 2026-11-27 chat 모듈 가독성 정리

## 1. 배경 / 문제

#250의 다섯 번째 묶음 중 마지막 모듈이다. chat 모듈(30개 파일, 약 1,400줄)은 다음을 담당한다.

- 팀 채널과 참여자 관리
- 메시지·답글·업무 카드 공유
- 본문·스레드 읽음 위치
- 접근 권한을 잃은 직원의 참여 종료

팀 채팅 API는 `openstudy.team-chat.enabled`가 켜져 있을 때만 등록되지만, 서비스와 참여 종료 처리는 계속 동작한다.

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`·`try`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성 메서드
- 한 줄짜리 enum
- 120자를 넘는 줄
  - JPQL 한 줄 문자열(최대 173자)
  - 응답 생성(채널 응답 15개, 메시지 응답 12개 인자)
- 코드 중간의 완전한 이름(`java.security.NoSuchAlgorithmException`)
- 이름이 동사로 시작하지 않는 메서드
  - 리포지토리: `joined`, `manageable`, `end`, `originals`, `replies`, `replyCounts`, `unreadOriginals`, `latestId`, `lastSent` 등
  - 헬퍼: `ChannelInputs.kind`·`label`·`limit` 등, `ChannelAccess.participation`·`visible`, `ReadPositions.unread`·`threads`·`position`
  - 서비스 private: `views`, `found`, `feed`, `store`, `attempt` 등
- `Message.text`와 `Message.taskCard`가 같은 대입 일곱 줄을 반복함

## 2. 검토한 선택지

- `Message.text`·`taskCard`는 이미 동사가 아니어도 정적 팩토리 관례에 맞는 이름이라 그대로 두었다. 두 메서드가 똑같이 채우는 필드는 private `create`로 모았다. 업무 카드는 `relatedTaskId`만 더 채운다.
- 응답 레코드는 모두 생성 지점이 한 곳뿐인 전체 매핑이라 빌더 적용 기준에 맞지 않아 생성자를 유지했다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 이름은 그대로 둔다.
  - 서비스 public 메서드 전부(`MessageService.send` 등은 테스트가 쓴다)
  - DTO
  - 통합 테스트가 spy로 가로채는 `findByConversationIdAndSenderEmployeeIdAndClientMessageKey`
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - `ConversationRepository`: `joined` → `findJoined`, `manageable` → `findManageable`
  - `ConversationMemberRepository`: `end` → `endParticipation`
  - `MessageRepository`
    - `originals` → `findOriginals`, `replies` → `findReplies`, `replyCounts` → `countReplies`
    - `unreadOriginals`·`unreadReplies`·`unreadByThread` → `countUnreadOriginals`·`countUnreadReplies`·`countUnreadByThread`
    - `latestId` → `findLatestId`, `lastSent` → `findLastSent`
  - 바뀐 리포지토리 메서드는 모두 `@Query`가 있어 Spring Data의 이름 해석과 무관하다.
  - `ChannelInputs`: `parseKind`, `toLabel`, `parseDescription`, `parseEmployeeIds`, `parseMessageText`, `parseClientKey`, `parseBefore`, `parseLimit`, `parseId`
  - `ChannelAccess`: `participation` → `requireParticipation`, `visible` → `findVisibleChannel`
  - `ReadPositions`: `unread` → `countUnread`, `threads` → `findThreadStates`, `position` → `calculatePosition`
  - `ConversationMember.active` → `isActive`(모듈 안에서만 쓴다)
- 컨트롤러
  - 메서드 이름은 openapi operationId 15개로 바꿨다.
  - 클래스 매핑 `/api/channels`와 기능 플래그 조건은 그대로 둔다.
  - 메시지 전송과 업무 카드 공유에 똑같이 있던 201/200 응답 분기는 `toSentResponse`로 모았다.
- 호출 순서는 그대로 둔다.
  - DB 호출과 검증, 단락 평가
  - 메시지 저장: 참여 확인 → 입력 → 업무 카드 접근 → 부모 확인 → 키로 이전 행 조회 → 저장
  - 중복 키 충돌 시 한 번 다시 시도하는 구조도 그대로다.
  - 스레드 읽음: 원문 확인 → 답글 확인 → 위치 비교 → 저장
- `AuditEvent` 호출은 위치 인자 그대로 둔다(#265).

## 4. 구현 내용

- 서비스
  - 주입 필드 이름을 타입에 맞췄다.
  - 201·24시간·길이 제한·`SHA-256`과 입력 제한 값을 상수로 바꿨다.
  - 채널·참여자·메시지 응답 변환을 `toView`·`toMemberView`·`toMessageView`로 나눴다.
  - 메시지 본문·키 검사의 부정 조건 나열을 이름 있는 조건으로 바꿨다.
- 리포지토리: JPQL과 네이티브 SQL을 text block으로 옮겼다.
- 엔티티: 필드 어노테이션과 한 줄 대입을 풀었다.
- enum 2개: 상수를 한 줄에 하나씩 썼다.
- DTO 레코드: 구성 요소가 넷 이상이면 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- chat `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- JPQL·SQL 리터럴 토큰 비교(`origin/main` 대비): `ConversationRepository`, `ConversationMemberRepository`, `MessageRepository` 모두 차이 없음.
- 채팅과 이를 쓰는 모듈의 15개 테스트 클래스, 165개 테스트: 모두 통과(실패 0, 건너뜀 0).
  - chat 모듈 전체(채널, 메시지, 중복 키, 업무 카드, 스레드 읽음, 기능 꺼짐)
  - task 모듈 전체
  - employee, authorization 모듈 전체(접근 권한 축소 시 참여 종료 경로 포함)
  - `CollaborationContractTests`, `LocalPreviewIntegrationTests`
- 전체 테스트(`./gradlew test`)는 실행하지 않았다. 로직 변경이 없는 정리라, 영향받는 모듈과 계약 테스트로 확인한다는 방침(사용자 결정)을 따랐다.

## 6. 남은 작업 / 보류 사항

- 이 PR로 #250의 다섯 번째 묶음(payment, ticket, seat, locker, facility, reservation, task, chat)의 작업이 모두 올라간다.
- 다음 묶음: sales, workforce
