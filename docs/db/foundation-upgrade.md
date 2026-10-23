# 기반 스키마 V1 → V3 적용

대상: MySQL 8.4, 계약 `0.1.2-design`. V1은 수정하지 않는다. 이 문서는 개발 DB를 자동 삭제하거나 운영 DB에 바로 실행하는 절차가 아니다. 이번 검증은 Docker 전용 DB에서 수행했다.

## 빈 DB

일반 개발·배포는 **앱의 Flyway V1 → V2 → V3**를 사용한다. `.env` 설정 후 기존 방식으로 백엔드를 실행하면 된다. 직원·상품 샘플은 추가하지 않으며, 기본 권한 9종·시스템 프리셋 2종·관리자 가드 1행만 준비한다. 현재 로컬 OWNER 초기화 방식은 그대로다.

팀원이 SQL로 빈 DB를 구축해야 한다면 [전체 구축 SQL](schema-full.sql)을 사용할 수 있다. **테이블이 하나도 없는 DB에서만** 실행한다. DB·계정 생성과 GRANT는 포함하지 않는다. `mysql --default-character-set=utf8mb4 -u <사용자> -p <빈DB명> < schema-full.sql`로 적용한다. PowerShell은 입력 리다이렉션 대신 MySQL 콘솔의 `SOURCE D:/.../schema-full.sql;`을 사용할 수 있다.

전체 SQL은 검증된 V3의 `SHOW CREATE TABLE`과 실제 V1-V3 Flyway 이력을 함께 내보낸 것이다. 적용 후 같은 버전의 앱이 `validate`하고 추가 마이그레이션 0건으로 기동하는 것을 테스트했다. **전체 SQL과 V1/V2/V3를 중복 수동 실행하지 않는다.** 이후 변경은 새 Flyway 버전으로 적용한다. 이력이 포함되지 않은 과거 설계 SQL에 이 SQL을 덧붙이거나 `baselineOnMigrate=true`로 건너뛰지 않는다.

## 기존 V1 데이터가 있는 DB

먼저 DB 백업과 복원 연습을 마치고 앱·worker 쓰기를 멈춘다. 원본 행 수, PK, 금액, 상품 조건, version, 생성·수정 시각을 기록한다. 기존 상품·초대가 하나도 없으면 일반 Flyway 실행으로 V3까지 적용된다. 상품이나 초대가 있다면 다음 순서로 처리한다.

1. 배포 환경의 Flyway target을 **2**로 제한하여 V2까지만 적용한다. Spring Boot 설정은 `SPRING_FLYWAY_TARGET=2`다. 이 상태는 운영 서비스 재개용 스키마가 아니며 앱/worker로 업무 쓰기를 하지 않는다.
2. `foundation_legacy_review`에서 기존 상품·초대 목록을 확인한다. 이 테이블은 매핑 확인 표식이며 V3 성공 시 제거된다. 확인하지 않은 행의 `confirmed`를 일괄 true로 바꾸지 않는다.
3. 각 상품의 실제 통계 분류를 `product_categories`에 등록하고 `products.category_id`를 연결한다. 분류 kind는 상품 kind와 같아야 한다. **실제로 판매하던 지점만** `product_branches`에 연결한다. 전 지점 자동 연결이나 상품 code/이름을 이용한 추측은 하지 않는다.
4. 기존 기간권의 `valid_days`, 0원 상품 등 새 제약과 맞지 않는 값은 실제 운영 근거를 확인해 수정한다. 임의로 0원 → 1원, unknown → 기본 분류로 바꾸지 않는다. 별도 정정 기록을 남긴다.
5. 초대의 이름은 수락 계정이 있으면 V2에서 해당 이름을 보존한다. 나머지는 확인한 이름을 입력한다. 당시 초대 예정 권한을 `invitation_permission_grants`에 명시 매핑한다. 현재 프리셋을 과거 원본이라고 간주하여 자동 복사하지 않는다. 검토한 빈 권한 집합은 허용된다. 과거 반려·취소의 작업자·시각·사유가 없다면 임의 작성하지 않고 이관을 보류한다.
6. 수락·승인·반려·취소 상태의 실제 행위자/시각과 토큰 폐기 상태를 확인한다. 이메일 정규화 충돌, 중복 해시, 기존 동명 시스템 프리셋의 권한 차이도 해결한다. 해당 엔티티 매핑이 확인된 뒤에만 개별 `confirmed=TRUE`로 표시한다.
7. target 설정을 해제하고 V3를 실행한다. V3는 매핑·정규화 충돌·상태·상품·seed 충돌을 **변경 전에** 검사한다. 통과한 뒤 email collation/정규화, NOT NULL/CHECK/UNIQUE 강화, 권한 seed, 현재 상품 BASELINE revision을 만든다. 기존 ID·version·비밀번호·구매 snapshot을 유지한다. 이메일 정규화는 `updated_at`을 증가시키지 않는다.
8. 전후 비교와 `flyway validate`, 앱 회귀 검증 후에 쓰기를 재개한다. V2/V3는 MySQL DDL을 포함하므로 트랜잭션 롤백을 기대하지 않는다. 중간 DDL 실패는 백업 복원 또는 실제 적용 상태에 맞춘 복구가 필요하다.

### 검토용 조회

```sql
SELECT * FROM foundation_legacy_review ORDER BY entity_type,entity_id;
SELECT p.id,p.code,p.kind,p.price_krw,p.valid_days,p.category_id,
       GROUP_CONCAT(pb.branch_id ORDER BY pb.branch_id) AS reviewed_branches
FROM products p LEFT JOIN product_branches pb ON pb.product_id=p.id GROUP BY p.id;
SELECT id,invitee_name,invitee_email,status,accepted_employee_id,accepted_at,
       approved_by,approved_at,rejected_by,rejected_at,rejection_reason,
       cancelled_by,cancelled_at FROM employee_invitations ORDER BY id;
SELECT LOWER(TRIM(email)) COLLATE utf8mb4_0900_as_ci AS normalized,COUNT(*) AS n
FROM employees GROUP BY normalized HAVING COUNT(*)>1;
```

애플리케이션 DB 계정에 부족한 migration 권한이 있다면 전용 배포 계정으로 실행한다. V3 사전 검사는 임시 stored procedure를 생성하므로 `CREATE ROUTINE`, 실행·제거 권한이 필요하다. 성공 후 procedure는 제거된다.

### 사전 검사 실패 후 재시도

`FOUNDATION_*` 사전 검사에서 실패하면 데이터 변경·강화 DDL은 시작하지 않았지만, MySQL에는 Flyway 실패 이력과 `foundation_preflight` procedure가 남을 수 있다. 실패 위치가 `CALL foundation_preflight()`이고 이후 DDL이 적용되지 않았음을 먼저 확인한다. 매핑을 수정한 뒤, **같은 migration 파일·같은 DB**의 Flyway `repair`로 실패 기록만 정리하고 다시 실행한다. 재시작 시 procedure는 재생성된다. checksum 불일치나 뒤쪽 DDL 실패를 무조건 `repair`로 무시하지 않는다.

## DB 제약과 서비스 책임

DB가 보호하는 항목: FK, UNIQUE, 상품 유형 조합·양수·기간권 validity, 초대 상태별 필수 필드·해시 폐기, 단일 OWNER 최대값, 기본 seed, 삭제 후 이력 생존 FK.

후속 서비스가 보호할 항목: 현재 인가·전체 지점 범위, 최소 활성 관리자, 최소 판매 지점 1개, category kind 일치, 상품 kind 불변, 감사/revision/outbox의 원자적 쓰기, 초대 상태 전이·worker lease·세션 회수·SSE. 테이블 구축만으로 해당 API가 구현되지는 않는다.
