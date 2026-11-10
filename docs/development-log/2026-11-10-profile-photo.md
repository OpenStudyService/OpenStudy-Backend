# 2026-11-10 프로필 사진 업로드 검증과 S3 저장 (#120)

## 1. 배경 / 문제

- 본인 프로필 사진을 서버에서 검증해 저장하고, 다시 방문해도 보이게 해야 한다(FR-SET-01·02).
- 계약은 #118(PR #208, `account-collab-api.md` "프로필 사진")에서 확정됐다.
  - M14-D03: S3 비공개 버킷
  - M14-D10: 파일 시그니처 판별, 5MB
  - M14-D13: 앱 경유 조회
  - M14-D14: 지점 공유 직원과 OWNER만 열람
- 지금까지 `SessionUser.avatar`·`Employee.avatar`는 `avatar_url` 원값을 그대로 내보냈다. 저장 키가 응답에 나가지 않도록 바꿔야 했다.

## 2. 검토한 선택지

- **S3 접근 방법**
  - AWS SDK v2 `s3` 모듈을 쓰는 방법과, SigV4 서명을 직접 구현하는 방법이 있었다.
  - 직접 구현은 서명·오류 처리를 따로 검증해야 해서 SDK를 택했다.
  - 버전은 BOM `2.57.0`(2026-10-19 배포)이다.
- **저장소 선택**: 운영 S3, 로컬 개발용 디렉터리, 테스트용 메모리를 같은 인터페이스(`AvatarStore`)로 두고 설정으로 고른다.
- **SDK 추가로 테스트 JVM이 실행되지 않은 문제**
  - 원인: 테스트 작업이 실행 클래스패스 전체를 시스템 속성으로 넘겼다. SDK jar가 더해져 34,632자가 되면서 Windows 명령줄 한계를 넘었다(CreateProcess error=206).
  - netty 클라이언트를 빼는 방법: 32,078자로 여전히 한계 근처였고, 의존성이 늘면 다시 넘는다.
  - 클래스패스를 파일로 넘기는 방법을 택했다. Gradle이 `build/owner-bootstrap/<task>-classpath.txt`에 쓰고, `OwnerBootstrapTests`가 읽어 자식 프로세스를 Java `@argfile`로 실행한다. 자식 프로세스의 `-cp`도 같은 한계에 걸리기 때문이다.

## 3. 결정 및 이유

- **저장 키**
  - 형식은 `avatars/<직원ID>/<uuid>.<jpg|png|webp>`다. 응답에는 키 대신 `/api/employees/<id>/avatar?v=<uuid>`를 넣는다(`AvatarKeys`).
  - 사진을 바꾸면 경로가 바뀌므로 `Cache-Control: private, max-age=86400`으로 캐시해도 이전 사진이 보이지 않는다.
  - 기존 `avatar_url`에 남은 다른 형식의 값(이전 URL)은 사진이 없는 것으로 본다. 경로 이탈(`..`)도 키 형식에서 막힌다.
- **저장 순서**
  1. 새 키로 파일을 쓴다.
  2. DB에 키를 저장한다(계정 version +1, 감사 `AVATAR_CHANGED`/`AVATAR_REMOVED`, 감사에는 키 대신 사진 유무만).
  3. 커밋 뒤 이전 파일을 지운다.
  - DB 저장이 실패하면 새 파일을 지운다.
  - 이전 파일 삭제가 실패해도 성공으로 답한다. 그 파일은 DB에 키가 없어 조회되지 않는다.
- **크기 상한**
  - 서블릿 multipart 상한은 6MB로, 계약의 5MB보다 조금 크게 잡았다. 5MB 초과는 서비스가 422로 답한다.
  - 6MB를 넘는 요청도 공통 처리기에서 같은 422 `AVATAR_TOO_LARGE`로 답한다.
- **조회 경로 권한**
  - `/api/employees/**`는 기존 권한 인터셉터가 `people:manage`를 요구한다.
  - 사진 조회는 일반 직원도 동료 사진을 봐야 하므로 이 경로만 인터셉터에서 빼고, 사진 서비스가 M14-D14로 판정한다.
- **S3**
  - 객체마다 SSE-S3(`AES256`)를 요청한다. 자격증명은 AWS 기본 체인(실행 역할)에서 얻는다.
  - 버킷·IAM 요청은 `docs/deployment/avatar-bucket-request.md`에 정리했다.

## 4. 구현 내용

- `employee/dto/AvatarKeys`: 키 생성·해석·응답 경로
- 사진 경로 반영
  - `CurrentUserService`(로그인·`GET /me`·프로필 저장 응답)와 `EmployeeManagementService`(직원 응답)의 `avatar`를 경로로 바꿨다.
  - `Employee.changeAvatar`, `EmployeeAccountService.changeAvatar`를 추가했다(본인 명령 잠금 순서).
- `account` 모듈
  - `AvatarService`: 시그니처 판별, 업로드·삭제·조회, 열람 판정
  - `AvatarController`
    - `PUT /api/me/avatar`: `file` 파트 하나만 받는다. 다른 파트·필드·query는 422.
    - `DELETE /api/me/avatar`
    - `GET /api/employees/{id}/avatar`: `v` 외의 query는 422.
  - `AvatarStore`와 구현 `S3AvatarStore`, `LocalAvatarStores.Directory`/`Memory`, `AvatarStorageConfiguration`
  - `AccountCredentialRepository.avatarKey`
- 설정
  - `openstudy.avatar.*`(기본 `directory`, 테스트 `memory`)
  - multipart 6MB
  - `.env.example`, `.gitignore`(`/data/`)
- `build.gradle`: AWS SDK BOM·`s3`, 부트스트랩 클래스패스를 파일로 전달
- 계약
  - 세 operation을 `implemented`로 바꿨다.
  - `SessionUser`·`Employee`의 `x-planned-changes`를 `avatar` 본 정의로 옮겼다. 이로써 M14 operation이 모두 구현됐다.
  - 저장소 설정을 계약 문서에 반영했다.

## 5. 검증 결과

- 실행함
  - `./gradlew test --tests "com.openstudy.modules.account.*" --tests "com.openstudy.architecture.*"`: 통과
  - `AvatarUnitTests` 9건
    - 시그니처로만 형식 판별(이름만 바꾼 텍스트, GIF, WebP 아닌 RIFF, 짧은 파일 거부)
    - 키·경로(다른 직원 키·이전 URL·경로 이탈 거부, 사진이 바뀌면 경로가 바뀜)
    - 업로드 시 새 파일 저장·이전 파일 삭제, 저장 실패 시 새 파일 없음
    - 형식 오류·5MB+1바이트 거부, 정확히 5MB 허용, 승인 전용 세션 거부
    - 삭제
    - 열람: 본인·OWNER·같은 활성 지점 직원 허용. 다른 지점, 회수·대기 소속, 사진 없음, 이전 값, 파일 없음은 모두 404
    - 디렉터리 저장소
  - 기존 계정 단위 테스트(#119) 22건도 함께 통과
  - 클래스패스 파일 전달로 바꾼 뒤 테스트 JVM이 정상 실행되는 것을 확인했다.
- 실행하지 않음
  - **`OwnerBootstrapTests`**(`slowTest`): 이번에 클래스패스 전달 방식을 바꾼 테스트인데 실행하지 않았다. 자식 프로세스를 `@argfile`로 띄우는 부분은 검증되지 않았다.
  - 실제 S3 업로드·조회·삭제
  - HTTP multipart 경로(파트 검사, 6MB 초과 처리, `Cache-Control` 헤더가 공통 `no-store`를 덮는지)
  - 기존 통합 테스트의 `avatar` 응답 변화: `ProfileIntegrationTests`는 DB 값만 비교하는 것으로 읽었다.
  - 전체 `./gradlew test`
  - 사용자 지시(2026-11-10)에 따라 통합·전체 테스트는 마일스톤 검증 때 실행한다.
- `graphify update .`는 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- **인프라 요청**: S3 버킷·IAM을 인프라 담당에게 요청하고, 운영 확인 결과를 `docs/deployment/avatar-bucket-request.md` "확인 결과"에 남긴다. #120 인수 조건이다.
- **마일스톤 검증 때 확인할 것**
  - `slowTest`(특히 `OwnerBootstrapTests`)
  - 사진 HTTP 통합 테스트: 형식 위조, 크기 경계, 권한, 교체 후 이전 파일 조회
- **운영 설정**: 운영에서는 `OPENSTUDY_AVATAR_STORAGE=s3`를 반드시 지정한다. 기본값 `directory`는 컨테이너 재시작 때 사라진다.
- **Frontend #43**: `avatar`가 경로로 바뀌었으니, 사진 표시는 이 경로를 그대로 `img src`로 쓴다(같은 출처, 세션 쿠키).
