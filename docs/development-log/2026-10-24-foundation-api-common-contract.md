# 2026-10-24 기반 API 공통 계약 구현 (B01 / #7)

## 배경 / 문제

목표 OpenAPI에는 공통 오류·요청 ID·페이지·version 규칙이 있었지만 기존 실행 서버는 짧은 오류 응답과 평면 목록 메타를 사용했다. 예외 메시지가 클라이언트에 노출될 가능성도 있었다. 후속 업무 API와 프론트 계약 작업이 같은 공통 처리를 사용하도록 기반을 구현했다.

## 검토한 선택지 / 결정 및 이유

- 서버가 발급한 UUID를 최우선 요청 필터에서 설정했다. 외부의 임의 요청 ID를 그대로 신뢰하지 않으며 Security·CSRF 오류도 같은 ID와 no-store를 받는다.
- 안전한 명시적 업무 오류와 예기치 않은 오류를 구분했다. SQL·비밀번호·토큰이 원인 메시지에 포함될 수 있어 응답과 공통 오류 로그에 예외 메시지·원문 요청·stack trace를 기록하지 않는다. requestId와 예외 타입으로 연결한다.
- 목록 정렬은 endpoint가 제공한 허용 집합으로 제한한다. ID 동률 정렬은 OpenAPI의 direction 설명을 그대로 따르고, api.md의 기본 desc 설명을 명료화했다. 정책이나 OpenAPI JSON 변경은 없다.
- 로컬 시연 API는 기존 UI가 사용하는 status/task due 정렬을 명시적으로 유지한다. 실제 운영 상품·초대 DTO와 권한 검사를 시연 데이터로 대체하지 않는다.

## 구현 내용

- X-Request-ID, API no-store, MDC 복구, Security/필터/MVC 공통 오류 응답.
- 422 fieldErrors, 409 latestVersion/reloadUrl, 401/403/404 구분 및 안전한 500. JSON 오타·허용하지 않은 필드와 잘못된 입력 형식을 거부하며 method validation과 지원하지 않는 media type도 구분한다.
- ApiVersion은 DB 0 기반과 API 1 기반을 양방향 변환하고 JavaScript 안전 정수 범위를 검사한다. 실제 수정 서비스는 인가 후 requireCurrent를 호출해야 한다.
- ListQuery는 page/pageSize/q/sort/direction을 검증하고 PageResponse는 이미 인가·필터된 집합의 total과 안정 정렬·빈 페이지를 반환한다. 로컬 목록 메타를 {items,meta}로 맞췄다.
- 로그인 이메일만 trim하고 비밀번호 원문은 보존한다. 신규 가입 비밀번호 정책을 기존 로그인에 강제하지 않으며 BCrypt 72바이트 초과는 422로 거부한다.
- 개발 계획 정본에 등록된 이슈 번호와 B02 완료, 후속 업무 검증 경계를 연결했다.

## 검증 결과

- Java 21, Docker MySQL Testcontainers 환경에서 clean test bootJar 성공: 전체 63개 테스트, 실패/오류/건너뜀 0. 기존 DB migration·제약·실제 HTTP 로그인/세션 테스트 포함.
- 공통 HTTP 테스트 7개: 버전 충돌·역변환/범위, 요청 ID 연결, 422, 안전한 예외 응답과 로그, 필터 실패, 동률 정렬, 범위 밖/빈 페이지.
- 인증 HTTP 테스트 13개: 기존 인증 회귀 및 미허용 JSON 필드·깨진 JSON·로그인 입력·415 응답.
- 최종 정렬 허용 목록 수정 후 `test --tests '*LocalPreviewIntegrationTests' bootJar --no-daemon` 성공: 실제 MySQL+HTTP 시연 통합 테스트 4개, 실패/오류/건너뜀 0. 기존 status/task due 정렬 정상·private 정렬 거부를 확인했다.
- `git diff --check` 통과. 테스트와 JAR은 별도 Testcontainers DB만 사용했으며 개발자가 사용하는 DB는 변경하지 않았다.

## 남은 작업 / 보류 사항

- 실제 지점·직원·상품 CRUD와 현재 권한에 따른 DB total, 트랜잭션 내 version 경합은 B03/B05/B06/B10 및 V01에서 구현·검증한다. 이번 목록 헬퍼와 로컬 샘플 테스트가 해당 운영 검증을 완료한 것은 아니다.
- 기존 로그인 응답 전체를 목표 sessionMode·membership version 계약으로 전환하는 작업은 B04 이후에 남아 있다.
- OpenAPI는 목표 계약이다. 이번 구현만으로 명세의 업무 endpoint가 모두 제공되지는 않는다.