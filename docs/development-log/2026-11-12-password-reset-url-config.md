# 2026-11-12 비밀번호 재설정 URL 설정 예시 정리

## 1. 배경

초대 메일 발송을 켠 백엔드는 시작할 때 비밀번호 재설정 URL을 검사한다. 이 값이 비어 있거나 허용 형식에 맞지 않으면 `A trusted HTTPS password reset URL ending with /password-reset is required` 오류로 기동이 중단된다. 기존 환경 변수 예시에는 운영용 HTTPS 주소만 있어 로컬 프론트엔드 주소에 HTTP를 쓸 수 있는 조건을 확인하기 어려웠다.

## 2. 결정 및 구현

- `.env.example`의 기존 설정 위치에 운영용 HTTPS와 로컬 루프백 HTTP 예시를 함께 둔다. 운영 URL에는 `/password-reset` 경로가 필요하며, 로컬 HTTP에는 기존 루프백 예외가 적용된다.
- 재설정 메일 어댑터에서 URL·입력 검증과 메시지 생성을 읽기 쉽게 정리했다. 기존 URL 검증 규칙, 메일 본문, 토큰의 URL 프래그먼트 전달 방식을 유지했다.
- SMTP 빈과 스케줄러 선언을 정리했다. 초대 메일 설정에 연동되는 활성화 조건과 SMTP 전송 검증을 유지했다.

## 3. 검증

- 변경 파일만 반영한 별도 작업 트리에서 `git diff --check`를 통과했다.
- 같은 작업 트리에서 Java 소스와 테스트 코드 컴파일을 통과했다.
- 설정 예시와 코드 가독성 변경 범위에 맞춰 전체 테스트는 실행 도중 중단했다. 실제 SMTP 발송은 검증하지 않았다.

## 4. 관련 기록

- [비밀번호 복구 이슈 #119](https://github.com/OpenStudyService/OpenStudy-Backend/issues/119)
- [메일 비활성 시 초기화 거부 PR #215](https://github.com/OpenStudyService/OpenStudy-Backend/pull/215)
