# OpenStudy DB 초기 설계 v0.4 — 사물함 4주(28일) 정액권

작성 기준: `OpenStudy_요구사항_명세서_v1.1.docx` 및 `OpenStudy_요구조건_식별서_및_WBS_v1.1.xlsx` (2026-10-18 정책 반영본).

## 1. 산출물

| 파일 | 용도 |
|---|---|
| `openstudy_schema_v0_4.sql` | MySQL 8.4/InnoDB 최초 생성 DDL (57개 테이블) |
| `openstudy_logical_erd.mmd` | 업무 도메인 관계 중심 논리 ERD |
| `openstudy_physical_erd.mmd` | 전체 테이블/컬럼/FK 관계를 담은 물리 ERD (대형) |
| `diagrams/01_access_physical.mmd` | 지점·직원·역할·권한·초대·감사 |
| `diagrams/02_collaboration_physical.mmd` | 업무·체크리스트·이력·채팅·공지 |
| `diagrams/03_commerce_physical.mmd` | 고객·상품·결제·환불·이용권·차감 원장 |
| `diagrams/04_space_physical.mmd` | 좌석·입퇴실·스터디룸·예약·유료 사물함 |
| `diagrams/05_facility_physical.mmd` | 시설·점검·고장·고객 문의 |
| `diagrams/06_hr_physical.mmd` | 근무표·출퇴근·휴게·정정·휴가·대체근무 |
| `diagrams/07_ai_session_physical.mmd` | DomainLens 소비 기록·Spring Session JDBC |

`.mmd`는 Mermaid `erDiagram` 형식이며 GitHub Mermaid 지원 Markdown 코드 펜스 안에 붙이거나 Mermaid Live Editor에 불러오면 된다. 전체 물리 ERD는 복잡하므로 실제 검토에는 영역별 파일을 권장한다. 논리 ERD의 이름은 비즈니스 개념이고 반드시 DDL의 실제 테이블과 1:1로 대응하지 않는다.

## 2. 물리 테이블 범위

| 구분 | 테이블 수 | 핵심 대상 | 출처 기능 |
|---|---:|---|---|
| 지점·인증·권한·초대·감사 | 11 | `branches`, `employees`, `branch_memberships`, `employee_invitations`, `audit_events` | AUTH, BR, EMP, INV, NFR |
| 협업·공지 | 8 | `work_tasks`, `conversations`, `messages`, `announcements` | TASK, CHAT, NOTI |
| 고객·상품·거래 | 8 | `customers`, `products`, `payments`, `passes`, `refunds`, `pass_balance_events` | MEM, PASS, PAY, SALE |
| 공간 운영 | 11 | `seats`, `seat_sessions`, `rooms`, `room_reservations`, `lockers` | SEAT, RSV, LOCK |
| 시설·문의 | 5 | `facilities`, `facility_works`, `inquiries` | FAC, INQ |
| 근태·휴가 | 10 | `shifts`, `attendance_records`, `leave_grants`, `leave_requests`, `shift_swaps` | SHIFT, ATT, LEAVE, SWAP |
| DomainLens 소비 기록 | 2 | `ai_threads`, `ai_requests` | AI |
| 세션 | 2 | `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` | AUTH, NFR |
| **합계** | **57** | | |

전체 128개 요구사항 중 데이터 영속화가 필요한 핵심 도메인을 담았다. 프론트 상태 처리, AI 엔진 내부, 실제 SMTP 설정, 인프라 구성은 DB 스키마가 대신하지 않는다.

## 3. 확정 정책 반영

- **사장님/관리자/직원**: `employees.global_role=OWNER`는 전체 운영 범위, 지점 관리자는 `branch_memberships.role=MANAGER`, 일반 직원은 `STAFF`. 권한은 멤버십별 `membership_permission_grants`에 복사하므로 프리셋 편집이 기존 직원 권한을 자동 변경하지 않는다. `status`와 **현재 DB 권한**을 요청마다 재확인해야 한다. (`P03`, `P27`, `P32`)
- **인증**: 직원 이메일 고정 식별자, 비밀번호 해시, JDBC 세션, 60분 유휴/인증 후 최대 12시간. DB의 `SPRING_SESSION`만으로 12시간 절대 만료를 강제하지 못하므로 Spring Security/세션 필터가 인증 시각 속성을 확인해야 한다. 이메일 변경 요청은 동일값 전송도 API에서 422 거절한다. (`P26`, `P28`, `P29`)
- **초대**: 최초 발급 기준 24시간·일회성·관리자 최종 승인·재발송 시 이전 링크 무효. 원문 토큰은 DB 및 복호화 가능한 payload에 저장하지 않는다. outbox는 초대 ID와 revision만 저장한다. 발송 워커에서 매 시도 새로운 원문 토큰을 **일시적 메모리에서 생성**하고 동일 트랜잭션에 해시/revision/발송 작업 상태를 관리하는 설계 계약이 필요하다. DB가 메일 외부 발송을 한 트랜잭션으로 보장하지는 않는다. (`P05`, `P31`)
- **고객**: 직원과 분리된 전 지점 공통 원장. 소속 지점에서 응대 확인된 **특정 고객**에 한해 다른 지점 이용 이력 조회 가능, `customer_service_interactions`와 `audit_events`에서 접근 근거 보존. 타 지점 전체 고객 검색이나 결제 내역 접근에 자동 적용하지 않는다. (`P04`, `P07`)
- **이용권**: 시간권/기간권/정액권 별 구매 당시 금액·이용량·요율·구매 지점·상품명 스냅샷. **시간권·정액권은 유효기간 없는 상품을 허용**하며 `products.valid_days IS NULL`, `passes.snapshot_valid_days IS NULL`, `passes.expires_at IS NULL`로 보존할 수 있다. 시간권은 양수 이용 초 단위에서 분 올림, 기간권은 결제시각부터 상품일×24h, 정액권은 구매시 요율×분/60 원 단위 올림. 소비는 결제액과 별도 매출로 합산하지 않는다. (`P06`, `P08`, `B04`~`B06`)
- **사물함 4주(28일) 단위 과금**: `lockers.rate_per_4weeks_krw`를 정의하고 `payments.purpose=LOCKER`를 유지. `locker_assignments`는 배정 상태·유효기간을 관리하고, `locker_assignment_charges`는 **최초 배정/유료 연장**마다 청구 4주권 수량·당시 4주권 요금·합계·원결제·적용 상태를 저장한다. Mock 결제 승인과 배정 활성화/기간 연장은 한 트랜잭션으로 처리하며, 실패 결제는 대여 권한을 생성하지 않는다.
- **결제/환불**: `payments`는 승인/실패 원거래, `refunds`는 최초 결제와 처리지점/시간을 연결하는 1회 전액 환불 사건. 판매액은 `payments.branch_id`, 환불 귀속은 원결제 지점, 실제 사용은 `seat_sessions.branch_id`. 매출 조회는 `payments`+`refunds` 공통 원장을 이용한다. (`P09`, `P10`, `P15`, `P36`)
- **좌석**: 좌석의 점유 여부는 `status=AVAILABLE`만으로 결정하지 않는다. `seat_sessions.status=ACTIVE`를 합성한다. 활성 세션당 좌석 및 고객 유일성은 생성 컬럼+UNIQUE가 강제한다. 이동·시간 정정은 별도 이력/차감 원장으로 보존한다. (`P11`, `P33`, `B07`)
- **예약**: 스터디룸만 사전 예약, **30분당 정수 KRW 요금 × 30분 구간 수**를 저장하고 구매 이용권과 독립적으로 Mock 선결제 성공 후 확정. 시작 시각은 00/30분 경계로 제한하며 종료 시각은 30분 배수로 제한. 예약 생성 시점의 30분 요금을 스냅샷으로 보존. 룸 운영요일·익일 종료 지원. 실제 이용 시작은 최대 10분 전 허용하되 자원 공실·운영시간·충돌 재검사가 필요하다. (`P12`, `P34`, `B08`, `B09`)
- **휴가**: 직원/연도 공통 잔량, 원부여 지점, 기간+신청 총일수, 신청 전기간 유효 부여만 만료 빠른 순/ID 순으로 소모. `leave_allocations`와 `leave_balance_events`가 승인·전체 취소 복원의 원장을 형성한다. (`P14`, `P35`)
- **AI**: DomainLens 내부 저장 구조/SQL 생성기를 임의 복제하지 않는다. OpenStudy 측은 호출 참조/상태를 기록하고 판매/환불 원장을 기반으로 같은 계산 기준과 현재 권한을 적용한다. (`P19`, `P21`)

## 4. DB만으로 강제하지 못하는 무결성

`FK`/`UNIQUE`/`CHECK`만으로는 **겹치는 시간 구간**과 **분산된 상태 전이**를 모두 보장하지 못한다. 반드시 실제 DB 트랜잭션에서 아래 검사를 추가한다.

1. 룸 예약 시 `SELECT ... FROM rooms WHERE id=? FOR UPDATE` → `start_at < requested_end AND end_at > requested_start`로 확정/진행 예약 충돌 재검사 → 승인 결제·예약 확정(또는 실패)을 하나의 트랜잭션에 처리.
2. 사물함 최초 결제/연장 시 `lockers`와 해당 `locker_assignments` 행을 잠그고 [start, end) 활성 배정 중복 검사. Mock 승인·요금 원장 `APPLIED`·배정 `ACTIVE` 또는 종료일 연장을 하나의 트랜잭션으로 처리한다. 승인 실패 시 배정/연장 미적용, 과거 이력 보존. **청구별 구간이 28일×4주권 수량인지 CHECK**로 강제한다. 연장 시작일 = 기존 종료일, 부모 배정의 최종 종료일 = 적용된 구간의 마지막 종료일인지 서비스에서 검증한다. FK/CHECK만으로는 배정 및 여러 청구 간 연속성/원자성을 보장하지 못한다.
3. 직원 근무 계획·대체근무 승인 시 `employees` 행 잠금 후 **전 지점**의 시간 겹침 검증. `branch_memberships`가 `ACTIVE`인지 별도 확인.
4. 좌석 입퇴실·보정·환불 시 고객/이용권/활성 세션을 정해진 순서로 잠가 중복 차감·환불권 재활성화·원거래 변경 방지. 잔여량과 원장/정정 기록은 한 트랜잭션에서 반영.
5. 고객/발급권/결제/예약/공간/지점의 교차 동일성(예: 이용권 고객 = 이용 세션 고객, 결제 승인 금액 = 전액 환불액, 예약 지점 = 룸 지점)을 서비스 계층에서 재검증. 일부 지점 일치는 복합 FK를 적용했지만 전체 업무 정책을 FK로 표현하지 않았다.
6. 근태 휴게 겹침·근무 구간 외 휴게, 수정 요청 승인 재시도, 휴가 전기간의 전 지점 근무/부재 충돌과 승인 시 잔량 부족을 잠금/멱등 키로 검증.
7. 조회·API·SSE·AI 후속 실행 때 현재 권한 재조회. `audit_events` JSON에는 비밀번호 해시/초대 토큰/개인정보 원문을 저장하지 않는다.

## 5. 아직 확정되지 않은 **설계 가정**

- **사업자 수**: 하나의 운영사가 여러 지점을 소유한다. 다수 독립 사업자를 분리하는 `organizations`/tenant 구조는 만들지 않았다.
- **이용권 유효기간 (선택 확정)**: 기간권은 `period_days` 필수. **시간권·정액권은 `valid_days=NULL` 허용**. NULL 스냅샷 발급권의 `expires_at`은 NULL이다(기간권은 결제 시점부터 상품 일수×24시간).
- **사물함 4주 과금 (확정)**: **1회 청구 단위 = 정확히 4주 = 28일**. 4주권 정액 가격 `lockers.rate_per_4weeks_krw` × 양의 정수 `locker_assignment_charges.charged_4week_terms`로 금액을 계산한다. 4주권 1개(28일), 2개(56일), 3개(84일) 등의 **연속 대여**를 허용한다. 결제 당시 단가는 `rate_per_4weeks_snapshot_krw`로 보존한다. 예약/반납 날짜 구간은 KST 기준 [시작일, 종료일)이다. **달력상 월말·윤년·30일 고정 월은 이용기간에 영향을 미치지 않는다**. 1주/2주 등 다른 단위 판매, 일할 계산, 조기 반납 환불 자격은 이 초기 설계에서 확정하지 않았다. 원거래는 `payments.purpose=LOCKER`로 남긴다. 사용 전에 4주권 가격을 바꾸어도 기존 청구 가격은 바뀌지 않는다. `refunds` 구조가 있더라도 취소/반납 환불 자격은 별도 운영정책으로 결정해야 한다.
- **룸 30분 단위 요금 (선택 확정)**: `rooms.rate_per_30min_krw`를 상품 원단위 요금으로 사용, `room_reservations.rate_per_30min_snapshot_krw × slot_count = amount_krw`로 고정. 소수점/반올림 없음. 시작 시각을 00분·30분으로 제한한다. 저장/표시 시각은 UTC/KST 기준을 따른다.
- **고객 중복 기준**: `member_no`는 전사 고유, 이름/전화번호/이메일은 동일인이 아닐 수 있어 UNIQUE를 적용하지 않았다.
- **단일 사장님**: 최초 사장님 한 계정만 생성하도록 `owner_slot` 생성 컬럼에 UNIQUE를 두었다. 이 계정의 삭제/전환 정책은 별도 설계로 남긴다.
- **시간대**: `DATETIME(6)` 저장/DB 접속 시간대를 UTC로 가정하고, 한국 영업일·요일·지난달 범위는 서버의 `Asia/Seoul`에서 계산한다. 운영 서버 및 JDBC 연결에 UTC 설정 필요.
- **중복 활성 초대**: 지점+이메일별 대기/수락 초대는 최대 한 건(생성 컬럼+UNIQUE)으로 제한하는 초기 가정이다.
- **DomainLens**: 엔진 제공 버전/시점 및 구체 IR·SPI 스키마는 외부 의존성으로 남는다.

## 6. 사용 순서 및 검증 상태

1. 이 문서와 Mermaid 영역별 ERD를 검토하고 미정 질문을 확정한다.
2. 확정된 스키마를 `backend/src/main/resources/db/migration/V1__initial_schema.sql` 같은 **Flyway** 마이그레이션으로 반영한다. 파일의 `CREATE DATABASE`, `USE`, `SET time_zone` 구문은 앱별 환경 설정에 맞게 분리/제거하는 것을 권장한다. Spring Session DDL과 Flyway 실행 방식도 선택한 Spring 버전에 맞춰 동기화한다.
3. 완전히 비어 있는 **전용 개발/테스트 DB**에서 적용 후 `SHOW TABLES`/FK/UNIQUE/CHECK 및 세션 저장을 확인한다. 개발/시연 데이터를 자동 초기화 대상으로 사용하지 않는다.
4. 실 DB 연결로 룸·사물함·근무 시간 경합, 좌석 중복 활성, 결제 멱등·환불 1회성, 이용권/휴가 차감·복원, UTC/KST 경계를 검증한다.

**검증 상태:** 생성된 테이블·FK·UNIQUE·CHECK 및 논리/물리 ERD 일치 정적 검증만 수행. 이 환경에서 실제 MySQL 8.4 인스턴스에 DDL을 실행하거나 Spring Session/JPA와 통합하는 검증은 수행하지 않았다. 따라서 그대로 운영 DB에 적용할 준비가 완료되었다는 의미는 아니다.



## 7. v0.3 → v0.4 변경 내역 (2026-10-18)

| 테이블 | 이전 v0.3 (달력 월) | 현재 v0.4 (고정 4주) |
|---|---|---|
| `lockers` | `monthly_rate_krw` | `rate_per_4weeks_krw` |
| `locker_assignment_charges` | `charged_months` | `charged_4week_terms` |
| `locker_assignment_charges` | `rate_per_month_snapshot_krw` | `rate_per_4weeks_snapshot_krw` |
| `locker_assignment_charges` | 달력 월 경계는 애플리케이션 검증 | `DATEDIFF(end_date, start_date) = charged_4week_terms * 28` CHECK 추가 |
| `locker_assignment_charges` | `amount_krw = charged_months * rate_per_month_snapshot_krw` | `amount_krw = charged_4week_terms * rate_per_4weeks_snapshot_krw` CHECK |

**사물함 사용 시나리오** (KST 지역 날짜, 종료일은 포함하지 않음):

- 최초 구매: `2027-01-31` 시작, 4주권 1개 → `2027-02-28` 종료. 결제 단가 30,000원이면 30,000원.
- 연장: 기존 `2027-02-28` 종료에서 4주권 1개 더 구매 → `2027-03-28` 종료. 연장 당시 단가가 35,000원이면 신규 결제만 35,000원, 최초 30,000원은 유지.
- 8주 선구매: `2027-01-31` 시작, 4주권 2개 → `2027-03-28` 종료. 단가가 30,000원이면 60,000원.
- 계산식: `end_date = start_date.plusDays(28L * charged_4week_terms)`. 연장 시 새 청구의 `start_date = 기존 배정 end_date`, `end_date = 새 청구 시작일 + 28일 × 추가 4주권 수량`.
- 가격 변경에 대비해 결제 건마다 `rate_per_4weeks_snapshot_krw` 저장. 실패한 결제·연장에는 종료일을 확장하지 않는다. 동일 결제 중복 적용은 멱등성과 행 잠금으로 보호한다.

**일반 기간권(좌석권)과의 구분:** 이미 존재하는 `products.name` / `products.period_days`를 사용한다. 4주 기간권을 팔려면 `name='4주권'`, `period_days=28`로 설정할 수 있으며 테이블 추가가 필요 없다. 이는 사물함 대여료·청구와 독립적이다.

### 테스트해야 할 경계와 거래 조건

1. `2027-01-31` 4주 → `2027-02-28`; 8주 → `2027-03-28`. 월말 보정 규칙을 사용하지 않는지 확인한다.
2. 윤년 `2028-02-10` 4주 → `2028-03-09`. 윤년에도 28일이라는 점은 바뀌지 않는다.
3. 청구 4주권 수가 2인데 날짜 차이가 28일이면 DB CHECK가 거부해야 한다(정상은 56일).
4. 사물함 1개에 활성 대여가 겹치거나, 청구 적용 후 배정 종료일이 맞지 않는 경우 트랜잭션에서 차단한다.
5. 결제 승인/실패, 중복 승인, 연장 경쟁, 기간 종료 및 재배정, 조기 반납 환불 미정 정책의 처리 결과를 확인한다.

**미확정:** 사물함 이용권의 중도 반납·취소 환불 자격과 1주/2주 기간권 판매 여부. 별도 결정 전에는 4주(28일) 정수 배수만 지원한다.
