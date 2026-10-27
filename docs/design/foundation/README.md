# 1차 기반 기능 DB·API 설계

2026-10-27 변경: 개발 DB의 실행 기준은 [통합 V1](../../db/README.md)이다. 모든 기존 데이터가 재생성 가능한 테스트 데이터라는 사용자 확인에 따라 [새 DB 전환 방침](../../db/foundation-upgrade.md)을 채택했다. 아래 V2/V3 설명과 과거 검증·ERD PDF는 당시 설계 기록이며 현재 실행 SQL은 `src/main/resources/db/migration/`를 따른다.

2026-10-27 추가: Issue #15의 초대 미리보기·신규/기존 계정 수락·최종 승인은 [초대 수락·승인 구현 계약](invitation-acceptance-api.md)을 따른다. 기존 요청·응답 모델과 계약 버전은 유지하고 해당 operation의 구현 상태만 OpenAPI에 표시한다.

2026-10-27 추가: Issue #14의 초대 발송 worker·SMTP·lease·재시도는 [메일 발송·장애 복구 계약](invitation-delivery.md)을 따른다. 기본 비활성이며 실제 운영 SMTP 수신 및 공개 수락·승인(#15)의 검증 범위를 구분한다.

2026-10-27 추가: Issue #28의 로그인 계정·접속 출처별 요청 제한, 신뢰 프록시 경계, 인증 사건 감사는 [로그인 보호 기준](login-protection.md)을 따른다. HTTP 계약은 기존 `POST /auth/login`의 429를 유지한다.

2026-10-27 추가: Issue #16의 상품·분류 목록·상세·등록·수정·판매 상태·판매 지점·변경 이력은 [상품 API 구현 계약](product-api.md)을 따른다. 요청·응답 모델과 계약 버전은 유지하고 해당 operation의 구현 상태를 OpenAPI에 표시한다. 구매·결제·발급(후속)과 구분한다.

2026-10-27 추가: Issue #13의 초대 목록·상세·생성·재발급·취소·반려는 [초대 관리 구현 계약](invitation-management-api.md)을 따른다. 발송 작업 예약과 실제 메일 발송(#14), 수락·최종 승인(#15)을 구분한다. 기존 요청·응답 모델은 유지하며 해당 operation의 구현 상태를 OpenAPI에 표시한다.

2026-10-26 추가: Issue #17의 기반 lookups와 단일 인스턴스 SSE는 [workspace-api.md](workspace-api.md) 및 OpenAPI `0.1.4-workspace`의 구현 계약을 따른다. 상품·분류 선택 데이터 및 운영 프록시·다중 인스턴스 검증 범위를 구분한다.

2026-10-26 추가: SRS v1.1의 상품 정책은 **물리 삭제 미제공·판매 중지/재개·기존 이력 보존**이다. 초기 기반 설계의 Q8 조건부 삭제안은 더 이상 적용하지 않으며, [정책 결정](decisions.md)·[API 계약](api.md)·[DB 설명](database.md)을 확인한다. 기존 V2/V3 마이그레이션과 이를 설명하는 과거 검증 기록은 변경하지 않는다. 정본 요구사항은 작업공간 SRS v1.1이며 아래 v0.6 정보는 최초 설계의 근거를 설명한다.

2026-10-26 추가: Issue #27의 M1 닉네임 저장은 [확정 프로필 계약](profile-api.md)에 따라 먼저 OpenAPI `0.1.3-profile`과 API 엑셀에 반영되었다. 이후 조건부 상품 삭제 API를 제거한 **현재 OpenAPI 버전은 `0.1.4-product-retention`**이다. API 엑셀은 이전 버전이므로 새 정책과 버전 동기화 전까지 상품 삭제 관련 항목은 사용하지 않고 JSON 정본을 우선한다. `PATCH /me/profile`의 프로필 계약은 유지한다. 아래의 최초 기반 설계 범위·구현 전 설명과 구분한다.

작성일: 2026-10-23. 갱신일: 2026-10-26. 상태: **DB V2/V3 구현·Docker 검증 완료, 업무 API는 구현 전 목표 계약**. 검토 완료된 8개 중분류의 61개 요구사항을 대상으로 한다. 최초3개와 후속9개 정책 선택을 [decisions.md](decisions.md)에 기록했다. [DB 구축 자료](../../db/README.md), [API 엑셀](OpenStudy-Foundation-API.xlsx), [ERD PDF](OpenStudy-Foundation-ERD.pdf)를 제공한다.

## 범위와 근거

| 대분류 | 중분류 | 요구사항 수 |
| --- | --- | ---: |
| 관리자 | 지점 관리 | 7 |
| 관리자 | 직원·권한 관리 | 9 |
| 관리자 | 이용권 상품 관리 | 10 |
| 관리자 | 직원 초대·승인 | 5 |
| 백오피스 공통 | 인증·세션 | 12 |
| 백오피스 공통 | 지점 선택 | 1 |
| 백오피스 공통 | 초대 수락·대기 | 3 |
| 시스템 공통 | 공통 업무 규칙 | 14 |
| **합계** | **8개** | **61** |

정본 요구사항은 작업공간 `docs/planning/SRS/OpenStudy_Service_SRS_v0.6.xlsx`의 `요구사항 명세` 시트다. 추출 당시 SHA-256은 [requirements.json](requirements.json)에 기록했다. 이 JSON은 설계에 사용한 61행의 추적용 사본이며 SRS 수정본이 아니다. 기존 결정은 `docs/design-decisions/decision-register.md`, DB 기준은 `src/main/resources/db/migration/V1__init_schema.sql`, 기존 화면 계약은 작업공간 `docs/api/openapi.json` 및 프론트엔드 스키마를 대조했다.

백엔드 구현과 연결해 검토할 수 있도록 이 묶음은 백엔드 저장소에 둔다. 기존에 결정한 별도 문서 저장소의 정본 운영을 변경하는 결정은 아니다. 문서 저장소가 준비되면 이 설계의 버전·커밋을 연결해 정본 위치와 프론트 계약을 동기화한다. 기존 전체 API 명세는 과거 범위의 계약이므로, 이번 범위에는 이 설계안을 대조 기준으로 사용한다.

## 읽는 순서

1. [정책·설계 결정](decisions.md): 기존 확정 사항, 사용자가 선택한 12개 정책, 이번 설계의 구현 방법.
2. [DB 설계](database.md): ERD, 테이블·컬럼·제약·인덱스, V1과의 차이, 트랜잭션과 마이그레이션 순서.
3. [API 설계](api.md) 및 [OpenAPI JSON](openapi.json): 인증·권한표, 상태 전이, 요청·응답·오류·실시간 갱신 계약.
4. [요구사항 추적표](traceability.md): 61개 요구사항과 저장 모델·API·인수 시나리오 연결.
5. [개발 단위와 완료 조건](implementation-plan.md): 마일스톤 후보, 저장소별 이슈 초안, 선행 관계와 검증 범위.

## 자료 위치

- 실행 스키마 정본: `src/main/resources/db/migration/`의 V1~V3. 기존 DB는 Flyway로 증분 적용한다.
- 팀원용 빈 DB 전체 구축 SQL: [schema-full.sql](../../db/schema-full.sql). 실제 V1~V3 Flyway 이력과 기본 권한 seed가 포함되어 있다.
- ERD: [전체 Mermaid 원본](../../db/erd.mmd), [기반 21개 테이블 PDF](OpenStudy-Foundation-ERD.pdf).
- API: [OpenAPI 정본](openapi.json), [사람이 읽는 엑셀](OpenStudy-Foundation-API.xlsx). 변경 시 두 자료의 계약 버전을 맞춘다.
- 개발 계획은 implementation-plan.md 한 곳에서 관리한다. 등록용 개별 복제 파일·제작 도구·패키징 중간 파일은 저장소에서 관리하지 않는다.

## 이번 단계의 경계

상품 정의·유형별 제공량·판매 범위·변경 이력을 설계한다. 고객, 구매, Mock 결제, 발급 이용권, 좌석·예약·사물함, 근무표, 분석 API의 구현 설계는 후속이다. 기존 V1의 해당 테이블과 참조는 보존하며, 상품의 과거 구매 조건을 보존할 연결 계약만 정한다.

공통 오류·권한·트랜잭션·멱등성·SSE 계약은 이번 기능에 필요한 기반과 후속 기능의 준수 규칙을 함께 명시한다. 결제 등 미검토 영역의 업무 규칙을 확정하지 않는다. `/lookups`는 이번 범위의 선택 데이터만 계약하며, 다른 업무 화면의 조회 모델 완성을 뜻하지 않는다.

## 현재 구현과 구분

현재 백엔드에는 이메일 로그인·세션·CSRF·현재 사용자 조회와 local 전용 조회 샘플이 있다. 지점·직원·초대·상품의 운영 쓰기 API, 이 설계의 변경 이력·SSE·멱등 저장소는 구현 대상이다. `localpreview`의 응답은 운영 API 구현 증거로 사용하지 않는다.

기존 Flyway V1과 실제 개발 DB는 변경하지 않았다. V2 확장·V3 검증/강화를 추가하고 Docker MySQL 8.4에서 빈 DB, V1 데이터 보존, 미확인 매핑 차단, 전체 SQL 복원과 기존 로그인 회귀를 검증했다. [적용 절차](../../db/foundation-upgrade.md)와 [검증 결과](../../verification/foundation-schema-v3.md)를 참고한다. 업무별 인가·경합·HTTP/SSE 계약 검증은 서비스 구현 이슈에서 진행한다. Q1~Q12 답변 대기 조건은 해제했다. 상품은 물리 삭제하지 않는다. 후속 상품 서비스에서는 판매 중지·재개와 변경 이력의 원자적 기록을 구현한다.
