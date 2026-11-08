# 2026-11-09 실행 이미지·운영 계약 재검증과 로그아웃 쿠키 정리 (#29)

## 1. 배경 / 문제

- #29의 Backend 측 산출물은 PR #53(2026-10-28)으로 들어갔다. 실행 이미지, `prod` 프로필, 필수 환경 변수 검사, 운영 계약 문서다. 이슈는 실제 AWS 검증(Infra #2)이 남아 열려 있다.
- 그 뒤로 main이 크게 바뀌었다.
  - 마이그레이션 V2~V10
  - SSE 제거(#170)
  - DomainLens AI 모듈(ONNX 임베딩, Qwen 모델 모드)
  - 초대 메일 worker, 팀 채팅 비활성 플래그
- 계약 문서와 로컬 검증은 V1·SSE 시절 기준이었다. 지금 main으로 만든 이미지가 같은 계약을 지키는지 다시 확인해야 했다.
- PR #53이 남긴 미해결 항목도 있었다. 로그아웃 응답의 삭제용 `Set-Cookie`가 두 개였고, 그중 Spring Security가 쓴 하나에 `Secure`가 없었다.

## 2. 검토한 선택지

### 로그아웃 쿠키

- **그대로 두고 실제 브라우저에서 확인 후 판단(PR #53 당시 결정)**: 두 헤더 모두 쿠키를 만료시키므로 동작상 문제는 없다. 하지만 운영 응답에 `Secure` 없는 쿠키 헤더가 남는다.
- **`deleteCookies("SESSION")`만 제거**: 살아 있는 세션은 Spring Session이 만료 헤더를 쓰므로 하나로 줄어든다. 그러나 세션이 이미 없는 쿠키(만료·삭제된 세션)로 로그아웃하면 아무도 쿠키를 지우지 않는다. 세션이 없으면 로그아웃의 CSRF 검사를 건너뛰므로 실제로 생기는 경로다.
- **세션이 없는 경우만 Spring Session의 쿠키 직렬화기로 지우기(선택)**
  - 두 경우 모두 헤더가 하나다.
  - 속성(Secure·HttpOnly·SameSite·Path)이 세션 쿠키 설정과 항상 같다.
  - Spring Session이 없는 MVC 슬라이스 테스트에서는 기존 `CookieClearingLogoutHandler`로 돌아간다.

### 문서화 범위

- AI·메일 설정을 운영 필수값으로 정하는 것은 하지 않았다. 운영 AI 방식(실제 모델, 임베딩 모델 배포, 키)은 #97과 사용자 결정 사항이다. 그래서 "기본값으로 두는 선택 설정"과 현재 이미지의 실제 동작만 적었다.

## 3. 결정 및 이유

- 로그아웃은 세 번째 방식으로 바꿨다. 동작(쿠키 만료)은 같고, 운영 응답의 헤더 속성만 일관되게 했다.
- `prod` API 문서(SpringDoc) 비활성화는 이번에 하지 않았다.
  - 외부 프록시가 `/api`만 넘겨 문서 경로는 외부에서 404다.
  - 앱 직접 접근도 인증이 필요하다(401).
  - 운영에서 끌지는 보안 최종 확인(#101)에서 정하도록 문서에 남겼다.

## 4. 구현 내용

- `SecurityConfiguration`
  - `deleteCookies("SESSION")`를 지웠다.
  - 세션이 없을 때만 `CookieSerializer`로 만료 헤더를 쓰는 로그아웃 핸들러를 더했다.
  - 직렬화기 빈은 `ObjectProvider`로 선택적으로 받는다.
- `SessionBoundaryIntegrationTests`
  - 살아 있는 세션의 로그아웃과 세션이 지워진 쿠키의 로그아웃이 각각 `SESSION` 삭제 헤더를 정확히 하나 보낸다.
  - 그 헤더가 `Max-Age=0`, `Path=/`, `HttpOnly`, `SameSite=Lax`를 가지는지 확인한다.
- `docs/deployment/README.md`
  - 2026-11-09 재검증 머리말
  - 이미지 크기(약 1GB, jar 288MB)
  - 기동·종료 시간
  - 선택 설정 표(팀 채팅, AI 모델 모드·키·임베딩 디렉터리·기준 시각·한도, 초대 메일)와 운영 AI 미정
  - 로그아웃 헤더 정리
  - Infra #1 이후 V2~V10 적용
  - actuator·API 문서 노출 범위
  - RDS CA 번들 해시

## 5. 검증 결과

### 로컬 HTTPS 구성(`deploy/local-https`, Docker Desktop, amd64)

이미지는 커밋 `4fd399e`(로그아웃 수정 포함)를 깨끗한 작업 트리에서 빌드했다.

- **빌드**
  - `linux/amd64`, 라벨 revision = 소스 커밋
  - RDS CA 번들 SHA-256 `90a6f1a7…2f21b`
- **이미지 내용**
  - `/app`에 jar·RDS 신뢰 저장소·마이그레이션 SQL(V1~V10, README)만 있다.
  - 루트에 `.env` 파일이 없다.
  - 실행 사용자는 `openstudy`(uid 10001)다.
- **마이그레이션**
  - Infra와 같은 Flyway 11.20.2·MariaDB 3.5.9 드라이버·마이그레이션 계정·TLS 검증으로 V1~V10을 적용했다.
  - 이력 10개, 테이블 74개(이력 포함)
- **OWNER 초기화**
  - 첫 실행 `Initial OWNER created.`
  - 두 번째 실행 `OWNER already exists; no changes made.`
- **기동**
  - 8.5초, health `healthy`
  - 임베딩 모델 없음 WARN(AI 검색 비활성)
  - SpringDoc 기본 활성 WARN
- **HTTPS smoke**
  - CSRF → 로그인 → `/api/me` 200 → 로그아웃 → `/api/me` 401
  - 로그인 쿠키 `Path=/; Secure; HttpOnly; SameSite=Lax`
  - 로그아웃 삭제 헤더 **한 줄**, `Secure` 포함(수정 전에는 두 줄, 하나는 `Secure` 없음)
- **노출 범위**
  - 프록시 경유 `/actuator/*`, `/v3/api-docs`, `/swagger-ui/*`는 404
  - 앱 직접 접근은 `/actuator/health`만 200(`status`와 그룹 이름만), 나머지는 401
- **종료**: SIGTERM 후 약 1.2초, 종료 코드 143
- **필수 환경 변수 누락**
  - `DB_URL`·`TRUSTED_PROXIES`는 `Missing required deployment environment: <이름>`
  - `DB_PASSWORD` 누락은 종료 코드 1
- **DB 계정**
  - 앱 계정의 `CREATE`는 1142로 거부됐다.
  - TLS 없는 접속은 1045로 거부됐다.

### 자동 테스트

- `SessionBoundaryIntegrationTests`, `AuthHttpTests`, `AuthenticationIntegrationTests`, `ProfileIntegrationTests` 통과
- 처음에는 `AuthHttpTests`(WebMvcTest 슬라이스)가 `CookieSerializer` 빈이 없어 컨텍스트를 만들지 못했다. 선택적 의존과 대체 핸들러로 고쳤다.
- 전체 `./gradlew test`: 1,031개 중 실패 0, 건너뜀 26(로컬 전용 AI 25, 조건부 측정 1). BUILD SUCCESSFUL(15분 48초)

### 검증하지 않은 것

- 실제 AWS(ECR push 다이제스트, ECS 태스크, 실제 RDS CA·계정, 공개 HTTPS, ECS bridge의 `TRUSTED_PROXIES` 실측): Infra #2
- 실제 브라우저의 쿠키 삭제·CSRF 동작
- 처리 중인 장시간 AI 요청이 있을 때의 종료 시간(SSE 제거 후 다시 재지 않았다)
- 실행 중 DB 재연결, 이미지 바이트 단위 재현성(PR #53 때와 같음)

## 6. 남은 작업 / 보류 사항

- **Infra #2**: Gateway 자리 결정, 이미지 다이제스트, `TRUSTED_PROXIES` 실측, OWNER 초기화 위치, 공개 HTTPS smoke·브라우저 확인. 운영 RDS가 V1이라면 V2~V10 적용도 필요하다.
- **운영 AI 방식**: 재생 모드·임베딩 모델 미포함 상태를 그대로 둘지는 #97·사용자 결정이 필요하다.
- **운영 API 문서 비활성화**: #101에서 판단한다.
- **이미지 크기**(약 1GB): 임베딩 모델을 이미지에 넣으면 더 커진다. 배포 방식을 정할 때 함께 본다.

## 7. 추가: 임베딩 모델 배포 방식 (같은 날, 사용자 결정)

### 배경

- 운영 이미지에 임베딩 모델이 없어 AI 의미 검색이 꺼진 채 배포된다(6절).
- 모델 파일은 사용자 체크아웃 `openstudy-backend/.models/koen-e5-tiny-q8/`에 있다. 두 파일의 SHA-256이 커밋된 `config/models/koen-e5-tiny-q8-x86_64.json`과 일치한다.

### 검토한 선택지

- **빌드 때 이미지에 복사**: 실행 환경 변경이 없다. 하지만 Git만으로 이미지를 재현할 수 없고 이미지가 커진다.
- **Git(일반·LFS)에 커밋**
  - 라이선스(Apache-2.0, 원본 MIT·Apache-2.0)상 재배포는 가능하다.
  - 저장소가 공개이고, 일반 Git이면 이력이 영구히 커진다.
- **빌드 중 내려받기·변환**: 고정 리비전에 x86_64 파일이 없고 변환 스크립트도 저장소에 없어 재현할 수 없다.
- **Infra가 비공개 S3에 보관하고 배포 때 마운트(사용자 결정)**

### 결정

- 2026-11-09 사용자 결정: 모델 파일은 Git·이미지에 넣지 않는다.
  - 사용자가 모델과 출처·라이선스·변환·버전·SHA-256 정보를 Infra 담당자에게 직접 전달한다.
  - Infra는 비공개 S3에 보관하고 배포 때 사용한다.
- 저장소에는 경로·설정·배포 절차·전달 정보만 문서화한다.
- 모델이 없어도 서버가 기동하는 기존 동작은 유지한다.
- 비활성 상태 확인은 기존 경로를 쓴다: 기동 WARN 로그와 `GET /api/ai/catalog/readiness`의 `warnings`(#93에서 구현). 코드는 바꾸지 않았다.

### 구현

- `docs/deployment/README.md`
  - 11절 신설: 전달 정보 표, 파일별 크기·SHA-256, 배포 절차(S3 → 체크섬 확인 → 읽기 전용 마운트 → `DOMAINLENS_EMBEDDING_DIRECTORY`), 상태별 동작과 확인 방법
  - 3절의 설정 설명과 10절 Infra 전달 목록을 고쳤다.

### 검증(로컬 HTTPS 구성, 이미지 `4fd399e`)

- 사용자 체크아웃의 모델을 `.local/`(Git 제외)에 복사해 compose override로 읽기 전용 마운트했다. 원본 폴더는 읽기만 했다. 확인 후 복사본은 지웠다.
- 모델 마운트
  - 기동 약 11초, `Local embedding ready: JVM=linux-x86_64, model=model_quint8_x86_64.onnx, ... dimensions=384`
  - 준비 상태 `ready: true`, `warnings: []`
- 마운트 없음
  - WARN 로그
  - 준비 상태 `ready: true`, `warnings`에 임베딩 미설치 경고 1개
- 빈 디렉터리 마운트
  - `openStudyEmbeddingGateway` 생성 실패로 기동이 실패했다(종료 코드 1).
  - 그래서 문서에 "빈·일부 디렉터리를 마운트하지 않는다"를 명시했다.

### 검증하지 않은 것

- 실제 S3 보관·내려받기, ECS 볼륨 마운트, EC2 호스트 권한(Infra #2)
- x86_64 양자화본의 정확한 변환 옵션: 스크립트가 없어 기록하지 못했다.
