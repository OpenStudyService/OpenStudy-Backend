# 기본 로그인

프론트엔드의 이메일·비밀번호 폼과 `/api` 계약에 맞춘 쿠키 세션 인증이다. 기본 Spring Security 폼 로그인과 HTTP Basic을 사용하지 않는다.

## 로컬 최초 관리자 생성

비어 있는 개발 DB에는 로그인할 직원이 없으므로 백엔드 루트의 `.env`에 다음 값을 추가한다. 예제 비밀번호 대신 본인이 사용할 비밀번호를 지정한다. Java properties 형식이므로 값에 따옴표를 붙이지 않는다.

```properties
spring.profiles.active=local
BOOTSTRAP_OWNER_EMAIL=owner@openstudy.example
BOOTSTRAP_OWNER_PASSWORD=replace-with-your-own-password
```

백엔드를 다시 시작하면 `local` 프로필에서만 최초 `OWNER` 계정을 생성한다. 비밀번호는 8자 이상, UTF-8 기준 72바이트 이하로 설정하며 BCrypt 해시로 저장한다. 기존 OWNER가 있으면 추가 생성하거나 비밀번호를 덮어쓰지 않는다. 생성이 끝나면 `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD` 두 줄을 제거해도 된다. 운영 계정 생성·비밀번호 재설정 절차는 별도로 구현해야 한다.

백엔드 작업 디렉터리는 `openstudy-backend`로 지정한다. 프론트엔드 폴더에서는 다음 명령으로 실제 백엔드에 연결한다.

```powershell
npm run dev:live
```

일반 `npm run dev`는 MSW 데모 모드다. live 모드에서는 Vite가 `/api`를 `http://localhost:8080`으로 프록시하므로 브라우저의 서로 다른 origin에 쿠키를 직접 전달하는 설정은 필요하지 않다. 표시되는 프론트엔드 주소로 접속하고 위에서 만든 이메일·비밀번호로 로그인한다. DB_USERNAME/DB_PASSWORD는 MySQL 접속용이고, 로그인 폼은 employees 테이블의 직원 계정을 사용한다.

## API와 보안 동작

| 요청 | 동작 |
|---|---|
| `GET /api/auth/csrf` | 익명 세션과 CSRF 토큰 `{token}` 발급, `Cache-Control: no-store` |
| `POST /api/auth/login` | JSON `{email,password}`와 `X-CSRF-Token` 검증, DB의 BCrypt 해시 확인, 세션 ID 및 CSRF 교체, `CurrentUser` 응답 |
| `GET /api/me` | 현재 직원·유효 소속 권한·접근 가능한 운영 지점 반환 |
| `POST /api/auth/logout` | 유효 세션의 CSRF 검증 후 세션과 SESSION 쿠키 삭제, `{ok:true}` 반환. 세션이 이미 없으면 CSRF 없이도 성공 |

프론트엔드 API 클라이언트는 이미 `credentials: include`, CSRF 요청과 로그인·로그아웃 후 토큰 재발급을 처리한다. 비인증 요청은 로그인 화면 리다이렉트나 브라우저 비밀번호 팝업 대신 JSON 401을 받는다. 비밀번호는 JSON 응답과 세션 principal에 저장하지 않는다.

JDBC 세션은 미사용 60분 또는 로그인 후 12시간에 만료된다. 인증된 요청에서 직원 상태를 DB로 다시 확인하므로 정지·탈퇴·삭제된 계정의 세션은 폐기된다. 승인 대기 직원은 로그인과 `/api/me` 조회가 가능하고 업무 API는 거부된다. `/api/me`는 소속과 권한을 재조회한다. 응답의 직원·지점 version은 프론트 계약에 맞춰 DB의 0 기반 version에 1을 더해 표시한다. 향후 업무 API는 각 자원·지점에 대한 별도 권한 검증을 구현해야 한다.

계정별 15분 내 10회 로그인 시도 제한을 적용한다. 이 제한은 프로세스 메모리 기준이므로 ECS 다중 인스턴스 운영 시 공유 저장소 등의 제한으로 확장해야 한다. 운영 HTTPS에서는 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`를 주입한다. SESSION 쿠키는 HttpOnly와 SameSite=Lax를 사용하며 프론트/API는 같은 origin의 `/api` 프록시를 전제로 한다.

실제 기능 구현 범위는 로그인·로그아웃·현재 사용자와 기본 세션 보호다. 직원 초대, 프로필 수정, 지점 업무 API, 홈 통계와 SSE 등은 아직 구현하지 않았다. `local` 프로필에서는 [조회 전용 샘플](local-preview.md)을 제공하여 홈·기본 목록을 확인할 수 있다. 다른 미구현 API 오류는 별도 작업 대상이다.

## 검증

JDK 21, Docker 실행 상태에서 백엔드 폴더에서 실행한다.

```powershell
.\gradlew.bat test --no-daemon
```

2026-10-23 검증에서 인증 HTTP 테스트 10개, Docker MySQL 인증 통합 테스트 3개와 기존 테스트 32개, 총 45개가 통과했다. 인증 통합 테스트는 별도의 Testcontainers DB에서 실제 HTTP 쿠키·세션 ID 교체·JDBC 세션 저장과 재조회·CSRF 교체·로그아웃·소속 회수·계정 정지·최초 관리자 생성과 기존 비밀번호 보존을 검증했다. 로컬 개발 DB에는 테스트 데이터를 쓰지 않는다.
