# 2026-10-27 초대 승인 직원의 로컬 지점 홈 접근 수정

관련: [Frontend #6](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/6), [Backend #18](https://github.com/OpenStudyService/OpenStudy-Backend/issues/18).

## 1. 배경 / 문제

수락 API [PR #37](https://github.com/OpenStudyService/OpenStudy-Backend/pull/37) 병합 후 실제 SMTP 수신 링크와 두 Chrome context로 초대 흐름을 검증했다. 일반 직원은 수락·관리자 승인·재로그인을 마치고 실제 ACTIVE 소속이 생겼지만 구체 지점의 `/api/home?scope={id}`가 403이었다.

`LocalPreviewData.snapshot`이 직원 목록 투영을 필터링하려고 `MANAGE_PEOPLE`의 구체 지점 scope를 요구했다. 해당 직원 정보를 볼 수 없는 일반 직원의 홈·샘플 조회까지 거부하는 문제가 있었다.

## 2. 검토한 선택지

직원에게 관리 권한을 부여해 통과시키는 방식은 실제 권한 정책을 왜곡한다. 직원 투영에 필요한 허용 지점 목록을 조회하고 권한이 없는 직원 행만 제거하는 기존 의도를 유지하기로 했다.

## 3. 결정 및 이유

허용 목록은 `scope=all`의 `MANAGE_PEOPLE`로 계산한다. 권한이 없으면 빈 목록을 얻고 직원 정보만 숨긴다. 구체 지점으로 제한한 응답에서는 그 지점의 직원 행만 남기도록 추가 필터링한다. 기존 scope 접근 검사는 앞에서 수행하므로 타 지점 접근을 넓히지 않는다. OWNER 본인 정보 예외도 유지한다.

사용자·상품 작업의 기존 변경을 보존하기 위해 main `b650acd` 기반 독립 worktree의 `fix/invitation-workspace-access`에서 진행했다. 공통 인증/인가 서비스와 상품 코드 영역은 변경하지 않았다. 반복 검증에서 발견한 SSE 연결 반환 수정은 [별도 기록](2026-10-27-workspace-sse-connection-lifetime.md)에 남겼다.

## 4. 구현 내용

- 로컬 샘플 직원 투영의 관리 권한 검사를 선택적 필터로 수정했다.
- 실제 MySQL 통합 테스트로 일반 직원의 자기 지점 홈·샘플 lookup 200, 직원 목록 비노출, 다른 지점 홈·lookup 403을 확인한다.
- fixture의 소속은 해당 테스트 직원 ID로만 정리한다.

## 5. 검증 결과

- Java 21.0.12.1, Docker Desktop 27.2.0, Testcontainers MySQL 8.4.11. `LocalPreviewIntegrationTests` 5개 및 bootJar 통과.
- 첫 집중 실행은 새 fixture에서 실제 enum에 없는 `global_role=STAFF`를 사용해 1개 실패했다. `EMPLOYEE`로 바로잡은 뒤 전체 집중 5개가 통과했다. 이 실패를 제품 결함이나 성공 검증으로 집계하지 않는다.
- 실제 일회용 MySQL+SMTP worker+Mailpit 링크로 초대 신규·승인·재로그인·추가 지점 수락 및 새 지점 홈 브라우저 검증을 통과했다. Frontend의 별도 live suite에서 기록한다.
- `graphify update .`: 2,034 nodes/5,471 edges. SQL/PDF 의존성 부재와 Gradle AST 경고는 탐색 자료의 제한이다. 추적하지 않던 graphify 산출물은 커밋하지 않는다.
- 전체 `test bootJar`: **197개/25개 suite**, 실패·오류·건너뜀 0, **9분45초 BUILD SUCCESSFUL**.

## 6. 남은 작업 / 보류 사항

로컬 샘플 홈은 실제 업무 모듈 구현이 아니다. 운영 SMTP/TLS/수신함·운영 HTTPS 프록시와 Backend #18 전체 시나리오는 이번 수정의 완료 범위가 아니다. 브랜치 push·PR 준비 후 사용자 병합을 기다린다. Backend #15 본문·상태는 변경하지 않는다.
