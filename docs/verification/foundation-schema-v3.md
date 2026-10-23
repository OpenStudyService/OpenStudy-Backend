# 기반 스키마 V3·전달 패키지 검증

검증일: 2026-10-24 (Asia/Seoul). 기반 계약 `0.1.2-design`, SRS v0.6 61개 요구사항·8개 중분류, 사용자 확정 Q1~Q12. 실제 개발/운영 DB를 사용하지 않았다.

## 실행 환경과 명령

- Windows / Docker Desktop Engine 27.2.0 / JDK 21 / Gradle Wrapper 9.8.1.
- MySQL 8.4.11 / InnoDB / utf8mb4_0900_ai_ci 기본값 / 연결 시간대 UTC.
- 이미지: `mysql@sha256:24989179f58856db763f418010c1169fecc944e3ec1ea7e921572d83eb070f70`.
- 모든 DB는 새 Testcontainers 전용 DB이며 임의 생성 테스트 비밀번호를 사용한다. 기존 테스트도 Docker 서비스 연결을 사용한다.

```powershell
.\gradlew.bat clean test bootJar '-PfoundationExportDir=docs/releases/foundation-0.1.2' --no-daemon
python scripts/validate_foundation_design.py
npx --offline --yes --package @apidevtools/swagger-cli swagger-cli validate docs/design/foundation/openapi.json
```

전체 결과: **53 tests, failures 0, errors 0, skipped 0. test·bootJar BUILD SUCCESSFUL.** 이 중 신규 `FoundationMigrationTests` 5개가 upgrade/negative/constraint/export 검증을 담당한다. 기존 로그인·JDBC 세션·실제 HTTP·localpreview·V1 제약의 48개도 회귀 통과했다.

## DB 검증 결과

| 검증 | 결과와 근거 |
| --- | --- |
| 빈 DB | V1 → V2 → V3 성공, 업무·세션 65개 및 Flyway 이력 1개. V1 파일은 원본과 동일. |
| 기존 V1 업그레이드 | 고정 ID의 직원·지점·상품·초대·고객·환불 결제 fixture 생성 → target=2 → 명시 매핑 → V3 성공. 상품·직원·초대 version, 상품 생성/수정 시각, 비밀번호 해시, 기존 결제 금액·상품명 snapshot 유지. |
| 최초 상품 이력 | 기존 상품 version에 BASELINE 생성. 실제 판매 지점 ID, terms JSON 객체·유형·분류 보존. 알 수 없는 과거 이력으로 표시하지 않음. |
| 매핑 없음 | FOUNDATION_MAPPING_REQUIRED로 V3 사전 검사 실패. 상품 지점·revision 임의 생성 없음, 기존 version 유지. 매핑 후 테스트 DB의 실패 기록만 repair해 재실행 성공. |
| 정규화 충돌·seed 충돌 | trim/lower 이메일 충돌과 다른 내용의 동명 기본 프리셋을 각각 거부. |
| 상품 직접 SQL | 0원, TIME 제공량 NULL, PERIOD valid_days 사용을 CHECK로 거부. |
| 참조·삭제 이력 | 환불 결제가 참조하는 상품 DELETE 거부. 거래 없는 상품 삭제 시 product_revisions.product_id=NULL, original_product_id와 이력 2행 유지. |
| OWNER·초대 | 두 번째 OWNER, 같은 이메일·지점 진행 초대 중복 거부. 반려 필수값 없는 상태 변경 CHECK 거부. |
| 기본 권한 | 코드 9개, 기본 STAFF grants 0개, MANAGER grants 9개. 기존 직원 권한에 자동 소급하지 않음. |
| 멱등 키 | 같은 actor/operation/scope에서 Key/key는 별개, 정확히 같은 키 중복은 거부. |
| 전체 SQL 복원 | pristine V3의 SHOW CREATE TABLE 및 기본 seed·실제 Flyway 이력만 추출. 새 빈 스키마에 실행해 컬럼 metadata 전체 동일, 기본 권한 9개, 직원 0명 확인. Flyway validate 성공·migrate 추가 실행 0건. |

개발 과정에서 MySQL FK가 있는 컬럼의 NOT NULL 변경과 동일 이름 FK 재추가 제한을 발견해 V3에서 다른 제약 이름으로 원자적 ALTER를 적용했다. CHECK 위반은 MySQL error 3819로 실제 거부되며 Spring의 예외 분류와 구별해 검사했다. 최초 실패 실행을 성공한 결과로 집계하지 않고 수정 후 위 전체 clean 검증 결과를 최종 기준으로 삼았다.

## 계약·산출물 검증

- OpenAPI 3.0.3 문법 검증 성공. 31경로·40 operations·57 schemas, local reference 544개 해석 성공.
- 요구사항 61개·8분류·정책 12개와 추적표/이슈 16개 연결 검증. 원본 SRS SHA-256 유지.
- 익명 CSRF/로그인/초대 미리보기와 제한 세션 조회의 x-permission 설명을 실제 계약에 맞게 명시했다. 엑셀도 같은 JSON에서 재생성했다.
- API 엑셀: 8시트, API 40행·요청 필드 239행·상태별 응답 320행·모델 사전 284행·업무 규칙 40행·확정 정책 12행. 필수 여부와 NULL 허용 분리, oneOf 변형 표시. 각 시트 렌더링 확인, 저장 XML/값 대조·오류 값 검사 수행.
- ERD: 실제 INFORMATION_SCHEMA의 66테이블/634컬럼/163 FK 컬럼에서 생성. 기반 21테이블 PDF 7쪽과 전체 업무·세션 65테이블 Mermaid 제공. PDF 전 페이지 렌더링 확인, 선·라벨의 겹침/페이지 경계를 수정했다.
- manifest/SHA256SUMS는 실제 파일의 SHA-256으로 생성하며 원본 OpenAPI·migration 사본 일치를 검사한다.

## 후속 구현·운영 검증

이번 검증이 새 업무 API 구현·인가 경합의 완료를 뜻하지 않는다. 최소 관리자 동시 회수, 승인/반려·이중 수락, 메일 worker revision/lease·SMTP, 상품 변경/구매 경합, 업무 트랜잭션의 감사/revision/outbox 원자성, 새 제한 세션 60분/12시간 정책, 권한 회수 후 SSE, 다중 ECS fan-out은 B03~B11/V01에서 구현·검증한다. 기존 로그인 회귀와 새 인증 보완 검증을 구분한다.

운영 DB의 실제 레거시 매핑·백업 복원, 운영 계정 migration 권한, Debian·프록시·TLS 쿠키·메일 수신 검증도 별도다. 프론트 코드 변경과 live 신규 업무 연동 테스트는 이번에 수행하지 않았다. GitHub 마일스톤·이슈 등록은 하지 않았다.
