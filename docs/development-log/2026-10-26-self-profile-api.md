# M1 본인 닉네임 저장 API (#27)

## 배경 / 문제

설정 화면이 호출하는 `PATCH /api/me/profile`이 Backend main에 없었다. 관리자 직원 API는 OWNER의 이름·계정 상태 변경용이므로 본인 닉네임 저장을 대신할 수 없다. [#27](https://github.com/OpenStudyService/OpenStudy-Backend/issues/27), 직원 [#12](https://github.com/OpenStudyService/OpenStudy-Backend/issues/12), 통합 검증 [#18](https://github.com/OpenStudyService/OpenStudy-Backend/issues/18), Frontend [#13](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/13) 및 관련 main 구현을 확인했다.

세션 복구 [PR #24](https://github.com/OpenStudyService/OpenStudy-Backend/pull/24), 지점 [PR #25](https://github.com/OpenStudyService/OpenStudy-Backend/pull/25), 직원·권한 [PR #26](https://github.com/OpenStudyService/OpenStudy-Backend/pull/26)의 병합 결과와 기존 조직 결합·직원 권한 개발 기록을 재사용했다. 이슈가 열려 있다는 이유로 기반 인증·직원 운영을 다시 구현하지 않았다.

최신 origin을 fetch한 뒤 main `dad40b8`에서 `feature/self-profile`을 생성했다. 기존 AGENTS.md 변경과 `2026-10-24-user-owned-main-merge.md`는 작업 전부터 있던 사용자 변경으로 이번 커밋에서 제외한다.

## 검토한 선택지

- 관리자 직원 수정 API 재사용은 OWNER만 허용하고 닉네임을 받지 않아 요구사항과 맞지 않는다. 본인 전용 HTTP 명령을 추가하고 기존 인증·직원 계정 조회 및 공통 입력·버전 검증을 재사용했다.
- 계정 공통 version 대신 프로필 전용 version을 두면 관리자 이름·상태·소속 편집과 충돌을 놓친다. 같은 employees.version을 사용한다.
- 권한 변경용 기존 감사 함수를 그대로 쓰면 닉네임 저장에도 AUTHORIZATION_CHANGED를 기록하게 된다. 공통 감사/이벤트 기록을 재사용하되 프로필은 INVALIDATE를 기록하도록 구분했다.

## 결정 및 이유

현재 사용자 ID는 세션 principal에서만 결정한다. `nickname`, `version`만 수용하고 모든 쿼리 매개변수와 그 외 본문 필드를 422로 거부한다. 사진·이메일·이름·상태·owner·권한·소속·테마·비밀번호는 저장 대상이 아니다. 기존 닉네임 VARCHAR(100)에 맞춰 공통 StrictInput의 원문 UTF-16 길이 상한 100과 blank 거부, strip 규칙을 사용한다. 별도의 중복 금지 정책은 만들지 않았다.

기존 AuthorizedCommands의 guard → 직원 → 지점 → 현재 소속 잠금 순서에 본인 계정 명령을 추가한다. 잠금 이후 현재 ACTIVE 상태를 locking read로 재검사하므로 먼저 읽은 InnoDB snapshot의 ACTIVE 정보를 사용할 수 없다. 관리자 계정·소속 변경과 같은 직렬화 경계에서 version을 검사한다. WORKSPACE 세션만 허용하며 기존 AccountSessionFilter의 제한·만료·CSRF 정책을 그대로 적용한다.

## 구현 내용

- AuthController → CurrentUserService → EmployeeAccountService의 공개 서비스 경계로 저장하고, 기존 CurrentUser DTO를 성공 응답과 GET /api/me·로그인에 동일하게 사용한다.
- 성공마다 employees.version은 한 번 증가하며 updated_by는 본인, updated_at은 기존 DB 시각 규칙을 따른다. 닉네임 외 계정 정보와 지점 소속·권한은 유지한다.
- 409 VERSION_CONFLICT에 latestVersion과 `/api/me` reloadUrl을 제공한다. 입력 보존 → 최신 정보 확인 → 명시적 재저장 계약을 문서화했다.
- 닉네임·version·추적 정보, 감사 전후 snapshot·actor·서버 requestId, 본인 및 직원 목록 INVALIDATE outbox를 같은 트랜잭션으로 저장한다. 자격증명을 포함하는 EmployeeAccount 전체를 감사/응답에 직렬화하지 않는다.
- 지점 scope와 무관한 `/api/me/profile`은 기존 일반 지점 interceptor에서 분리하고 전용 controller·세션·트랜잭션 경계에서 검사한다. 다른 업무 API의 scope 규칙은 유지한다.
- OpenAPI `0.1.3-profile`에 ProfileUpdate 및 실제 CurrentUser의 SessionUser·SessionMembership·SessionBranch 모델을 명세했다. 관리용 Employee·Membership 모델의 목표 계약을 현재 사용자 응답에 강제하지 않는다.
- [프로필 계약과 타입 동기화](../design/foundation/profile-api.md), 요구사항 추적표, API 엑셀을 같은 정본에 맞췄다. 생성 타입은 Frontend의 기존 `npm run api:foundation`으로 동기화한다.

## 검증 결과

### 실행 환경과 명령

- Java 21.0.12 계열, 프로젝트 Gradle wrapper. 사용자 DB를 쓰지 않는 기존 @MySqlIntegrationTest/@ServiceConnection 구성을 사용했다.
- Testcontainers MySQL 8.4.11 고정 이미지 `mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70`, 재사용 없음. 기존 실패 전용 datasource 정책을 유지했다.
- 첫 `gradlew.bat test bootJar`: 111개 중 2개 실패. 로그아웃의 기존 200 응답을 204로 잘못 기대한 시험을 수정했고, 프로필 쿼리가 공통 지점 scope 검사에서 먼저 차단되는 경계를 수정했다. 실패 결과를 통과로 계산하지 않는다.
- 수정 후 `gradlew.bat test bootJar`: 전체 112개, 실패·오류·건너뜀 0 및 bootJar 성공.
- 이후 오래된 snapshot의 계정 제한 회귀 시험을 추가하여 `gradlew.bat test --tests com.openstudy.modules.auth.ProfileIntegrationTests --tests com.openstudy.architecture.ModuleBoundaryTests bootJar`: 프로필 10개+모듈 경계 1개, 실패·오류·건너뜀 0 및 bootJar 성공. 최종 추가 시험은 이 별도 실행에서 확인했으며 전체 113개를 한 번에 실행했다고 주장하지 않는다.

### 인수 조건별 확인

| 조건 | 결과 |
| --- | --- |
| 활성 OWNER·MANAGER·STAFF의 본인 저장·재조회·재로그인 | 실제 HTTP 쿠키·CSRF 세션으로 3역할 각각 저장, GET /me와 로그인 응답 및 MySQL 값 확인 |
| 다른 직원 ID 조작 방지 | 본문 id/employeeId 422, query id/employeeId/scope 422, ID 추가 경로 404, STAFF의 타 직원 관리 수정 403. 다른 직원 보존 |
| 이메일·owner·상태·소속·권한·사진 등 불변 | 미허용 필드 모두 422. DB 계정 고정 필드·소속·grants·version·감사/outbox 무변경 확인 |
| 빈 값·길이·타입·version·미허용 필드 | 공통 VALIDATION_FAILED/fieldErrors/requestId. 100자 성공·101자 거부, blank/null/타입/잘못된 version 거부 |
| 미인증·CSRF·제한 세션·계정 제한 | 401/403 및 저장 없음. APPROVAL_ONLY는 승인 뒤에도 재로그인 전 거부. PENDING/SUSPENDED/LEFT 계정 및 오래된 ACTIVE snapshot에서 잠금 후 재검사 |
| 기존 계정 version·추적 정보·관리자 경합 | API=DB+1, 한 번 증가, updated_by/updated_at 확인. 관리자 선행 편집과 역방향 409, 동시 HTTP 저장 1성공/1충돌. rollback 시 계정·감사·outbox 복원 |
| 성공·현재 사용자 응답의 닉네임과 비밀값 미노출 | 새 닉네임·version·snapshotVersion 및 공통 no-store 확인. 비밀번호·해시·토큰 미노출. 실제 응답 필드와 정본 schema 대조 |
| 확정 OpenAPI·생성 타입 | Node.js 24·openapi-typescript 7.13.0으로 별도 출력 폴더에 타입 생성 성공. strict tsc로 정상 nickname/version 요청·CurrentUser 응답 타입, 이메일 입력 및 version 누락의 컴파일 오류 확인 |

API 엑셀은 기존 생성 도구·서식을 재사용하고 JSON과 같은 버전/원본 hash를 확인했다. 8개 탭 유지, API 41개·모델 61개, 오류 scan 0건, 요청/업무 규칙 렌더 확인을 수행했다. 기존 행 비교에서 사용안내·개수·CurrentUser 참조 외 변경이 없으며 오류·보안/확정정책은 그대로임을 확인했다. 검증용 제작 도구·타입·미리보기는 저장소 밖 outputs에 보관한다.

`git diff --check` 통과. `graphify update .`로 AST 그래프를 갱신했다(추가 API 비용 없음). 기존에 커밋된 backend 그래프는 없었으므로 생성 결과는 로컬 탐색 자료로 보관하며 기능 커밋에는 포함하지 않는다. SQL parser 미설치, Gradle AST 미지원 경고는 그래프 추출 제한이며 실제 SQL·Java 실행 결과와 구분한다.

## 남은 작업 / 보류 사항

- 사용자가 이번 범위를 Backend만으로 지정했다. 현재 Frontend 설정은 avatar를 함께 보내며 version이 없어 새 API에서 거부된다. Frontend #13에서 nickname/version만 보내도록 수정하고 사진 입력을 분리한 뒤, MSW를 끈 실제 저장 → 표시 갱신 → 새로고침 → 재로그인·실패 입력 보존 브라우저 검증을 수행해야 한다. Backend #18의 M1 증거에 연결한다.
- 사진 업로드·형식/파일 저장, 비밀번호·테마 변경은 제외했다. SSE 전달·운영 프록시/TLS 검증은 기존 B11/V01 범위다.
- 이 PR은 서버 범위의 구현·시험을 제공하며 Issue #27 전체 또는 M1 전체의 실제 화면 인수 완료를 의미하지 않는다. 원격 main 변경·PR 병합·자동 병합은 수행하지 않고 사용자가 병합한다.
