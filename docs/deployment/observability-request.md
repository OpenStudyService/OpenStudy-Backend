# 운영 관측 요청 사항 (Backend #102)

[Backend #102](https://github.com/OpenStudyService/OpenStudy-Backend/issues/102)의 인프라 요청과 운영 확인표다. ADR-M11-001 M11-D04에 따라 별도 모니터링 서버 없이 Actuator·Micrometer·JSON 표준 출력 로그를 ECS의 CloudWatch에서 본다. 인프라 작업은 이 문서와 이슈 체크리스트로 요청하고 결과를 기록한다(M11-D02).

## 1. 앱이 제공하는 것 (2026-11-11, #102 구현)

### 상태 점검 (인증 없음, 상태만)

| 경로 | 의미 | 쓰는 곳 |
| --- | --- | --- |
| `/actuator/health/liveness` | 프로세스가 살아 있음. DB와 무관 | 컨테이너 health(ECS, 이미지 `HEALTHCHECK`) |
| `/actuator/health/readiness` | 요청을 받을 준비가 됨. **DB 장애 시 503** | 트래픽 투입 판단(로드밸런서 대상 그룹 등) |
| `/actuator/health` | 위 둘을 합친 값 | 사람이 볼 때 |

- 응답에는 `status`(와 그룹 이름)만 있다. 구성 요소·상세는 보이지 않는다.
- 다른 actuator 경로(`metrics`, `env` 등)는 HTTP로 노출하지 않는다.

### 요청 로그 (prod 프로필, 한 줄 JSON)

API 요청마다 끝에 한 줄을 쓴다. Logstash 형식이며 주요 필드는 다음과 같다.

| 필드 | 예 | 설명 |
| --- | --- | --- |
| `@timestamp`, `level`, `logger_name`, `message` | | 공통 |
| `requestId` | UUID | 응답 헤더 `X-Request-ID`와 같다. 요청 중 쓴 모든 로그에 붙는다 |
| `employeeId` | `42` | 로그인한 직원. 익명 요청에는 없다 |
| `method`, `route` | `GET`, `/api/sales/revenue` | `route`는 경로 **템플릿**이다(`/api/ai/executions/{executionId}`). 실제 ID·query는 남기지 않는다 |
| `status`, `durationMs` | `200`, `35` | 응답 코드, 처리 시간(ms) |
| `branchIds` | `3` 또는 `3,5` | 요청이 지점을 지정했을 때만(경로 `{branchId}` 또는 `branchId`·`branchIds` query) |
| `aiRequestId`, `aiTraceId`, `outcome` | `req-…`, `trc-…` | 자연어 질문 줄. M10 진단 로그(`/api/ai/traces`)와 같은 ID라, `requestId`로 HTTP 요청과 이어진다 |

- 남기지 않는 것: 요청 본문·query·실제 경로, 비밀번호·해시, 세션·CSRF 토큰, 이메일·연락처
- 지표: Micrometer가 `http.server.requests`(경로 템플릿·상태·결과별 횟수·시간)를 모은다. HTTP로 내보내지 않으므로, CloudWatch 지표는 아래 로그 지표 필터로 만든다.

## 2. 인프라 요청 사항

| # | 요청 | 기대 결과 | 상태 |
| --- | --- | --- | --- |
| 1 | ECS 태스크 정의의 health 명령을 `curl -fsS http://localhost:8080/actuator/health/liveness`로 바꾸기 | DB 장애 때 정상 컨테이너를 다시 시작하지 않는다 | 요청 전 |
| 2 | 로드밸런서·대상 그룹이 있으면 health 경로를 `/actuator/health/readiness`로 | DB 장애 때 트래픽을 넣지 않는다 | 요청 전 |
| 3 | 앱 로그를 CloudWatch 로그 그룹(예: `/ecs/openstudy-backend`)으로 보내기(`awslogs`) | Logs Insights에서 `requestId`로 검색된다 | 요청 전 |
| 4 | 로그 그룹 보존 기간 **90일** | 90일 지난 로그가 자동 삭제된다(ADR-M11-002 M11R-D09, 2026-11-11 확정). 로그에 이메일·연락처는 없고 직원 ID는 있다 | 요청 전 |
| 5 | 로그 지표 필터(네임스페이스 예: `OpenStudy/Api`) | 아래 3절 지표가 생긴다 | 요청 전 |
| 6 | 경보 후보(아래 3절) 등록과 알림 대상 | 장애를 알림으로 받는다 | 요청 전 |

- 상태 칸은 전달·회신 결과로 고친다. 회신 원문은 #102 이슈 댓글에 남긴다.

## 3. 지표 필터와 경보 후보

| 지표 | 필터 패턴 | 값 | 차원 |
| --- | --- | --- | --- |
| `Requests` | `{ $.durationMs >= 0 }` | 1 | `route` = `$.route` |
| `ServerErrors` | `{ $.status >= 500 }` | 1 | `route` |
| `ClientErrors` | `{ $.status >= 400 && $.status < 500 }` | 1 | `route` |
| `Latency` | `{ $.durationMs >= 0 }` | `$.durationMs` | `route` |

| 경보 후보 | 조건(초안, 운영 데이터 보고 조정) |
| --- | --- |
| 서버 오류율 | 5분간 `ServerErrors` / `Requests` > 1% |
| 지연 | 5분간 `Latency` p95 > 2,000ms |
| 준비 실패 | 대상 그룹 unhealthy 대상 ≥ 1이 5분 지속 |
| 재시작 반복 | ECS 태스크 중지가 10분에 2회 이상 |

## 4. 운영 확인표

| 항목 | 확인 방법 | 결과 |
| --- | --- | --- |
| 로그가 JSON 한 줄로 들어옴 | Logs Insights `fields @timestamp, requestId, route, status, durationMs` | 미검증 |
| `requestId`로 한 요청의 로그가 모임 | 응답 헤더의 `X-Request-ID`로 검색 | 미검증 |
| AI 질문이 진단 로그와 이어짐 | 같은 `requestId`의 `aiRequestId`가 `/api/ai/traces`에 있음 | 미검증 |
| 지표 필터 4개 값이 생김 | CloudWatch 지표 콘솔 | 미검증 |
| DB 장애 시 readiness 503, 컨테이너 유지 | 준비 환경에서 DB 연결 차단 | 미검증(로컬 시험은 통과) |
