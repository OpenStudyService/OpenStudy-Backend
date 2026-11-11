# 2026-11-11 M14·M15 마일스톤 통합 검증 (백엔드)

## 1. 배경 / 문제

- M14(#118~#122, #215)와 M15(#124~#127, #214)는 기능마다 단위 테스트까지만 하고, 통합 테스트와 전체 테스트는 마일스톤 검증으로 미뤘다(사용자 지시).
- 두 마일스톤의 백엔드 PR이 모두 `main`에 병합되어 이번에 한 번에 검증한다.
- 검증 기준: `origin/main` `6e07292`(PR #219 병합 시점)

## 2. 검토한 선택지

- 미룬 인수 조건 대부분에 HTTP 통합 테스트가 없었다. 기존 테스트만 돌리면 그 조건들은 검증되지 않는다.
- 그래서 전체 테스트와 함께 다음 두 통합 테스트 클래스를 새로 썼다.
  - M15: `AnalysisReuseIntegrationTests`
  - M14: `AccountSecurityIntegrationTests`

## 3. 결정 및 이유

### 찾은 버그와 수정
1. **필터 수정 실행이 질문으로 저장됨(#125)**
   - 원인
     - `ai_executions`의 ID는 코드가 정한다. 그래서 `save()`는 새 엔티티를 merge하고, 관리되는 다른 객체를 돌려준다.
     - `persistExecutionAndTrace`는 원래 객체를 돌려줬다.
     - 그래서 그 뒤의 `refines(...)`(origin·원래 실행·고친 기간·상품)가 DB에 반영되지 않았다.
   - 같은 원인: #126에서 후속 질문이 이어받은 기간·상품을 저장하는 `keeps(...)`도 저장되지 않았다.
   - 수정: `save()`가 돌려준 객체를 돌려준다.
   - 단위 테스트는 DB를 거치지 않아 찾지 못했다.
2. **지점 없는 이전 결과를 이어받으면 500(#126)**
   - 이전 실행의 지점 목록이 비어 있으면, 후속 질문이 빈 지점 집합을 계산 범위로 이어받았다.
   - 그러면 매출 집계가 `IllegalArgumentException`을 냈다.
   - 기존 `AiConversationHistoryIntegrationTests`가 이런 데이터로 질문해서 드러났다.
   - 수정: 지점이 있을 때만 이어받고, 없으면 질문 범위의 지점을 쓴다.

### 테스트를 고친 경우(코드 문제 아님)
- 한 지점 직원이 "지점별"로 묻거나 `scope=all`로 묻는 질문은 M10 규칙대로 403이다.
- 새 테스트가 이런 질문을 써서 실패했다. 테스트 질문을 바꿨다.

## 4. 구현 내용

- `AiWorkspaceService`
  - `persistExecutionAndTrace`가 관리되는 엔티티를 돌려준다.
  - 후속 질문은 지점이 있는 이전 결과에서만 지점을 이어받는다.
- 새 `AnalysisReuseIntegrationTests`(7건)
  - 필터 수정
    - 모델 미호출, 새 실행(REFINEMENT)·원래 실행·고친 기간 저장
    - 다시 조회·대화 다시 열기에 반영
  - 필터 수정 거부
    - 권한 밖 지점 403 `BRANCH_SCOPE_DENIED`(그 지점 이름 미노출)
    - `FILTER_NOT_EDITABLE`, `PERIOD_TOO_LONG`, `NO_FILTER_CHANGE`
    - 다른 직원 404
  - 최근 질의: 본인 질문만, 30일 안, 필터 수정 실행 제외, 실행 안 된 질문의 `executionId` null
  - 결과 페이지·정렬
    - 알 수 없는 열 422
    - 매출 권한이 없으면 사유 코드 403 `SALES_PERMISSION_DENIED`
  - 후속 질문
    - 첫 질문 `NO_PRIOR_RESULT`, "같은 조건으로" 이어받기, 표기 변형의 같은 분석
    - 지점 범위 변경 `SCOPE_CHANGED`, 매출 권한 회수 `PERMISSION_LOST`
  - 매출 CSV
    - 화면 순매출과 같은 값, BOM·머리말·헤더, 감사 1행
    - `EXPORT_REPORT_UNKNOWN`, 페이지 query 422, 권한 없으면 403
  - AI CSV: 모델 미호출, 감사 1행, 다른 직원 404
- 새 `AccountSecurityIntegrationTests`(4건)
  - 비밀번호 변경: 틀린 현재 비밀번호 422, 변경 후 현재 세션 유지·다른 세션 401, 새 비밀번호 로그인, 감사 FAILED·SUCCESS, 감사에 비밀번호 없음
  - 메일 발송이 꺼진 상태의 초기화: 503 `PASSWORD_RESET_MAIL_DISABLED`, 기존 비밀번호 유지, 토큰 없음
  - 프로필 사진: 이미지 아닌 파일 422, 같은 지점 동료·OWNER는 볼 수 있음, 다른 지점 직원 404
  - 감사 검색: OWNER만, 기본 기간, 상세, 92일 초과 `PERIOD_TOO_LONG`, 알 수 없는 query 422

## 5. 검증 결과

- **1차 전체 `./gradlew test`**(수정 전, 4 fork): 1,179건 중 1건 실패
  - 실패: `AiConversationHistoryIntegrationTests.reopeningNeverCallsTheModelWhileAskingDoes`(버그 2)
  - 26건은 건너뜀(기존 `@Disabled`·조건부 테스트)
- **새 통합 테스트**: M15 7건, M14 4건 통과(`-PtestForks=1`). 실행 중에 버그 1을 찾아 고쳤다.
- **2차 전체 `./gradlew test`**(수정 후, 4 fork): 119개 클래스 1,190건 통과, 실패 0건, 건너뜀 26건(17분 35초)
- 실행하지 않음
  - Frontend live e2e(`test:e2e:*:live`): 백엔드·DB를 띄운 상태가 필요하다. 이번 범위에 넣지 않았다.
  - 실제 SMTP 메일 발송, S3 사진 저장소: 테스트 설정은 메일 꺼짐·메모리 저장소다.
  - 1,000행이 넘는 실제 원장 CSV(단위 테스트로 형식만 확인)
- `graphify update .`: 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- Frontend M14·M15 화면의 live 검증(#45 병합 완료, #46·#47 진행 예정)
- M14 운영 의존: 사진 S3 버킷·IAM 요청(`docs/deployment/avatar-bucket-request.md`), 재설정 메일 SMTP 설정
- 새 통합 테스트가 다루지 않은 것
  - 로그인 잠금 해제(계정 제한 횟수를 채우는 시나리오)
  - 재설정 링크 사용(메일 켜짐이 필요)
