# 백엔드 디렉토리 구조와 책임

기준일: 2026-10-22 KST. 현재 실제 src 배치와 후속 구현 위치를 정의한다. 2026-10-18의 다섯 계층 설계를 사용자 요청에 따라 일반적인 Controller·Service·Repository 구성으로 변경했다. 변경 경위와 검증은 [개발 기록](../development-log/2026-10-22-simplify-backend-package-layout.md)을 따른다. 과거 구조는 Git 이력과 기존 개발 기록에서 확인한다. 사용하지 않는 골격의 전체 복제본은 현재 저장소에서 추적하지 않는다.

## 기준과 현재 범위

- [요구사항 명세서 v1.2](../../../docs/revised/2026-10-17/OpenStudy_요구사항_명세서_v1.2.docx)와 [요구조건 식별서 및 WBS v1.2](../../../docs/revised/2026-10-17/OpenStudy_요구조건_식별서_및_WBS_v1.2.xlsx)의 업무 책임을 유지한다.
- [설계 선택 기록](../design-decisions/decision-register.md)의 단일 빌드·기능별 패키지, Gradle Groovy DSL, JPA 엔티티와 별도 DTO, Docker 전용 MySQL 테스트 DB 결정을 유지한다.
- Java 21·Spring Boot 4.1.1·Gradle Wrapper 9.8.1의 단일 Gradle 프로젝트다. modules 하위는 Java 패키지 경계이며 Gradle 서브프로젝트가 아니다.
- 현재 업무 모듈은 디렉토리와 package-info.java만 있다. DB 설정·Flyway V1·Testcontainers 통합 테스트는 구현되어 있다. 업무 API와 ArchUnit 경계 검사는 아직 없다.

```text
src/main/java/com/openstudy/
├── OpenstudyBackendApplication.java
├── config/
├── common/
│   └── exception/
└── modules/
    └── <module>/
        ├── package-info.java
        ├── controller/
        ├── service/
        ├── repository/
        ├── entity/
        └── dto/
            ├── request/
            └── response/
```

## 업무 모듈

위치: `src/main/java/com/openstudy/modules/`. 범위 표기의 `001~006`은 해당 접두어의 연속된 요구사항을 뜻한다. 여러 모듈에 걸친 요구사항은 협력 책임을 표시한 것이며 데이터
소유권 중복을 허용하는 의미가 아니다.

| 패키지          | 책임                | 요구사항                                                            | 연결 WBS     |
|-----------------|---------------------|---------------------------------------------------------------------|--------------|
| `auth`          | 직원 인증과 세션    | OS-AUTH-001~006                                                     | W07, W08     |
| `authorization` | 지점 권한과 프리셋  | OS-EMP-002~005                                                      | W09, W10     |
| `branch`        | 지점                | OS-BR-001~004                                                       | W09, W10     |
| `employee`      | 직원과 프로필       | OS-EMP-001, OS-EMP-006, OS-AUTH-005, OS-SET-001                     | W07~W10, W39 |
| `invitation`    | 직원 초대와 승인    | OS-INV-001~006                                                      | W11, W12     |
| `task`          | 협업 업무           | OS-TASK-001~006                                                     | W13, W14     |
| `chat`          | 직원 채팅           | OS-CHAT-001~007                                                     | W15, W16     |
| `customer`      | 전사 고객 원장      | OS-MEM-001~005                                                      | W17, W18     |
| `ticket`        | 상품과 보유 이용권  | OS-PASS-001~008                                                     | W19, W20     |
| `payment`       | Mock 결제와 환불    | OS-PAY-001~007                                                      | W21, W22     |
| `seat`          | 일반 좌석과 이용    | OS-SEAT-001~005                                                     | W23, W24     |
| `reservation`   | 스터디룸과 예약     | OS-RSV-001~006                                                      | W25, W26     |
| `locker`        | 사물함              | OS-LOCK-001~004                                                     | W27, W28     |
| `facility`      | 시설과 점검         | OS-FAC-001~005                                                      | W29, W30     |
| `inquiry`       | 고객 문의           | OS-INQ-001~003                                                      | W31, W32     |
| `notice`        | 내부 공지           | OS-NOTI-001~003                                                     | W31, W32     |
| `workforce`     | 근무와 근태         | OS-SHIFT-001~004, OS-ATT-001~006, OS-LEAVE-001~005, OS-SWAP-001~003 | W33~W36      |
| `sales`         | 일반 매출과 지표    | OS-SALE-001~005                                                     | W37, W38     |
| `home`          | 운영 홈과 설정 조회 | OS-HOME-001~004, OS-SET-001~003                                     | W06, W39     |
| `ai`            | DomainLens 컨슈머   | OS-AI-001~007                                                       | W04, W42~W45 |

`workforce`는 근무표·근태·휴가·대체근무를 묶는다. 전 지점 시간 충돌 검사와 승인/복원 트랜잭션이 겹치므로 지금 단계에서 네 개의 독립 모듈로 분리하지 않는다. 세부 업무 패키지는 구현량에 따라 service·entity 등 필요한 계층 아래에 추가한다. 빈 하위 업무 폴더를 미리 만들지는 않는다.

고객 원장 (`customer`)과 직원 (`employee`)은 분리한다. `ticket`은 상품과 보유 이용권을 함께 소유한다. 예약은 `reservation`에서 룸 자원까지 소유하고 일반 좌석은 `seat`에
둔다. 공지 (`notice`)와 초대 메일/SSE 기술 기반도 구분한다. 별도 알림 발송 모듈은 만들지 않는다.

## 모듈 내부

| 디렉토리 | 배치 기준 |
|---|---|
| controller/ | @RestController, URL 매핑, 요청 검증과 응답 변환. 업무 처리는 Service에 위임하며 Repository를 직접 호출하지 않는다. |
| service/ | @Service, 업무 흐름·권한 검사·트랜잭션·여러 모듈의 협력. 필요한 외부 연동 코드도 해당 책임 안에서 구현하고 별도 패키지는 규모에 따라 추가한다. |
| repository/ | Spring Data JPA Repository 인터페이스, 조회·저장, 필요한 사용자 정의 저장소 구현. 인터페이스와 구현을 별도 최상위 계층으로 나누지 않는다. |
| entity/ | JPA 엔티티, 값 객체와 상태 변경 규칙. Controller·Service·Repository·HTTP DTO에 의존하지 않는다. 복잡한 업무 정책은 Service 내 별도 클래스로 분리할 수 있다. |
| dto/request/ | HTTP 입력 DTO. 허용할 입력 필드와 Bean Validation 제약을 명시한다. |
| dto/response/ | HTTP 출력 DTO. 필요한 필드만 공개하며 엔티티를 직접 포함하지 않는다. |

예를 들어 예약 생성은 controller/ReservationController → service/ReservationService → repository/ReservationRepository 흐름으로 처리한다. entity/Reservation이 상태와 업무 규칙을 가지며 DTO는 dto/request와 dto/response에서 관리한다. 위 클래스명은 배치 예시이고 현재 구현된 클래스가 아니다.

JPA 업무 엔티티와 별도 영속 모델을 기계적으로 이중화하지 않는다. Service 인터페이스/구현도 필요할 때만 분리한다. 모듈 간 전달용 DTO가 필요하면 dto 아래에 별도 타입을 추가하며 HTTP 요청 DTO를 내부 명령으로 무조건 재사용하지 않는다.

## 의존 방향과 모듈 경계

- Controller → Service → Repository의 호출 흐름을 따른다. Service와 Repository는 해당 모듈 Entity를 사용한다.
- Controller는 Entity를 요청·응답으로 직접 사용하지 않는다. Entity는 웹 처리나 저장소 호출을 담당하지 않는다.
- 모듈 간 호출은 명시적으로 공개한 service 진입점과 DTO를 통해서만 수행한다. Java public 접근 제한자만으로 공개 계약을 정하지 않는다. 기능 구현 시 공개할 클래스·메서드·DTO와 호출 방향을 명시하고 후속 경계 검사에 반영한다.
- 다른 모듈의 Controller·Repository·Entity는 직접 참조하지 않는다. 모듈 간 JPA 연관관계 대신 식별자와 공개 서비스 계약을 사용한다.
- 모듈 전체 의존 그래프의 순환은 금지한다. 모든 Service를 서로 자유롭게 호출할 수 있다는 뜻이 아니다.
- 공통 기술 코드는 common에 두고 업무 모듈을 직접 참조하지 않는다. 업무 코드는 config의 구성 클래스를 직접 호출하지 않는다.

우선 검토할 협력 방향은 payment → ticket.service(이용권 구매/환불), seat → ticket.service(차감/보정), reservation → payment.service(예약 선결제/취소), chat → task.service(업무 카드), ai → sales.service(의미/집계 기준)다. 구현 전 설계 방향이며 실제 호출 그래프는 아직 없다. 홈은 각 업무의 공개 조회를 조합한다. 인증·권한·직원 간 상호 참조가 생기면 호출 방향이나 이벤트 계약을 먼저 정리한다.

## 트랜잭션

결제 승인+발급, 예약+선결제, 이용 종료+차감, 정정+보정, 승인+원장 반영은 상위 업무 Service가 같은 DB 트랜잭션으로 묶는다. 호출받는 모듈이 별도 커밋하거나 REQUIRES_NEW로 분리해 원자성을 깨지 않도록 한다. 공개 명령은 단독 호출 시에도 해당 업무의 불변조건을 검증해야 한다.

성공 변경·감사·outbox·멱등 완료는 함께 커밋하고 메일/SSE 전달은 커밋 뒤에 수행한다. outbox는 외부 전달을 위한 것이며 발급·차감·환불의 필수 동기 변경을 지연 처리하는 수단이 아니다. 잠금 순서와 version/멱등 키의 구체 계약은 스키마·업무 Service 설계에서 정한다.

## 공통 설정과 기반 기능

현재는 config/와 common/exception/만 준비한다. 아래 후속 위치는 해당 기능을 실제 구현할 때 추가하며 빈 기술 계층을 미리 만들지 않는다.

| 위치 | 책임 |
|---|---|
| config/ | 보안 필터 체인·Clock·외부 클라이언트 등 Spring 설정. 업무 규칙은 두지 않는다. |
| common/exception/ | 공통 오류 코드·예외·@RestControllerAdvice 기반 HTTP 예외 매핑. |
| common/validation, time, money, pagination | 필요한 공통 검증·시간·금액·페이지 값. 매출·차감 정책은 각 업무 모듈에 둔다. |
| common/audit, idempotency, outbox | 감사·멱등·발송 대기/재시도 기술 기반. 비밀값을 저장하지 않는다. |
| common/mail, realtime, storage | 메일·SSE·이미지 저장 기술 기능. 수신 대상·현재 권한·이미지 정책은 업무 Service가 판단한다. |
| modules/auth/service | 최초 사장님 초기화 업무. 전용 명령은 일반 기동과 분리해 후속 구현한다. |
| common/seed | local/test 전용 시드 실행. 기능 구현 시 추가하고 명시적으로 실행한다. |

업무 로직을 모으는 common/service는 만들지 않는다. 외부 DomainLens 연결은 ai 모듈의 Service 책임으로 두며 실제 SDK와 규모를 확인한 뒤 필요한 client 등 하위 패키지를 추가한다.

## 리소스와 운영 명령

application.yaml은 공통 DB·JPA·Flyway·JDBC 세션 설정을 관리한다. 비밀값은 환경변수 또는 Git에서 제외한 로컬 설정에 둔다.

src/main/resources/db/migration/은 순차 DDL의 정본이며 현재 V1__init_schema.sql이 있다. Hibernate는 ddl-auto=validate로 검증하고 JDBC 세션 스키마도 Flyway로 관리한다. 버전 충돌을 피하도록 여러 모듈의 마이그레이션 순서를 관리한다.

로컬 시연 데이터는 db/seed/local/, 테스트 fixture는 src/test/resources/fixtures/에 둔다. 시드는 자동 스키마 마이그레이션과 분리하고 환경 확인 뒤 명시적으로 실행한다. 시드와 최초 사장님 전용 초기화 명령은 아직 구현하지 않았다. scripts/는 실제 프로젝트 실행 도구가 필요할 때 추가하며 빈 안내 파일을 미리 두지 않는다. 인프라 프로비저닝·실제 PG·고객 QR·DomainLens 엔진 내부 구현은 이번 변경 범위에 포함하지 않는다.

## 테스트와 검사 위치

표준 src/test/java/com/openstudy를 사용하며 별도 Gradle source set은 없다.

| 위치 | 검증 책임 |
|---|---|
| architecture/ | 후속 ArchUnit: 모듈 순환·공개 Service/DTO 외 접근·Controller의 Repository 직접 호출·Entity/DTO 경계·common/config 의존 방향. |
| modules/<module>/ | 해당 업무 규칙·Service·Controller·Repository 테스트. |
| common/ | 감사·멱등·outbox·전달 기반 등 공통 기능 테스트. |
| integration/http/ | 실제 HTTP·세션·CSRF·권한·계약·커밋 결과. 현재 DB 초기화/제약 테스트도 이 위치에 있다. |
| integration/concurrency/ | 중복 승인·차감·예약·version 충돌·롤백 경합 검증. |
| integration/domainlens/ | 실제 공개 엔진+MySQL, 일반 매출과 값/권한 비교. |
| support/database/ | 구현된 MySqlIntegrationTest와 MySqlTestConfiguration. |
| support/fixture, clock, http | 재현 데이터·시간 경계·클라이언트 도우미. |

Docker 전용 MySQL 8.4/InnoDB를 Testcontainers로 관리한다. 컨테이너 설정이 없을 때 개발 DB로 대체하지 않는다. 기존 32개 검증 결과는 [검증 보고서 v1.0](../verification/database-initialization-v1.0.md)에 기록되어 있으며 이번 구조 변경의 검증 결과와 구분한다. ArchUnit 의존성·실행 가능한 경계 검사는 아직 없으며 BE-003에서 허용/위반 예제로 검사 효력을 검증한다.

OS-NFR-001~004는 auth/authorization 및 각 Service와 common 기반, OS-NFR-007은 공통 값과 ticket/sales/ai, OS-NFR-008~010은 마이그레이션·시드·테스트·실행 인계 문서에 연결된다. 화면 접근성·반응형은 프론트 책임이고 성능 목표는 실제 서버 부하 검증 때 측정한다.

## 각 모듈의 구현 시 주의할 정책

- `auth`: 로그인·로그아웃, 자격 증명 검증, JDBC 세션, CSRF, 60분/12시간 만료. 프로필 저장은 employee의 공개 계약을 사용한다.

- `authorization`: 현재 DB의 지점 소속·역할·행동/필드 권한, 한 지점씩 저장, 복사형 프리셋. 일반 권한 변경과 계정 제한에 따른 회수 정책을 구분한다.

- `branch`: 지점 정보·운영시간·운영 상태. 자원의 상세 상태는 각 자원 모듈이 소유한다.

- `employee`: 직원 계정·재직 상태·닉네임·사진·직원 version. 이메일 ID 불변, 프로필과 직원 정보의 version 공유. 응대 고객 이력은 customer와의 권한 계약으로 제공한다.

- `invitation`: 초대·수락·최종 승인과 발송 revision. 매 발송/재시도 새 토큰, 최초 24시간 유지. DB/outbox에 원문 또는 복호화 가능한 토큰을 저장하지 않는다.

- `task`: 업무 원본·배정·상태·체크리스트·변경 이력. 홈과 채팅 카드는 이 모듈의 업무 ID와 공개 조회 계약을 참조한다.

- `chat`: 채널·DM·참여자·메시지·스레드·읽음 위치. REST 전송과 SSE 수신, 재접속·전송 멱등. AI 대화와 저장 책임을 분리한다.

- `customer`: 전 지점 공통 고객 등록·검색·상태·응대 이력. 직원 계정과 분리하고 고객 상세에서 결제 정보를 별도 권한 없이 노출하지 않는다.

- `ticket`: 시간권·기간권·정액권 상품, 발급권·구매 조건/요율 스냅샷·잔량·소비/보정 원장·사용 이력. 구매 지점과 이용 지점을 구분한다.

- `payment`: Mock 승인/실패·거래·미사용 전액 환불·원거래/환불 원장. 구매 지점·환불 처리 지점 보존. 실제 PG와 부분 환불은 범위 밖이다.

- `seat`: 현장 좌석·활성 이용 세션·이동·종료·직원 예외 지원·시간 정정. 차감/보정은 ticket과 같은 트랜잭션에서 반영한다. 고객 QR 실제 연동은 후속이다.

- `reservation`: 룸별 요일별 운영시간/휴무·요금·예약·선결제·취소·실제 이용. 중복 시간 방지, 최대 10분 조기 시작·추가 과금 없음·예약 종료 유지.

- `locker`: 지점별 사물함 상태·기간 배정·연장·반납 및 중복 기간 검사.

- `facility`: 시설·점검 주기·고장/처리·완료 이력. 마지막 완료일과 시설별 주기로 다음 점검을 산정한다.

- `inquiry`: 운영자의 전화/현장 문의 대리 등록·배정·답변·완료 이력.

- `notice`: 전체/지점 공지·대상·고정·게시 종료. 채팅 알림이나 외부 발송과 구분한다.

- `workforce`: 근무 계획·실제 출퇴근/휴게·정정 승인·휴가 부여/사용/복원·대체근무. 전 지점 직원 시간 충돌과 직원/연도 휴가 잔량을 한 경계 안에서 관리한다.

- `sales`: 원거래 기반 집계·두 환불 기준·개인정보 없는 최소 근거 원장. sales:read와 payments:read 분리. AI와 동일한 지표 정의를 제공하며 이용권 소비를 새 매출로 더하지 않는다.

- `home`: 권한에 맞춘 운영 집계와 설정 진입 정보. 원본 업무/좌석/시설 데이터를 복제하지 않는다. 테마·반응형·로딩 화면 등은 프론트 책임이며 별도 CRUD 모듈을 만들지 않는다.

- `ai`: 질문·실행/대화 참조·공개 starter/API/SPI 어댑터·의미/권한 바인딩. sales 지표를 공유한다. 엔진 내부·SQL 생성기를 구현하지 않고 실제 산출물 확인 전 연동을 미검증으로 둔다.

## 후속 구현

1. BE-003/004에서 현재 계층과 공개 Service/DTO 경계를 검사하고 공통 오류·입력 검증을 구현한다.
2. 구현된 DB·Testcontainers 기반 위에 필요한 시드·Clock·업무 기능을 추가한다.
3. 작은 Task별로 인증·직원·지점·권한 등을 구현하고 실제 HTTP·DB 경합을 검증한다.
4. 실제 DomainLens 산출물을 확인한 뒤 ai 모듈의 외부 연동 코드를 구현한다.

공통 요구사항/API 문서는 현재 상위 docs를 참조한다. 이번 변경은 패키지 배치와 관련 안내를 정리한 것이며 HTTP 계약·DB 스키마·업무 기능을 변경하지 않는다.
