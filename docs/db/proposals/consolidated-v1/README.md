# 신규 DB용 통합 V1 (검토 초안)

> 이 문서는 채택 전 검토 기록이다. 2026-10-27 개발 DB 재생성 방침에 따라 실행 정본과 검증을 정리한다. 현재 적용 기준·검증 상태는 [DB 관리 안내](../../README.md)와 [전환 기록](../../../development-log/2026-10-27-consolidated-v1-rollout.md)을 따른다. 아래 미실행 표시는 초안 작성 당시의 상태이며 이 폴더의 SQL을 직접 실행하지 않는다.

이 파일은 `docs/db/proposals/consolidated-v1/`에서만 관리하며 Flyway가 자동 실행하지 않습니다. MySQL 8.4의 빈 DB를 대상으로 기존 V1~V3 최종 스키마 및 M3~M4 V4/V5 확정 설계 조건을 `CREATE TABLE`에 통합했습니다.

## 반영
- 기존 65개 업무 및 Spring Session 테이블과 권한·프리셋·관리 가드 기본 데이터.
- 결제 1건당 이용권 1장, `payments.issued_pass_quantity`의 상태별 CHECK. 발급 원자성은 서비스에서 보장.
- 구매 시 `product_revisions` 연결과 `passes`의 상품 조건·분류 스냅샷.
- 기간권 최초 입실 전 `NOT_STARTED`, 시작·종료 시각 NULL; 최초 입실 시 정확히 기간 일수×24시간 활성화. 첫 입실 기한 없음.
- 미사용 이용권 전액 환불은 서비스 정책 및 트랜잭션에서 처리.

## 제외
- `flyway_schema_history` 테이블 생성·이력 INSERT. Flyway가 직접 관리.
- V2/V3의 **기존 데이터 이관 전용 검사/임시 테이블**, V4/V5의 **기존 거래 데이터 백필 검사**.
- 고정 시각으로 생성한 테스트 시드 날짜(현재 UTC 기준으로 교체).

## 안전
**기존 V1~V3을 적용한 DB에서는 수정된 V1을 사용할 수 없습니다.** Flyway 체크섬이 다르므로 빈 개발/테스트 DB를 명시적으로 재생성하거나, 기존 데이터를 보존할 때는 이전 V1~V3과 V4/V5 증분 경로를 유지해야 합니다. Flyway repair로 무마하지 마세요.

현재는 정적 구조 검사만 통과했습니다. MySQL/Flyway/Testcontainers 실행, 애플리케이션 통합 테스트, 새 schema-full.sql 재생성과 ERD 동기화는 미실행입니다. **이 초안을 곧바로 실행용 migration 경로로 옮기지 마세요.**
