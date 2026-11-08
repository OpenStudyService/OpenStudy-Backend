# 2026-11-08 팀 채팅 API 비활성화 플래그 (#167)

## 1. 배경 / 문제

- 팀 채팅은 서비스 범위에서 빠졌다(ADR-M10-002 M10-D09·D10, SRS v1.14 POL-50).
- M7에서 구현한 채널·메시지·읽음·업무 카드 API는 지우지 않고 설정으로 끄기로 했다. 되살릴 가능성을 남기고, 지금은 마이그레이션·DROP 위험을 지지 않기 위해서다.
- ADR은 "플래그 이름과 꺼졌을 때의 응답(404 여부)은 구현 이슈에서 정한다"고 남겼다.
- 함께 처리하는 Frontend #70(팀 채팅 화면 비활성화)과 합쳐야 채팅 메뉴 정리가 끝난다.

## 2. 검토한 선택지

- **끄는 방법**
  - 컨트롤러를 조건부로 등록(`@ConditionalOnProperty`): 꺼져 있으면 경로 자체가 없다. 서비스 코드에 분기가 생기지 않는다.
  - 요청 필터나 서비스에서 플래그를 확인해 거절: 응답 코드를 고를 수 있지만 모든 경로에 분기가 퍼진다.
- **꺼졌을 때의 응답**
  - 404(경로 없음): 삭제한 SSE 경로(#170)와 같은 응답이다. 기능이 없는 서버와 구별되지 않는다.
  - 별도 오류 코드(예: 410·403): 클라이언트가 이유를 알 수 있지만 새 오류 계약이 필요하다.
- **접근 축소 시 채널 참여 종료(`ChannelParticipationEnding`)**
  - 계속 동작: 남은 행이 현재 접근 권한과 맞게 유지된다.
  - 플래그를 따라 끔: 꺼진 동안 회수된 직원이 플래그를 다시 켜면 채널에 남아 있게 된다.

## 3. 결정 및 이유

- 플래그는 `openstudy.team-chat.enabled`(환경 변수 `OPENSTUDY_TEAM_CHAT_ENABLED`, 기본값 `false`)로 정했다. 기존 설정(`openstudy.invitation-mail.enabled`)과 같은 형식이다.
- 컨트롤러 조건부 등록 + 404로 정했다.
  - 채널·메시지 데이터가 나갈 경로가 아예 없다.
  - #170에서 삭제한 경로와 같은 응답이라 새 오류 계약이 필요 없다.
  - 미인증 요청은 그 전에 401이다(보안 필터가 먼저 처리).
- 참여 종료는 플래그와 상관없이 계속 돌린다. 비용이 거의 없고, 다시 켰을 때 권한이 없는 직원에게 채널이 보이는 일을 막는다.
- 계약(`openapi.json`)은 operation을 지우지 않고 표시만 했다. 플래그를 켜면 그대로 동작하는 기록이기 때문이다.

## 4. 구현 내용

- `application.yaml`: `openstudy.team-chat.enabled` 추가(기본 `false`)
- `ChannelController`: `@ConditionalOnProperty(name = "openstudy.team-chat.enabled", havingValue = "true")`
- `ChannelParticipationEnding`: 동작 변경 없음. 꺼진 동안에도 계속 도는 이유를 주석에 남겼다.
- `openapi.json`
  - `/channels/**` operation 15개에 `deprecated: true`, `x-feature-flag`, `x-disabled-by-default: true`를 달았다.
  - 각 설명 앞에 비활성 안내를 붙였다.
  - `channels` 태그 설명과 `info.description`에 #167 내용을 더했다.
- `collaboration-api.md`: 2026-11-08 항목으로 플래그 이름·응답·참여 종료 결정을 기록했다.
- 테스트
  - `ChannelTestSupport`에 `@TestPropertySource(openstudy.team-chat.enabled=true)`를 달아 기존 채널·메시지·읽음·업무 카드 테스트는 플래그를 켠 상태로 돈다.
  - 신규 `TeamChatDisabledIntegrationTests`(기본값 끔)
    - 저장된 채널·메시지가 있어도 조회 9개 경로, 쓰기 7개 경로가 모두 404이고 응답에 채널 이름·메시지 원문이 없다. DB 행도 바뀌지 않는다.
    - 미인증 요청은 401이다.
    - 소속 회수·계정 비활성이 플래그가 꺼진 상태에서도 성공하고 채널 참여를 끝낸다.
  - `CollaborationContractTests`: `/channels/**` operation만 비활성 표시가 있고 15개 모두 표시됐는지 확인한다.
- 마이그레이션 없음. 채팅 테이블과 `modules/chat` 코드는 그대로다.

## 5. 검증 결과

- 채팅 통합 테스트(플래그 켬) + 신규 비활성 테스트 + 계약·모듈 경계·영속성 접근 테스트: 10개 클래스 136개 통과
- 전체 `./gradlew test`: 88개 클래스 998개 중 실패 0, 건너뜀 25(로컬 전용 AI 테스트)
- **미검증**
  - 운영 환경에서 플래그 기본값으로 기동했을 때의 실제 응답(테스트는 같은 설정 파일을 쓰는 HTTP 통합 테스트로만 확인)
  - Frontend 연동(Frontend #70 범위)

## 6. 남은 작업 / 보류 사항

- Frontend #70: 팀 채팅 화면·채널 설정·업무 카드 공유를 숨기고 채팅 탭을 AI 채팅으로 바꾼다. Frontend 생성 타입에는 이 operation들이 `deprecated`로 표시된다.
- 채팅 코드·테이블 삭제는 하지 않는다(M10-D10). 다시 결정할 때 별도 이슈로 다룬다.
