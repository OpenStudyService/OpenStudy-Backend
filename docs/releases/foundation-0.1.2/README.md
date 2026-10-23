# OpenStudy 기반 개발 전달 패키지 0.1.2

작성일: 2026-10-24. **DB는 실행·검증 완료, 업무 API는 목표 계약**이다. SRS 검토 완료 8개 중분류·61개 요구사항과 Q1~Q12 확정 정책을 기준으로 한다. 채널·근무표·구매·결제·발급 등 후속 업무의 설계 완료를 뜻하지 않는다. GitHub 마일스톤·이슈는 아직 등록하지 않았다.

## 팀원에게 전달할 파일

| 파일 | 용도 |
| --- | --- |
| [schema-full.sql](schema-full.sql) | **빈 DB 전체 구축**. 업무·세션 65개 + 실제 Flyway 이력 1개, 기본 권한·프리셋·가드 포함. 실제 계정·비밀번호·상품·구매 데이터 없음. |
| [migrations/](migrations/README.md) | 원본 Flyway V1/V2/V3. **기존 V1 DB는 전체 SQL 대신 증분 migration**을 사용. |
| [OpenStudy-Foundation-ERD.pdf](OpenStudy-Foundation-ERD.pdf) | 기반 21개 테이블의 컬럼·주요 관계, 전체 65개 색인. A3 가로 7쪽. |
| [erd-foundation.mmd](erd-foundation.mmd) / [erd-all-tables.mmd](erd-all-tables.mmd) | 검증한 DB에서 생성한 편집용 Mermaid ERD. 전체 원본은 기존 V1의 후속 영역도 포함. |
| [OpenStudy-Foundation-API.xlsx](OpenStudy-Foundation-API.xlsx) | 사람이 읽는 API 목록·요청·응답·모델·오류/보안·업무 규칙·정책, 8개 시트. |
| [openapi.json](openapi.json) | API 계약 0.1.2-design, 31경로·40 operation·57모델. 엑셀의 생성 원본 사본. |
| [schema-metadata.json](schema-metadata.json) | 검증된 MySQL 8.4.11의 컬럼·FK 추출값. |
| [verification.md](verification.md) | 실행한 검증과 후속 검증 구분. |
| [manifest.json](manifest.json) / [SHA256SUMS.txt](SHA256SUMS.txt) | 구성 파일별 SHA-256과 계약·migration 버전. |

전체 SQL에는 실제 V1-V3 Flyway checksum/이력이 들어 있다. 빈 DB에 적용한 뒤 동일 버전 앱의 Flyway validate와 재실행 0건을 검증했다. `CREATE DATABASE`, 계정·GRANT, DROP DATABASE/TABLE, FK 검사 해제는 포함하지 않는다. 기존 DB에 덧붙이거나 full SQL과 migration을 중복 수동 실행하지 않는다. [적용·기존 데이터 매핑 절차](../../db/foundation-upgrade.md)를 먼저 확인한다.

## 현재 상태

- V1 checksum 유지. V2 확장 → 기존 상품/초대 명시 매핑 → V3 사전 검사·제약·권한 seed·현재 상품 BASELINE 생성.
- DB upgrade/restore와 기존 로그인 포함 **53개 테스트 성공, 실패·생략 0**, 실행 JAR 빌드 성공.
- OpenAPI 문법, 61개 요구사항·12개 정책·544개 참조·16개 이슈 연결 검증 성공.
- API 엑셀은 전체 목표 계약이다. 이번 작업이 지점/직원/초대/상품 운영 API를 구현한 것은 아니다.
- 실제 로컬 개발 DB·운영 DB를 읽거나 변경하지 않았다. 전용 Testcontainers DB만 사용했다.

## 다음 개발 작업

[등록 초안](../../design/foundation/github-drafts/README.md)과 [운영 방식](../../design/foundation/agent-workflow.md)을 검토하고, 이 패키지 PR이 통합된 기준 커밋을 고정한다. B02는 이번 브랜치에서 완료했으므로 재개발하지 않는다. B01 공통 런타임 규칙과 F01 계약 타입·오류·캐시 처리가 첫 작업이며 이후 인가·조직 → 초대 → 상품 순서로 진행한다.

## 재생성

1. JDK 21·Docker에서 `./gradlew test --tests '*FoundationMigrationTests' -PfoundationExportDir=docs/releases/foundation-0.1.2` 실행. Windows PowerShell은 `-P...` 전체 인수를 작은따옴표로 감싼다. 이 작업은 컨테이너의 pristine DB로 전체 SQL·metadata를 내보내고 복원을 검증한다.
2. `python scripts/export_foundation_erd.py docs/releases/foundation-0.1.2 [한글 TTF 경로]` 실행. reportlab 필요, 생략 시 Windows 맑은 고딕 사용.
3. `@oai/artifact-tool`이 설치된 작업 폴더에서 `node <저장소>/scripts/export_foundation_api.mjs <저장소>/docs/design/foundation/openapi.json <출력 폴더>` 실행. 이 환경에서는 번들 Node/runtime을 사용했다. 결과 xlsx를 이 폴더에 복사한다.
4. `python scripts/prepare_foundation_issue_drafts.py`는 이슈 본문만 생성한다. 외부 등록·배정은 하지 않는다.
5. `python scripts/package_foundation_release.py <엑셀 출력 폴더>`로 원본 사본·해시를 동기화하고 전달 ZIP을 만든다. manifest에는 자기 자신과 SHA256SUMS의 해시는 중복 포함하지 않는다. `files`와 SHA256SUMS는 byte 고정한 release 파일, `zipSourceFiles`는 ZIP에 넣은 나머지 원본의 해시다. 파일 경로는 저장소/ZIP 루트를 기준으로 한다. Git checkout의 줄바꿈 변환은 원본 파일의 byte 해시와 구분한다.

OpenAPI·migration 변경 시 해당 검증과 영향을 받는 엑셀/ERD를 다시 생성하고 패키지 버전·개발 기록을 갱신한다. PDF와 엑셀의 실제 표시 상태는 렌더링으로 별도 확인한다.
