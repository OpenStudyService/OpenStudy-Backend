# 7. 직접 띄워 보기: DB 재현 방법

포트폴리오를 보는 사람이 OpenStudy의 DB를 직접 만들어 확인하는 방법이다. 세 가지 경로가 있고, 목적에 따라 고르면 된다.

| 경로 | 필요한 것 | 결과 | 걸리는 시간 |
|---|---|---|---|
| A. 스키마만 보기 | MySQL 8.4 하나 | 빈 스키마 + 기준 시드 | 1분 |
| B. 앱으로 띄우기 | JDK 21, MySQL 8.4 | Flyway로 만든 스키마 + 로컬 샘플 데이터 | 5분 |
| C. 추출·검증 다시 하기 | Docker, Python 3 | 이 문서의 ERD·구축 SQL 재생성과 복원 검증 | 2분 |

## A. 전체 구축 SQL로 스키마만 만들기

[`docs/db/schema-full.sql`](../db/schema-full.sql)은 V1~V22를 적용한 DB에서 추출한 SQL이다. 테이블 81개, 뷰 1개, 권한·보존 규칙 기준 시드, 실제 Flyway 이력을 담고 있다. 고객·직원 같은 업무 데이터와 계정 정보는 없다.

Docker로 빈 MySQL을 띄운다. 비밀번호는 예시이니 바꿔서 쓴다.

```bash
docker run -d --name openstudy-db -e MYSQL_ROOT_PASSWORD=change-me -e MYSQL_DATABASE=openstudy -p 3306:3306 mysql:8.4.11 --character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci --default-time-zone=+00:00
```

DB가 준비되면(10~20초) 구축 SQL을 적용한다.

```bash
docker exec -i -e MYSQL_PWD=change-me openstudy-db mysql -uroot --default-character-set=utf8mb4 openstudy < docs/db/schema-full.sql
```

확인 쿼리 예시:

```sql
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'openstudy' AND TABLE_TYPE = 'BASE TABLE';  -- 81
SELECT MAX(CAST(version AS UNSIGNED)) FROM openstudy.flyway_schema_history;                                    -- 22
SELECT code, period_days, legal_minimum_days FROM openstudy.retention_rules;                                   -- 보존 규칙 5건
```

주의:

- **빈 DB에만** 적용한다. 기존 DB 위에 덧붙이지 않는다.
- Flyway 이력이 들어 있으므로, 이 DB에 앱을 연결해도 Flyway가 V1~V22를 다시 적용하지 않는다.

## B. 앱으로 띄우기 (Flyway 적용 + 샘플 데이터)

정식 설치 경로다. 앱이 Flyway로 V1부터 V22까지 적용한다.

1. 빈 스키마를 준비한다(A의 `docker run`만 실행).
2. `.env.example`을 `.env`로 복사하고 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 맞춘다. `.env`는 Git에 올리지 않는다.
3. 서버를 실행한다.

```bash
./gradlew bootRun
```

- 첫 관리자 계정은 `local` 프로필에서 `.env`의 `BOOTSTRAP_OWNER_EMAIL`, `BOOTSTRAP_OWNER_PASSWORD`로 만든다.
- `local` 프로필은 화면 확인용 [로컬 워크스페이스 샘플](../local-preview.md)을 제공한다.
- 자세한 변수와 운영 초기화 명령은 [프로젝트 README](../../README.md#실행하기)를 따른다.

## C. ERD와 구축 SQL을 다시 추출하고 검증하기

이 문서들의 원본 데이터를 직접 다시 만드는 경로다.

```bash
python scripts/export_schema_snapshot.py
```

스크립트가 하는 일:

1. 일회용 `mysql:8.4.11` 컨테이너를 띄우고 `flyway/flyway:11.20.2`로 마이그레이션 폴더 전체를 적용한다.
2. `docs/db/schema-full.sql`과 `docs/db/erd.mmd`를 새로 쓴다.
3. 새 SQL을 두 번째 빈 스키마에 복원하고, 원래 스키마와 컬럼·인덱스·FK·CHECK·뷰 정의·테이블별 행 수를 비교한다. 하나라도 다르면 실패한다.
4. 컨테이너와 네트워크를 지운다. 루트 비밀번호는 실행할 때마다 무작위로 만들고 저장하지 않는다.

이어서 영역별 ERD를 다시 만든다.

```bash
python scripts/export_domain_erd.py
```

2026-11-18 실행 결과: 테이블 81, 뷰 1, 컬럼 808, FK 176, CHECK 87, 인덱스 334, 복원 후 Flyway 이력 22건(최신 V22), 시드 테이블 6개 행 수 일치.

## 테스트로 확인하기

DB 관련 테스트는 Testcontainers로 MySQL을 띄우므로 Docker만 있으면 된다.

```bash
./gradlew slowTest
```

- 빈 DB에 전체 마이그레이션을 적용하고 최신 버전·테이블 수를 확인한다.
- 마이그레이션별 데이터 변환과 실패 조건을 확인한다.
- OWNER 최초 생성의 동시 실행을 확인한다.

매출 집계 실행 계획 측정은 [4. 인덱스 튜닝](04-index-tuning.md#재현과-원문)에 명령이 있다.

## 시각 자료로 바꾸기

모든 ERD는 Mermaid 형식이다. GitHub에서 Markdown을 열면 그림으로 보인다. 발표 자료용 이미지가 필요하면 [`erd/`](erd/) 폴더의 `.mmd` 파일을 [Mermaid Live Editor](https://mermaid.live)에 붙여 PNG·SVG로 내보낸다.
