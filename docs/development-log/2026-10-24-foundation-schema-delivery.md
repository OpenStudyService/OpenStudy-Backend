# 기반 스키마 구현과 개발 전달 패키지

## 1. 배경 / 문제

사용자가 8개 중분류의 정책 선택을 마쳤고, DB 팀원에게 ERD·전체 구축 DDL을 전달하고 API 계약을 엑셀로 읽을 수 있게 준비한 뒤 GitHub 마일스톤·이슈 등록 직전까지 진행하도록 요청했다. 기존 문서는 목표 설계였으며 실행 DDL은 V1만 존재했다.

## 2. 검토한 선택지

- V1 변경 또는 증분 migration: 이미 적용된 checksum을 유지하기 위해 V2/V3로 확장했다.
- 단일 자동 이관 또는 확장/검토/강화 분리: 기존 상품 판매 지점·초대 권한/이름을 추측할 수 없어 target=2에서 명시 검토 후 V3를 적용하는 방식을 선택했다.
- 전체 SQL을 수작업으로 중복 작성하거나 검증된 DB에서 추출: SHOW CREATE TABLE·실제 Flyway 이력·기본 reference seed를 추출하고 빈 스키마 복원까지 검증했다.
- API 엑셀을 별도 수기 정본으로 유지하거나 OpenAPI에서 생성: 계약 불일치를 줄이기 위해 OpenAPI를 정본으로 유지하고 엑셀 생성 스크립트를 만들었다.

## 3. 결정 및 이유

`chore/foundation-schema-delivery`를 main에서 생성해 기존 설계 문서와 이번 DDL/검증/전달 산출물을 하나의 검토 단위로 준비했다. 실제 개발 DB는 사용하지 않고 Docker 전용 MySQL로 검증했다. 기본 권한 seed는 Q5의 STAFF 빈 권한·MANAGER 9종을 반영하고 기존 직원 권한은 바꾸지 않았다. 업무 API 구현과 DB 준비 완료를 문서에서 구분했다.

## 4. 구현 내용

- V2: 추적 필드·초대 정보/예정 grants·worker lease·상품 분류/판매 지점/revision·관리자 가드·멱등성·이벤트 outbox 확장. 일시적 legacy 검토 표식.
- V3: 변경 전 매핑/충돌 검사, 이메일 collation·정규화, 필수값/CHECK/UNIQUE, 권한 seed, 실제 현재 조건의 BASELINE revision. 기존 거래 FK와 V1 유지.
- 전체 구축 SQL·metadata 추출/복원 JUnit, V1 업그레이드·미확인 매핑 거부·직접 SQL 제약·삭제 이력 테스트 추가.
- 실제 metadata 기반 ERD PDF/Mermaid, OpenAPI 기반 API 엑셀과 재생성 스크립트, 적용 안내·검증 보고서·manifest·전달 ZIP 준비.
- 전달 파일은 Git 줄바꿈 변환으로 해시가 바뀌지 않도록 release byte를 고정하고 OpenAPI 정본은 LF로 유지한다. 기존 Gradle wrapper/JAR 속성은 유지한다.
- 이슈 16개 등록 본문과 저장소별 마일스톤 3개씩의 정의·선행 관계·운영 지시 준비. B02는 전달 브랜치에서 완료 표시. GitHub 마일스톤·이슈 등록은 실행하지 않았다.
- 익명/제한 세션 API의 x-permission 설명 정정. 저장소 밖의 사용자 작업 규칙도 GitHub 작업 AI가 읽을 수 있도록 backend AGENTS.md에 브랜치·로그 원칙을 반영했다.

## 5. 검증 결과

- `clean test bootJar -PfoundationExportDir=...`: 테스트 53개, 실패/오류/생략 0, JAR 빌드 성공. 신규 migration 테스트 5개와 기존 회귀 48개.
- MySQL 8.4.11 pinned digest, UTC. 기존 V1 fixture의 ID/version/조건/자격증명/결제 snapshot 보존, 매핑 차단, 전체 SQL 복원·Flyway validate 및 추가 실행 0건 확인.
- OpenAPI validator 성공. 추적 검증 61요구사항/8분류/40operation/57schema/544refs/12정책/16이슈 성공.
- 엑셀 8시트와 ERD PDF 7쪽 렌더링 검토. 파일 구조·내용·해시 대조. 세부 실행 결과와 미검증 항목은 `docs/verification/foundation-schema-v3.md`에 기록했다.

## 6. 남은 작업 / 보류 사항

패키지 PR 검토·통합 후 실제 기준 커밋을 고정하고 GitHub 마일스톤·이슈를 등록한다. B01 공통 런타임 처리, B03 이후 인가/업무 서비스 및 프론트 연동은 미착수다. 운영 데이터 매핑·운영 프록시/쿠키/SMTP·다중 ECS SSE와 업무 경합 검증은 후속 이슈 범위다. WBS·일정·담당자는 임의 확정하지 않았다.
