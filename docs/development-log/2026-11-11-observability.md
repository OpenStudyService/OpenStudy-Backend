# 2026-11-11 상태 점검·요청 지표·구조화 로그 (#102)

## 1. 배경 / 문제

- NFR-OPS-05·NFR-PERF-01: 서비스 상태와 요청 성공·실패·처리량·오류율·지연을 CloudWatch에서 볼 수 있어야 한다.
- 방식은 ADR-M11-001 M11-D04로 정해져 있다.
  - 상태 점검·지표: Actuator·Micrometer
  - 로그: JSON 표준 출력, ECS의 CloudWatch
  - 별도 모니터링 서버는 두지 않는다.
- 지금 상태
  - `/actuator/health` 하나뿐이었다. DB 포함 여부와 liveness·readiness 구분이 없었다.
  - 로그는 사람이 읽는 형식이었다.
  - 요청 로그 한 줄에는 요청 ID와 상태만 있었다.
  - AI 질문의 진단 로그 ID(`req-…`)와 HTTP 요청 ID를 이을 방법이 없었다.

## 2. 검토한 선택지

| 항목 | 선택지 | 결과 |
| --- | --- | --- |
| JSON 로그 | Spring Boot 기본 구조화 로그(`logging.structured.format.console`) / logstash-logback-encoder 추가 | 기본 기능. Boot 4.1에 이미 있고 의존성이 늘지 않는다 |
| 형식 | Logstash / ECS | Logstash. MDC와 key-value가 최상위 필드라 CloudWatch 필터(`$.status`)가 단순하다 |
| CloudWatch 지표 | Micrometer CloudWatch 레지스트리(의존성·AWS 권한 추가) / 로그 지표 필터 | 로그 지표 필터. 앱에 AWS 권한·의존성이 필요 없다. Micrometer는 앱 안에서 `http.server.requests`를 계속 모은다 |
| 컨테이너 health | 기존 `/actuator/health` 유지 / liveness로 변경 | liveness. readiness에 DB를 넣으면 기존 경로도 DB 장애 때 503이 된다. 그러면 ECS가 정상 프로세스를 계속 다시 시작한다 |

## 3. 결정 및 이유

- **health**
  - `show-details`·`show-components`는 `never`다. HTTP로는 health만 노출한다.
  - liveness·readiness 확인 경로를 켰다.
  - readiness는 `readinessState`와 `db`를 본다.
  - 이미지 `HEALTHCHECK`를 liveness로 바꿨다. ECS 태스크 정의 변경은 인프라에 요청했다.
- **요청 로그 한 줄**
  - 남기는 것: 경로 템플릿, 방식, 상태, 처리 시간, 직원 ID, 지점 ID
  - 실제 경로·query·본문은 남기지 않는다(기존 원칙).
  - 기존 메시지(`API requestId=… status=…`)는 유지했다. 로그를 검사하는 기존 시험이 이 문구를 본다.
- **직원 ID**
  - 세션 필터가 요청 속성과 MDC에 넣는다.
  - 요청 로그 줄은 Security 필터 바깥에서 쓰므로 MDC 대신 요청 속성을 읽는다.
- **지점**: 경로 변수 `{branchId}` 또는 `branchId`·`branchIds` query만 쓴다. 본문은 읽지 않는다.
- **AI 연결**
  - 질문을 처리할 때 `aiRequestId`·`aiTraceId`·`outcome`을 한 줄 남긴다.
  - MDC의 HTTP `requestId`가 같은 줄에 붙어, 진단 로그와 HTTP 요청이 이어진다.
- **이번 범위에서 뺀 것**: 멱등 기록 정리 작업(저장소 점검 2번). 관측과 다른 일이라 따로 다룬다.

## 4. 구현 내용

- `application.yaml`: `management` 설정(health만 노출, 상세 숨김, liveness·readiness, readiness에 DB)
- `application-prod.yaml`: `logging.structured.format.console: logstash`
- `SecurityConfiguration`: liveness·readiness 경로 익명 허용
- `ApiRequestFilter`: 처리 시간과 key-value(경로 템플릿·방식·상태·시간·직원·지점)
- `AccountSessionFilter`: 직원 ID를 요청 속성과 MDC에 넣는다.
- `AiWorkspaceService`: AI 질문의 ID 연결 로그
- `Dockerfile`: `HEALTHCHECK`를 `/actuator/health/liveness`로
- 문서
  - 새 `docs/deployment/observability-request.md`: 앱 제공 항목, 인프라 요청 6개, 지표 필터·경보 후보, 운영 확인표
  - `docs/deployment/README.md`: 상태 점검 표와 로그 형식

## 5. 검증 결과

- 새 `ObservabilityTests` 3건 통과
  - health 3개 경로가 익명 200이고 상태만 보인다.
  - `metrics`·`env`는 익명으로 401 또는 404다.
  - `http.server.requests`가 경로 템플릿·상태별 횟수와 시간을 모은다.
  - 요청 로그 줄에 경로 템플릿과 시간이 있고 query는 없다.
- 새 `StructuredLogAndReadinessTests` 2건 통과
  - 시험 설정: prod와 같은 Logstash 형식, DB health를 DOWN으로 바꾼 별도 컨텍스트
  - readiness는 503이고 상태만 보인다. liveness는 200이다.
  - 로그인 후 요청의 JSON 줄에 `requestId`·`route`·`status`·`employeeId`·`durationMs`가 있다.
  - 비밀번호·세션·CSRF 토큰·이메일은 출력에 없다.
- `./gradlew fastTest`(공통 필터·보안 설정 변경이라 AGENTS.md 기준): 120개 클래스 1,162건 통과, 실패 0건, 건너뜀 26건(기존과 같음), 10분 27초
  - 구조화 로그 시험의 별도 컨텍스트 뒤에도, 로그 문구를 검사하는 기존 시험(로그인 감사·API 계약)이 통과했다.
- 실행하지 않음
  - CloudWatch 로그·지표·경보: 인프라 요청 회신이 필요하다.
  - 실제 DB 장애 상황: 로컬 시험은 DB health를 DOWN으로 바꿔 확인했다.
  - 이미지 다시 빌드와 컨테이너 `HEALTHCHECK` 실행
- `graphify update .`: 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 인프라 담당에게 요청 사항(문서 2절)을 전달하고, 회신과 운영 확인표 결과를 #102에 기록한다(사용자 진행).
- 로그 보존 기간은 #99 결정 뒤 정한다.
- 경보 기준값은 초안이다. 운영 데이터를 보고 조정한다.
- 멱등 기록 정리 작업은 따로 다룬다.
