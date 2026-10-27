# 2026-10-27 SSE 수명과 DB 연결 반환

관련: [Frontend #6](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/6), [Backend #18](https://github.com/OpenStudyService/OpenStudy-Backend/issues/18).

## 1. 배경 / 문제

실제 SMTP 메일 링크의 초대 흐름 4개가 통과한 뒤 같은 서버에서 브라우저 context를 반복 생성·종료하자 초대 생성 500과 로그인이 30초 이상 기다리는 문제가 나타났다. 서버에서 Hikari 기본 풀 10개가 `active=10, idle=0`으로 고갈되었고, MySQL 프로세스 목록은 Sleep 상태이며 잠금 대기가 없었다. 단순히 브라우저 기대 시간을 늘려도 해결되지 않았다.

SSE 연결의 권한 조회는 읽기 트랜잭션이지만 기본 `spring.jpa.open-in-view=true`가 EntityManager 수명을 장기 HTTP 요청에 묶어 DB 연결을 유지했다. 기존 전체 197개 테스트 통과만으로 실제 브라우저의 반복 연결 안정성을 보장할 수 없었다.

## 2. 검토한 선택지

풀 크기나 테스트 타임아웃 증가는 연결 보유를 제거하지 않는다. API는 DTO와 JDBC로 응답하므로 뷰에서 영속 엔티티의 지연 로딩이 필요하지 않다. Open EntityManager in View를 끄고 서비스 트랜잭션 종료 시 연결을 반환하도록 결정했다.

## 3. 결정 및 이유

공통 `application.yaml`에 `spring.jpa.open-in-view=false`를 명시했다. 인증·인가 정책과 SSE 이벤트 계약, 상품 코드는 수정하지 않았다. 단순 로그 출력량 문제로 단정하지 않고 작은 풀에서 실제 연결 보유를 측정해 변경 전후를 비교했다.

## 4. 구현 내용

- `WorkspaceConnectionIntegrationTests`에서 전용 Testcontainers MySQL과 최대 2개 연결의 Hikari 풀을 사용한다.
- 실제 로그인 후 SSE 3개를 순차로 열고, 브라우저 응답 body를 열린 채 유지하는 동안 매번 풀의 active 연결이 0으로 돌아오는지 확인한다. 풀 크기보다 많은 스트림을 유지한 상태에서 `/api/me`도 200이어야 한다.
- 테스트 HTTP 클라이언트는 body를 닫고 `shutdownNow`하여 실패 시에도 Java 21 `HttpClient.close`가 열린 스트림 때문에 무기한 대기하지 않게 했다.

## 5. 검증 결과

- 설정 변경 전 신규 검사는 2초 이내 active 연결 0 복귀 조건에서 **실패**했다. 이 실행을 성공으로 집계하지 않는다. 첫 진단 실행에서 테스트 클라이언트 종료가 대기하여 중단했고 fixture 종료를 보완했다.
- 설정 변경 후 신규 연결 풀 검사·기존 Workspace SSE·LocalPreview MySQL 집중 **15개 모두 통과**, bootJar 성공(1분21초).
- 수정된 동일 서버를 유지한 실제 SMTP·두 Chrome context live suite **4개씩 3회 연속(12개) 통과**, 각1.6분. 서버 로그에 Hikari 연결 부족 0건. 원시 메일·비밀번호·token을 trace 또는 저장소에 남기지 않는 Frontend 별도 테스트로 확인했다.
- 최초 지점 홈 수정만 적용한 전체 회귀 **197개/25개 suite**는 실패·오류·건너뜀 0으로 통과했다. 이 수치는 공통 설정 변경 전 결과다.
- 공통 설정 변경 후 최종 `test bootJar` **198개/26개 suite**, 실패·오류·건너뜀 0, **6분21초 BUILD SUCCESSFUL**.
- AST `graphify update .` 갱신 완료. SQL/PDF parser 의존성 부재와 Gradle AST 경고는 코드 탐색의 제약이며 실행 결과와 구분한다.

## 6. 남은 작업 / 보류 사항

클라이언트 SSE 종료 시 `AsyncRequestNotUsableException`을 일반 JSON 오류로 처리하면서 남는 로그 소음은 관찰했으나, 이번 수정에서 예외 응답 정책을 바꾸지 않았다. 운영 TLS/프록시·부하 시험과 Backend #18 전체 검증은 별도 범위다. 원격 main 변경과 PR 병합은 사용자가 수행한다.
