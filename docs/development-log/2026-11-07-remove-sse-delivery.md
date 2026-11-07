# 2026-11-07 SSE 전달 경로 제거와 요청 시 인가 전환 (#170)

## 1. 배경 / 문제

- ADR-COM-001(2026-11-06 사용자 확정)은 업무 화면의 SSE·polling을 쓰지 않고 사용자 행동으로 다시 읽기로 했다. 권한 회수는 다음 API 요청에서 현재 서버 상태로 막는다(COM-D05).
- 정책 문서만 병합된 상태였다. 코드에는 다음이 남아 있었다.
  - 공통 SSE `GET /api/workspace/events`와 500ms 발행 스케줄러, 전달 전용 세션·권한 검사
  - AI SSE 호환 경로 `GET /api/ai/requests/{id}/events`, `GET /api/requests/{id}/events`
  - 업무 명령 26개 파일, 116곳에서 쓰는 `workspace_event_outbox` 행
- SSE를 없애면 outbox 행을 읽는 곳이 사라진다. 쓰기를 그대로 두면 행이 계속 쌓인다(#170 인수 조건).

## 2. 검토한 선택지

- **Outbox 쓰기 정리**
  - 호출까지 모두 제거: 변경 범위가 크지만 쓰이지 않는 코드가 남지 않는다.
  - `WorkspaceOutbox`만 아무것도 저장하지 않게 바꾸기: 변경은 작고 #174의 캐시 무효화 지점으로 다시 쓸 수 있다. 하지만 116곳의 호출이 실제로는 아무 일도 하지 않는 채로 남는다.
- **테이블**
  - 유지, 마이그레이션 없음
  - 새 마이그레이션으로 삭제: 쌓인 행이 함께 사라지고, #173의 V8이 아직 main에 없어 번호 맞추기가 추가로 필요하다.

## 3. 결정 및 이유

- 사용자가 "호출까지 모두 제거"와 "테이블 유지, 마이그레이션 없음"을 골랐다.
- #174(캐시)에 필요한 무효화 신호는 그 이슈에서 따로 설계한다. 지금 남겨 둘 이유가 없다.
- 테이블 삭제는 이슈에서도 결정하지 않은 사항이다. 이번 범위에서는 스키마를 바꾸지 않는다.
- 세션 정책(유휴 60분·최대 12시간)과 서버 인가·버전·중복 검증은 그대로 둔다.

## 4. 구현 내용

- **제거한 코드**
  - `common/outbox` 패키지 전체(`WorkspaceOutbox`, `WorkspaceEvent`, `WorkspaceEventType`, `WorkspaceEventRepository`)
  - `WorkspaceEventService`, `WorkspaceScheduling`, `WorkspaceOutboxRepository`, `WorkspaceAccessService`, workspace `WorkspaceEvent` DTO
  - 전달 전용 세션 검사 `WorkspaceSessionService`
  - 전달 판단에만 쓰던 `ShiftService.announcedTo`, `AttendanceService.announcedTo`, `EmployeeAccountService.relatedToBranches`(+저장소 쿼리), `ShiftRepository.employeeOf`
  - `WorkspaceController`의 SSE endpoint, `AiWorkspaceController.streamEvents`와 `AiWorkspaceService.streamRequestEvents`
    - 참고: AI SSE 경로는 실행 ID만 알면 본인 확인 없이 상태를 돌려줬다. 경로가 사라지면서 이 노출도 없어졌다.
- **업무 명령**: 24개 서비스·저장소에서 outbox 주입과 호출을 지웠다. 호출만을 위한 도우미도 함께 지웠다.
  - `CustomerService.invalidate`, `ProductService.invalidate`와 그 인자로만 쓰던 영향 지점 집합, `NoticeService.announce`
  - `EmployeeChanges`는 감사 기록만 남겼다.
- **세션 활동 분류**(`SessionBoundaryPolicy.effectiveActivity`): 없어진 SSE 경로와 `Accept: text/event-stream` 예외를 지웠다. `/api/me`·`/api/lookups`·승인 상태·CSRF 조회는 여전히 활동이 아니다.
- **계약·문서**
  - `openapi.json`에서 `GET /workspace/events`, `WorkspaceEvent` 스키마, `workspace` 태그와 다른 operation 설명의 SSE·outbox 언급을 지웠다.
  - `workspace-api.md`를 명시적 조회·요청 시 인가 설명으로 바꿨다. `api.md`·`authentication.md`·배포 README와 모듈 문서의 이벤트 항목에 제거 사실을 표시했다.
  - `application*.yaml`의 SSE 주석을 일반 장시간 요청 기준으로 고쳤다.
- **테스트**
  - 삭제: `WorkspaceConnectionIntegrationTests`, `ShiftEventDeliveryIntegrationTests`, `AttendanceEventDeliveryIntegrationTests`, 각 모듈의 outbox 행 수 검사와 스트림 테스트
  - `WorkspaceIntegrationTests`를 다시 썼다.
    - SSE 경로 세 개가 404다.
    - 소속 변경 명령 후 outbox 행이 늘지 않는다.
    - 다른 세션에서 소속을 회수하면 다음 `/api/lookups`가 403이다.
    - 회수 뒤 다시 읽은 선택 데이터에서 직원이 빠진다.
    - 로그아웃·계정 제한 후 다음 `/api/me`가 401이다.
    - 유휴·최대 시간 초과 후 다음 요청이 401이다.
  - `CollaborationContractTests`: 계약에 변경 알림 스트림이 없음을 확인한다.

## 5. 검증 결과

- 전체 `./gradlew test`(85개 스위트): 985개 중 961개 통과, 23개 건너뜀(로컬 전용 AI 테스트), 실패 1
  - 실패한 것은 새 테스트 `noChangeNotificationStreamIsServed`였다. AI 경로는 라우팅보다 AI 권한 확인이 먼저라, AI 권한이 없는 직원은 404가 아니라 403을 받았다.
  - OWNER로 AI 경로를 확인하도록 고친 뒤 `WorkspaceIntegrationTests` 7개가 모두 통과했다. 테스트만 바뀐 수정이라 전체 스위트는 다시 돌리지 않았다.
- 테스트 수가 줄어든 이유: SSE 전달·outbox 행만 검사하던 테스트를 지웠다(전달 테스트 클래스 3개, 공통 기록 2개, 공지 2개, 사물함·룸 각 1개).
- **미검증**
  - 실제 Frontend와 실제 API에서의 명시적 재조회·401 이후 정리: Frontend #77 범위다.
  - 운영 프록시·HTTPS 환경
  - 기존 DB에 쌓인 `workspace_event_outbox` 행 수: 이 환경에서 조회하지 못했다.

## 6. 남은 작업 / 보류 사항

- `workspace_event_outbox` 테이블 삭제 여부는 별도 판단이 필요하다. 지운다면 새 마이그레이션으로 한다.
- Frontend는 아직 `/api/workspace/events`에 연결을 시도한다. 이 PR이 먼저 병합되면 연결이 404로 실패한다. 화면 데이터 조회 자체는 REST라 영향이 없지만, Frontend #77과 함께 배포하는 편이 낫다.
- #17·#10의 "운영 TLS/프록시의 SSE buffering·heartbeat 검증" 항목은 대상이 없어졌다. 해당 이슈에 연결해 정리해야 한다.
- AI 요청 취소(`POST /api/ai/requests/{id}/cancel`)는 본인 확인 없이 아무 실행이나 CANCELED로 바꾼다. `GET /api/executions/{id}`도 실행한 직원을 확인하지 않는다(지점 권한만 다시 확인). 코드로 확인했으며 이번 범위 밖이라 고치지 않았다. 별도 수정이 필요하다.
