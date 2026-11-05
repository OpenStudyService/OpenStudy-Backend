# BE-029 서버 실행 이미지와 운영 프록시·DB 설정 계약

- 이슈: [#29 서버 실행 이미지와 운영 프록시·DB 설정 계약 준비](https://github.com/OpenStudyService/OpenStudy-Backend/issues/29) · M1 · `type:feature`, `layer:adapter`
- 상태: **착수 가능** (Infra#2와 계약 조율 필요)
- 선행: #8·#10 구현 (완료), [Infra#1](https://github.com/OpenStudyService/OpenStudy-Infra/issues/1) (완료), [Infra#2](https://github.com/OpenStudyService/OpenStudy-Infra/issues/2) (열림, 상호 의존)
- 후속: #10·#17·#28 잔여 검증, #101, #102
- 인수 조건 진행: 0 / 8 (2026-11-06 조회)

## 목표

실제 HTTPS 경로에서 M1 서버를 검증할 수 있도록, 재현 가능한 실행 이미지와 운영 환경 설정(DB 접속, 세션 쿠키, health, Flyway 실행 주체) 계약을 준비하고 Infra에 전달한다. 레포에 `Dockerfile`·`deploy/`가 이미 있으므로 현재 상태를 먼저 확인한다.

## 작업 단계

1. 기존 `Dockerfile`·`deploy/`와 Infra ECS 계약(이미지·포트·health·환경 변수)을 비교해 차이를 정리한다.
2. 실행 이미지: Java 21, linux/amd64, 문서화된 빌드·기동 명령, 이미지에 `.env`·운영 자격증명·고정 OWNER 비밀번호·시연 계정이 없는지 확인한다.
3. 환경 설정: `DB_URL`·`DB_USERNAME`·`DB_PASSWORD` 등 주입 방법과 누락 시 기동 실패 동작을 정한다.
4. OWNER 초기화를 일반 기동과 분리된 실행 절차로 문서화한다(기존 자격증명 보존).
5. health·정상 종료를 ECS 계약과 맞춘다.
6. 운영 프로필의 세션 쿠키(Secure·HttpOnly·SameSite·Path), 신뢰 프록시 전달 헤더, SSE 프록시 계약(#17 인계 항목)을 설정·문서화한다.
7. Flyway 실행 주체를 하나로 정하고 Infra 기본 비활성 설정과 충돌하지 않게 한다.
8. RDS 최소 권한 계정·TLS 요구와 이미지 식별자·소스 커밋을 Infra#2에 전달한다.

## 제외 범위

- AWS 리소스 재구축, M11 CI/CD·관측 체계 완성(#102)

## 검증

- `./gradlew test bootJar`
- 문서 명령으로 컨테이너 빌드 → 일회용 MySQL 연결 → health·초기화·로그인·종료·환경 누락 확인
- 로컬 컨테이너 성공과 AWS HTTPS 성공을 따로 기록한다.

## 참고

- [deploy 문서](../deployment/)
- [조직 통합 검증 기록](../development-log/2026-10-25-organization-integration-verification.md)
