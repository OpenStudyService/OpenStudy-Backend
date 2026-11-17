# 2026-11-18 발표·포트폴리오용 DB 산출물 정리와 V22 기준 ERD·구축 SQL 갱신

## 1. 배경 / 문제

- 프로젝트 마무리를 앞두고 사용자가 발표·포트폴리오용 DB 산출물 정리를 요청했다. 산출물은 `docs/final/` 아래에 두기로 했다.
- 기존 원자료(ADR 17건, 전체 ERD, 전체 구축 SQL, 실행 계획 측정, 개발 기록)는 많았지만 발표용으로 묶인 문서가 없었다.
- 원자료 중 `docs/db/erd.mmd`와 `docs/db/schema-full.sql`이 최신 스키마(V22)와 맞지 않았다.
  - `schema-full.sql`은 V21 기준이라 `ai_plan_cache`(V22)가 없었다. V19의 `retention_rules` 기준 시드 5건도 빠져 있었다.
  - `erd.mmd`는 더 오래된 상태였다. V18에서 지운 `ai_export_approvals`가 남아 있고, `password_reset_tokens`·`retention_rules`·`retention_runs`·`ai_plan_cache` 테이블과 일부 컬럼(`ai_executions`의 V17 컬럼, `customers.anonymized_at`)이 없었다.
  - 두 파일을 만든 추출 스크립트는 저장소에 없었다.

## 2. 검토한 선택지

- **ERD·구축 SQL 갱신 방법**
  - 기존 파일에 V22 변경만 손으로 추가: 빠르지만 ERD의 다른 누락을 고칠 수 없고 다음에도 같은 문제가 생긴다.
  - 추출 스크립트를 새로 만들어 저장소에 두기 **(채택)**: Flyway로 만든 일회용 DB에서 다시 추출하고, 복원 검증까지 자동으로 한다.
- **영역별 ERD 작성 방법**
  - 손으로 영역별 Mermaid 작성: 스키마가 바뀌면 어긋난다.
  - 전체 ERD에서 영역별로 잘라 생성 **(채택)**: 새 테이블이 영역에 배정되지 않으면 스크립트가 실패해 누락을 알린다.

## 3. 결정 및 이유

- `scripts/export_schema_snapshot.py`를 추가했다.
  - Docker로 `mysql:8.4.11`을 띄우고 `flyway/flyway:11.20.2`로 마이그레이션 폴더를 적용한다. 앱 테스트와 같은 MySQL 이미지, 배포 구성과 같은 Flyway 이미지다.
  - 출력 형식(테이블 순서, ERD 표기)은 기존 파일에 맞췄다. 그래서 diff에는 실제 스키마 변경만 남는다.
  - 루트 비밀번호는 실행마다 무작위로 만들고 저장하지 않는다.
- `scripts/export_domain_erd.py`를 추가했다. 업무 영역 8개로 나누고, 영역 밖에서는 직원을 가리키는 작성자·승인자 FK를 생략한다. 거의 모든 테이블에 있어 업무 관계를 가리기 때문이다.
- `docs/final/`은 백엔드 저장소 안에 두었다. DB 원자료(`docs/db`, 마이그레이션, 개발 기록)가 모두 이 저장소에 있어 상대 링크로 연결할 수 있기 때문이다.
- 핵심 설계 결정은 ADR 17건 중 6건(구매 조건 고정, 생성 컬럼 + UNIQUE, 시간 슬롯 이중 예약 방지, AI 결과 미저장, 파생값 미저장, 삭제 대신 상태·이력)을 골랐다. 멱등성·관리자 가드·잠금 순서는 정합성 문서로 분리했다.

## 4. 구현 내용

- `docs/db/schema-full.sql`, `docs/db/erd.mmd`: V22 기준으로 다시 추출했다.
  - 구축 SQL: `ai_plan_cache` 추가, `retention_rules` 시드 5건 추가, 머리말을 V1~V22로 수정.
  - ERD: 위 1절의 테이블·컬럼·관계 누락 정정.
- `docs/db/README.md`: 기준 버전(V21 → V22)과 재생성 방법, `docs/final` 링크를 추가했다.
- `docs/final/`
  - `README.md`: 목차, 숫자 요약, 10분 발표 흐름, 예상 질문표, 알려진 한계
  - `01-erd-overview.md` + `erd/*.mmd`: 영역 지도와 영역별 ERD 8개
  - `02-schema-evolution.md`: V1~V22 타임라인과 마이그레이션 운영 규칙
  - `03-key-design-decisions.md`: 핵심 설계 결정 6건
  - `04-index-tuning.md`: V10 인덱스 튜닝 사례 요약
  - `05-integrity-and-concurrency.md`: CHECK·복합 FK·멱등성·잠금·동시성 테스트
  - `06-technology-choices.md`: MySQL·Flyway·JPA 전환·Testcontainers·세션
  - `07-reproduction.md`: 구축 SQL·앱·추출 스크립트로 재현하는 방법

## 5. 검증 결과

- `python scripts/export_schema_snapshot.py`: 성공. 테이블 81, 뷰 1, 컬럼 808, FK 176, CHECK 87, 인덱스 334.
  - 새 구축 SQL을 두 번째 빈 스키마에 복원해 컬럼·인덱스·FK·CHECK·뷰 정의·테이블별 행 수가 Flyway 적용 스키마와 같음을 확인했다. 복원 후 Flyway 이력 22건, 최신 V22.
  - 기존 구축 SQL과 비교해 `CREATE` 문은 `ai_plan_cache` 추가 외에 달라진 것이 없음을 스크립트로 확인했다.
- 첫 실행에서 `DEFAULT CURRENT_TIMESTAMP` 컬럼(MySQL `EXTRA`에 `DEFAULT_GENERATED`)을 생성 컬럼으로 오인해 시드 INSERT에서 빠지는 버그가 있었다. 생성 컬럼 판별을 `VIRTUAL/STORED GENERATED`로 고치고, 이 문제를 잡을 수 있도록 복원 검증에 테이블별 행 수 비교를 추가했다.
- `07-reproduction.md`의 경로 A를 실제로 실행했다. 빈 `mysql:8.4.11`에 구축 SQL 적용 성공(약 5초), 테이블 81·최신 버전 22·보존 규칙 5건.
- `docs/final`의 상대 링크와 앵커를 스크립트로 검사했다. 깨진 링크 0건.
- 문서 안 Mermaid 그림 10개를 Mermaid 11.4.1로 브라우저에서 렌더링했다. 모두 오류 없음.
- 코드·마이그레이션 변경은 없어 Gradle 테스트는 실행하지 않았다. 문서에 인용한 테스트 결과(2026-11-17 전체 테스트)는 해당 날짜 기록을 옮긴 것이다.

## 6. 남은 작업 / 보류 사항

- 발표 슬라이드 자체는 만들지 않았다. `docs/final/README.md`의 발표 흐름을 바탕으로 별도로 만든다.
- 발표용 PNG·SVG 이미지는 저장소에 넣지 않았다. 필요하면 `docs/final/erd/*.mmd`를 Mermaid 도구로 내보낸다.
- `docs/design/foundation/OpenStudy-Foundation-ERD.pdf`와 `docs/db/v0.5/` 자료는 과거 기준이다. 이번에 갱신하지 않았다.
- 스키마가 바뀌면 두 스크립트를 다시 실행해야 한다. 자동 실행(CI)은 연결하지 않았다.
