# 승인 제한 세션과 유효 활동 만료 경계

## 배경 / 문제

B04의 기존 로그인은 DB 계정 상태만 보고 승인 후 업무 접근을 허용했다. 이 방식은 신규 직원이 승인되면 기존 제한 세션이 자동 승격되어 Q10의 재로그인 정책을 지키지 못한다. JDBC/Servlet의 last-accessed 시각은 자동 조회·SSE 재연결에도 연장되므로 유휴 만료의 유효 활동 기준과 분리해야 한다.

## 검토한 선택지

- 현재 계정 상태에서 세션 종류를 매 요청 추론: 상태 변경이 즉시 보이지만 승인 이후 자동 업무 승격을 막을 수 없다.
- 로그인 시 고정된 sessionMode와 시각을 세션에 저장: 재로그인으로만 모드가 바뀌며 JDBC 재기동에서도 경계를 유지한다.
- 유효 활동 표시 헤더를 새로 도입: 수동 요청과 임의 polling을 구분하기 쉽지만 프론트 전체 요청 경로와 공개 계약에 영향을 준다.
- 현재 자동 조회 끝점을 비활동으로 분류: 기존 API 입력을 바꾸지 않고 현재 계약의 상태·CSRF·lookup·SSE 자동 조회를 제외한다. 새 자동 polling API는 분류 추가가 필요하다.

## 결정 및 이유

고정 sessionMode와 독립된 `authenticatedAt`·`lastEffectiveActivityAt`을 JDBC 세션에 저장하고 주입 가능한 Clock으로 정확한 경계를 검사한다. 헤더 추가는 보류하고 endpoint 기반 활동 분류를 사용한다. 일반 성공 업무 GET과 변경은 활동이며 `/me`, `/auth/csrf`, `/invitations/approval-status`, `/lookups`, `/workspace/events`, event-stream Accept, HEAD/OPTIONS 및 실패 응답은 연장하지 않는다.

## 구현 내용

- ACTIVE 로그인은 WORKSPACE, PENDING은 APPROVAL_ONLY. `/me` 응답에 sessionMode를 추가하고 제한 모드에서는 branches/memberships를 비우며 별명·아바타·소유자 권한을 반환하지 않는다. status는 실제 계정 상태다.
- APPROVAL_ONLY는 승인 후에도 유지한다. 본인 승인 상태가 `requiresLogin=true`를 반환하고 다시 로그인할 때 세션 ID·CSRF를 교체한다. 기존 ACTIVE 직원의 추가 지점 PENDING 소속은 업무 모드를 바꾸지 않는다.
- 제한 세션은 본인 정보·본인 승인 상태·CSRF·로그아웃·재로그인 외 업무 요청을 거부한다. 승인 조회 SQL은 accepted_employee_id로 서버에서 본인을 고정하고 수락된 초대만 반환한다. 이메일 쿼리로 타인을 선택할 수 없다.
- 계정 정지·퇴사·삭제는 기존대로 매 요청 DB에서 확인하고 세션을 폐기한다. WORKSPACE에서 계정이 PENDING으로 되돌아가도 업무 세션을 폐기한다.
- 인증 시작 후 12시간 또는 유효 활동 후 60분 경계 이상에서 세션을 무효화한다. 필수 속성이 없는 배포 이전 로그인 세션도 재로그인하도록 만료한다.
- 기존 CSRF 교체, 동일 로그인 오류, JDBC 인증 principal의 비밀번호 제거와 쿠키 설정을 유지한다. HTTP 초대 생성/수락/승인 명령은 추가하지 않았다.

## 검증 결과

- 독립 변경 단계에서 기존 AuthHttpTests 통과.
- 전용 Testcontainers MySQL 8.4.11 및 실제 HTTP로 신규 SessionBoundaryIntegrationTests 8개 통과: 제한 세션 최소 응답·본인 승인 조회·승인 후 재로그인·ACTIVE 추가 소속 대기·3599/3600초·활동 유지 중 12시간·실패/SSE 비활동·계정 퇴사·쿠키 삭제·JDBC 속성 재조회.
- 별도 Spring 애플리케이션 인스턴스를 실제 기동→로그인→종료→재기동하고 같은 쿠키가 JDBC 세션으로 인증됨을 확인했다. 모든 연결 대상은 해당 테스트 컨테이너로 고정했다.
- B03 `00b20480287ce9a9a12461e3ec63afe691417ced`를 작업 브랜치에 fast-forward 반영한 뒤 `./gradlew test bootJar --no-daemon` 성공: 전체 81개, 실패·오류·스킵 0, bootJar 성공. 보존용 임시 stash는 복원 후 제거되었다.
- event-stream Accept의 복수 미디어 타입·파라미터 처리 보완 후 신규 8개 통합 테스트와 bootJar를 재검증해 성공했다. `git diff --check` 통과.

## 남은 작업 / 보류 사항

- SSE 스트림을 전달하는 B11 구현은 아직 없으므로 열린 스트림의 실시간 종료·heartbeat 만료 검수는 B11 통합 시 확인해야 한다. 이 변경은 SSE 요청/재연결이 유효 활동을 연장하지 않는 경계를 제공한다.
- 향후 업무 목록 자동 polling을 추가하면 비활동 분류를 함께 추가해야 한다. 현재 변경은 새 헤더·heartbeat API를 추가하지 않는다.
- 실제 TLS/운영 쿠키 도메인 검수는 운영 실행 환경에서 수행해야 한다. 운영/개발 실제 사용자 DB에는 접근하지 않았다.
