# 기반 선택 데이터와 단일 인스턴스 SSE

Issue [#17](https://github.com/OpenStudyService/OpenStudy-Backend/issues/17)의 현재 구현 계약은 OpenAPI `0.1.4-workspace`의 `GET /lookups`, `GET /workspace/events`다. 서버 접두사는 `/api`이며 WORKSPACE 세션의 현재 ACTIVE 계정만 허용한다.

## 선택 데이터

`GET /api/lookups?scope=all 또는 지점 ID`는 현재 허용 지점 범위를 다시 검사한다. `branches`, `employees`, `products`, `permissionDefinitions`, `permissionPresets`, `productCategories`, `asOf`, `snapshotVersion`을 반환한다.

- 지점은 구조화 영업시간·상태가 있는 현재 지점 DTO다. 허용된 중지 지점은 이력·설정 조회를 위해 포함하며 신규 거래 허용을 뜻하지 않는다.
- 직원 선택 데이터는 정확히 `{id,name}`이다. 허용 지점의 활성 업무 대상과 활성 OWNER만 반환하고 이메일·사진·계정 상태·소속·권한·승인 대기자를 노출하지 않는다. 직원 관리 DTO를 대신하지 않는다.
- 프리셋은 현재 요청 범위에서 관리 권한이 있는 사용자에게만 제공한다. 일반 직원에게는 빈 배열이다. 권한 사전은 기존 공개 서비스를 재사용한다.
- 상품·분류 API는 아직 구현되지 않았으므로 해당 배열은 빈 배열이다. 기존 초안·시연 DB 자료를 운영 선택 데이터로 승격하지 않는다. 다른 업무 영역은 이 계약에 추가하지 않는다.
- scope=all은 허용 지점의 합집합이다. 미배정 계정의 지점·직원 선택지는 정상 빈 배열이며, 인증/권한 실패를 빈 배열로 바꾸지 않는다.

과거 로컬 샘플 선택 데이터는 충돌을 피하기 위해 `/api/local-preview/lookups`로 옮겼다. local 프로필·기존 preview 옵션에서만 제공한다. 운영 `/api/lookups`는 local 프로필에서도 실제 최소 계약을 따른다.

## 스트림과 프론트 동작

```text
GET /api/workspace/events?scope=1
Accept: text/event-stream

event: resync
data: {"type":"resync","resource":"lookups","entityId":null,"branchId":null,"at":"..."}
```

모든 연결과 재연결은 `resync`로 시작한다. 클라이언트는 현재 화면·본인·선택 데이터를 재조회한다. 호환용 `cursor`와 `Last-Event-ID`는 재생에 사용하지 않는다. SSE id는 연결별 논리 힌트이며 전역 outbox 순서나 타 지점 활동량을 노출하지 않는다.

- `invalidate`는 허용 범위의 조회 무효화 신호다. 업무 본문·개인정보를 싣지 않는다. 허용된 지점 사건만 지점/자원 ID를 제공하되, 직원 사건의 entityId는 항상 null로 지운다. 소속 회수로 최소 선택 데이터에서 제외된 직원 ID가 일반 직원의 스트림에 남지 않도록 한다.
- 전역 직원 목록의 무효화는 관련 지점 사용자에게만 보낸다. 식별자를 지우고 lookups를 다시 읽게 하므로 계정 제한·소속 회수 후 남은 오래된 선택지를 제거할 수 있다. 전역 프리셋 무효화는 관리자에게만 식별자 없이 보낸다.
- 대상 직원의 권한 변경은 `authorization.changed`를 그 직원에게만 보내고 연결을 닫는다. entityId/branchId는 null이며 다른 지점 목록을 포함하지 않는다. 클라이언트는 스트림과 개인 캐시·초안을 정리하고 `/me`를 다시 읽는다.
- 다른 세션의 개인 프로필 변경은 식별자 없는 `resync`로 본인 정보도 다시 읽게 한다.
- 로그아웃·현재 계정 제한·유휴/최대 만료는 데이터 없이 연결을 끝낸다. 클라이언트 재연결/본인 조회는 기존 401 정책으로 개인 상태를 정리한다. ASYNC 완료 dispatch가 이미 시작된 SSE에 JSON 인증 오류를 덧붙이지 않도록 최초 요청 인가와 지속 스트림 인가를 구분한다.

## 저장과 전송 경계

기존 지점·직원·권한 명령의 outbox 기록을 재사용한다. dispatcher는 별도 연결에서 커밋된 미발행 행만 읽는다. 큰 ID부터 커밋되더라도 이전 작은 ID를 건너뛰지 않도록 최대 ID cursor 대신 published_at 미설정 행을 계속 조회한다. 롤백된 outbox는 전달하지 않는다.

dispatch는 업무 트랜잭션을 중지한 별도 읽기/갱신 경계에서 동작하며 HTTP 전송을 업무 트랜잭션 안에서 실행하지 않는다. JDBC 세션과 계정·소속·역할·grants를 전달 직전과 주기적으로 다시 확인한다. 세션을 조회만 하고 저장하지 않으므로 heartbeat·자동 재연결은 유효 활동 시각을 갱신하지 않는다.

전송 뒤 published 표시는 현재 단일 프로세스에 연결된 수신자를 대상으로 한 best-effort 처리다. 수신자의 적용 확인이나 이벤트 재생을 보장하지 않는다. 연결 실패·서버 재시작 후에는 resync와 재조회로 최신 상태를 회복한다. 다중 프로세스가 같은 published 표시를 소비하는 fan-out 구조는 지원하지 않는다. 여러 ECS task를 운영하기 전에 전달 수단 또는 인스턴스별 cursor를 별도로 설계·검증해야 한다.

## 실행 설정과 검증 경계

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `openstudy.workspace.validation-millis` | 500 | dispatch 완료 후 다음 세션·권한 검사/발행 tick까지의 대기 시간 |
| `openstudy.workspace.heartbeat-millis` | 15000 | SSE comment heartbeat 간격. 사용자 활동이 아님 |
| `openstudy.workspace.max-connections` | 512 | 한 프로세스의 동시 스트림 상한. 초과 연결은 공통 429 |
| `openstudy.workspace.scheduling.enabled` | true | 운영 dispatcher 활성화. 결정적 통합 시험에서만 false로 두고 tick을 직접 실행 |

위 값은 구현 설정이며 사용자 확정 업무 정책이 아니다. 500ms는 DB/전송 시간이 없는 경우의 재검사 간격이며 엄격한 실시간 SLA를 뜻하지 않는다. `Cache-Control: no-store`, `Content-Type: text/event-stream`, `X-Accel-Buffering: no`를 제공한다. 운영 프록시는 SSE buffering을 꺼야 한다. 로컬 직접 HTTP 성공을 운영 TLS·프록시·다중 ECS 검증으로 대신하지 않는다.

Frontend는 이 JSON으로 기존 `npm run api:foundation` 생성 경로를 실행해 `Lookups`와 `WorkspaceEvent`를 동기화한다. 최소 직원 목록에 관리용 `Employee` 필드가 있다고 가정하지 않아야 한다. 관련 범위의 실제 브라우저 검증 결과는 [통합 검증 #18](https://github.com/OpenStudyService/OpenStudy-Backend/issues/18)에 연결한다.
