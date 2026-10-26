# M1 본인 닉네임 저장 계약

Issue [#27](https://github.com/OpenStudyService/OpenStudy-Backend/issues/27)의 확정 계약은 [openapi.json](openapi.json)의 `PATCH /me/profile`이다. API 서버 접두사는 `/api`다. 사진은 M14이며 이 요청에 포함하지 않는다.

## 요청과 응답

활성 OWNER·MANAGER·STAFF의 WORKSPACE 세션과 유효한 `X-CSRF-Token`이 필요하다. 대상은 세션의 직원 ID로 정하며 경로에 ID를 받지 않는다. 쿼리 매개변수도 허용하지 않는다.

```http
PATCH /api/me/profile
Content-Type: application/json
X-CSRF-Token: <GET /api/auth/csrf의 token>

{"nickname":"새 별명","version":1}
```

- `nickname`, `version`은 모두 필수다. 그 외 필드는 422와 `fieldErrors`로 거부한다. 이메일·이름·owner·상태·소속·권한·사진·비밀번호·테마는 이 API로 바꿀 수 없다.
- 닉네임은 공백만 있는 값과 Java UTF-16 기준 원문 길이 100 초과를 거부한 뒤 앞뒤 공백을 제거한다. 기존 `VARCHAR(100)`보다 느슨하게 검증하지 않는다. 닉네임 중복 금지 정책은 추가하지 않는다.
- version은 `GET /api/me`의 `user.version`이다. 기존 직원 계정 및 소속 관리자 편집과 같은 계정 버전이며 API 값은 DB 값+1이다. 성공 요청마다 한 번 증가한다.
- 성공은 200 `CurrentUser`이며 저장된 `user.nickname`, 새 `user.version`, `updatedAt`, `snapshotVersion`을 반환한다. 기존 로그인·본인 조회 DTO를 재사용한다. `Cache-Control: no-store`와 서버 요청 ID를 제공한다.
- `CurrentUser`의 `SessionUser`, `SessionMembership`, `SessionBranch`는 현재 실제 응답을 명세한다. 직원 관리의 `Employee`, `Membership`, `Branch` 편집 모델과 구분한다. 비밀번호·세션 ID·CSRF 토큰을 응답/감사 snapshot에 넣지 않는다.

## 충돌과 실패

관리자가 이름·상태·소속을 먼저 편집했거나 다른 화면이 저장했다면 409 `VERSION_CONFLICT`와 `latestVersion`, `reloadUrl: /api/me`를 반환한다. 실패 요청은 닉네임·감사·outbox를 남기지 않는다. 화면은 미저장 입력을 보존하고 `/api/me`로 최신 정보를 확인한 뒤 사용자가 다시 저장하도록 한다. 최신 version으로 자동 재전송하여 덮어쓰지 않는다.

미인증/만료/현재 계정 제한은 기존 401 계약을, 승인 제한 세션과 잘못된 CSRF는 기존 403 계약을 따른다. APPROVAL_ONLY 세션은 승인 이후에도 재로그인 전에는 저장할 수 없다. 저장 트랜잭션에서도 잠금 후 현재 ACTIVE 상태를 다시 확인한다.

guard → 직원 → 지점 → 현재 소속 순서의 기존 잠금 경계를 사용한다. 닉네임, 계정 version·updated_by·updated_at, 자격증명 없는 전후 감사 snapshot·actor·requestId, 본인/직원 목록 INVALIDATE outbox를 한 트랜잭션으로 기록한다. 이 API는 권한을 바꾸지 않으므로 AUTHORIZATION_CHANGED를 발행하지 않는다. outbox 기록은 SSE 전달 완료를 뜻하지 않는다.

## 생성 타입 동기화

Backend의 `openapi.json`이 정본이다. Frontend 저장소에서 Node.js 24로 다음 명령을 실행한다.

```powershell
npm run api:foundation
npm run check
npm run build
```

`api:foundation`은 인접 Backend의 정본으로 `src/shared/api/foundation.generated.ts`를 생성한다. 요청 타입은 `components["schemas"]["ProfileUpdate"]`, 응답은 `components["schemas"]["CurrentUser"]`이며 경로는 `paths["/me/profile"]["patch"]`다. 과거 전체 화면 계약의 `api:generate`는 별도 입력을 사용하므로 이 정본을 대체하지 않는다.

Frontend [#13](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/13)에서 닉네임과 계정 version만 보내고 사진 입력을 분리한다. 사용자의 범위 지정에 따라 이번 Backend 작업에서는 Frontend 코드를 수정하지 않고 실제 설정 화면의 MSW 없는 브라우저 저장 검증은 #13과 Backend [V01 #18](https://github.com/OpenStudyService/OpenStudy-Backend/issues/18)의 M1 후속 검증으로 남긴다.
