# OpenStudy DB 최종 정리

발표와 포트폴리오를 위한 데이터베이스 산출물 모음이다. 기준은 2026-11-18, Flyway V1~V22, MySQL 8.4.11이다.

## 숫자로 보는 DB

| 테이블 | 컬럼 | 물리 FK | CHECK | 인덱스 | 마이그레이션 | 설계 결정 기록 |
|---|---|---|---|---|---|---|
| 81 | 808 | 176 | 87 | 334 | 22 | 17 |

## 문서

| # | 문서 | 내용 | 발표 활용 |
|---|---|---|---|
| 1 | [ERD: 전체 구조와 영역별 요약](01-erd-overview.md) | 영역 지도 1장 + 업무 영역 8개 ERD | 구조 소개 1~2장 |
| 2 | [스키마 변천: V1에서 V22까지](02-schema-evolution.md) | 버전별 변경과 배경, 마이그레이션 운영 규칙 | 타임라인 1장 |
| 3 | [핵심 설계 결정](03-key-design-decisions.md) | 문제 → 선택지 → 결정 6건 | 본론 3~5장 |
| 4 | [인덱스 튜닝 사례](04-index-tuning.md) | 매출 집계 전체 탐색 제거, 19.4 ms → 6.0 ms | 성과 1장 |
| 5 | [정합성과 동시성](05-integrity-and-concurrency.md) | CHECK·복합 FK·멱등성·잠금·동시성 테스트 | 본론 1~2장 |
| 6 | [DB 기술 선택과 근거](06-technology-choices.md) | MySQL, Flyway, JPA 전환, Testcontainers | 부록 또는 1장 |
| 7 | [직접 띄워 보기](07-reproduction.md) | 구축 SQL·앱·추출 스크립트로 재현 | 포트폴리오 링크 |

영역별 ERD 원본(Mermaid)은 [`erd/`](erd/)에 있다.

## 추천 발표 흐름 (10분 기준)

1. **도메인과 규모** (1분): 다지점 스터디카페 운영. 위 숫자 표와 [영역 지도](01-erd-overview.md#영역-지도).
2. **핵심 구조** (2분): 지점이 데이터 경계, 결제가 공통 원장. [상품·결제 ERD](01-erd-overview.md#4-상품결제이용권정산).
3. **설계 결정** (3분): [D1 구매 당시 조건 고정](03-key-design-decisions.md#d1-거래는-구매-당시-조건을-고정한다), [D2 생성 컬럼 + UNIQUE](03-key-design-decisions.md#d2-활성-상태는-하나만을-생성-컬럼--unique로-강제한다), [D4 AI 결과 미저장](03-key-design-decisions.md#d4-ai-분석-결과를-저장하지-않는다).
4. **동시성** (2분): [관리자 0명 방지](05-integrity-and-concurrency.md#5-관리자-0명-방지-단일-행-가드)와 [동시성 테스트 표](05-integrity-and-concurrency.md#검증-동시성-통합-테스트).
5. **성능** (1분): [인덱스 튜닝](04-index-tuning.md) 전후 표.
6. **운영** (1분): [마이그레이션 운영 규칙](02-schema-evolution.md#마이그레이션-운영-규칙)과 재현 방법.

## 예상 질문과 답할 문서

| 질문 | 답 |
|---|---|
| 왜 PostgreSQL이 아니라 MySQL인가? 부분 인덱스가 없는데? | [6.1](06-technology-choices.md#1-mysql-84--innodb), [3. D2](03-key-design-decisions.md#d2-활성-상태는-하나만을-생성-컬럼--unique로-강제한다) |
| 결제가 두 번 되면? | [5.3 멱등성 키](05-integrity-and-concurrency.md#3-멱등성-키-같은-요청은-한-번만-처리한다) |
| 상품 가격이 바뀌면 과거 구매는? | [3. D1](03-key-design-decisions.md#d1-거래는-구매-당시-조건을-고정한다) |
| 인덱스는 어떻게 정했나? | [4](04-index-tuning.md) |
| 고객 정보는 언제 지우나? | [3. D6](03-key-design-decisions.md#d6-지우지-않고-상태와-이력으로-남긴다) |
| 이미 배포된 스키마를 고쳐야 하면? | [2. 운영 규칙](02-schema-evolution.md#마이그레이션-운영-규칙), V20→V21 사례 |

## 알려진 한계

이 문서들은 개발·테스트 환경에서 확인한 사실만 담았다. 다음은 검증하지 않았다.

- 운영 DB의 실제 데이터 분포에서의 실행 계획과 쓰기 지연
- 보존 기간 중 거래·문의·근태 항목 (정책 미결정)
- 다중 서버 인스턴스에서의 갱신 알림 전달

## 원본 자료

- [DB 관리 안내](../db/README.md), [전체 ERD](../db/erd.mmd), [전체 구축 SQL](../db/schema-full.sql)
- [기반 DB 설계](../design/foundation/database.md)
- [마이그레이션](../../src/main/resources/db/migration/)
- [개발 기록](../development-log/)
