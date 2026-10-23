# 요구사항 추적 및 인수 기준

작성일: 2026-10-23. 8개 중분류·61개 요구사항의 **설계 추적**이다. 구현/테스트 통과 상태표가 아니다. 원문과 셀 행은 [requirements.json](requirements.json), operationId의 실제 요청·응답은 [openapi.json](openapi.json), 이슈 ID는 [implementation-plan.md](implementation-plan.md)를 따른다.

공통 요구사항은 모든 관련 API와 화면에 교차 적용한다. endpoint가 있다는 것만으로 충족되지 않으며 마지막 열의 부정·경합 시나리오가 인수 조건이다. Q1~Q12는 사용자가 확정했으며 [decisions.md](decisions.md)의 선택을 인수 기준으로 적용한다. FR-BR-08의 신규 운영 거래, FR-COM-15의 결제·예약 적용은 후속 도메인의 구현 검증까지 연결한다.


## 지점 관리

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-BR-01 · 15 | 지점 목록 조회 | branches, branch_memberships | `listBranches` | B03/B05/F02 | 허용 지점만 목록·검색·total에 포함 |
| FR-BR-02 · 16 | 지점 상세 조회 | branches, branch_memberships | `getBranch` | B03/B05/F02 | 타 지점 ID 직접 요청403·범위 내 없는 ID404 |
| FR-BR-03 · 17 | 지점 등록 | branches, branch_weekly_hours, idempotency_records | `createBranch` | B05/F02 | OWNER 등록·7요일·같은 키 재전송1건·서버 감사 필드 |
| FR-BR-04 · 18 | 지점 정보 수정 | branches, branch_weekly_hours | `updateBranch` | B05/F02 | 권한·version·운영시간 집합 원자성 |
| FR-BR-05 · 19 | 지점 운영 상태 변경 | branches, audit_events | `changeBranchStatus` | B05/F02 | 중지·재개 뒤 기존 참조·이력 유지 |
| FR-BR-08 · 20 | 비활성 지점 신규 업무 제한 | branches + 후속 운영 참조 | `changeBranchStatus` | B03/B05 | 중지 지점 신규 거래 거부 정책. 실제 판매·배정·예약 호출 검증은 해당 후속 모듈에서 추가 |
| FR-BR-10 · 21 | 지점 식별·참조 보존 | branches 및 기존 FK | `getBranch`, `updateBranch` | B02/B05/F02 | 지점명·주소 변경 후 같은 ID와 과거 참조 유지 |

## 직원·권한 관리

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-STAFF-01 · 22 | 직원 초대를 통한 계정 생성 | employees, branch_memberships, employee_invitations | `createInvitation`, `reissueInvitation`, `cancelInvitation`, `rejectInvitation`, `approveInvitation`, `acceptInvitation` | B07/B09/F03 | 초대 수락과 승인 분리·공개 직원 생성 API 없음 |
| FR-STAFF-02 · 23 | 직원 목록 조회 | employees, branch_memberships | `listEmployees`, `getEmployee` | B06/F02 | 관리 지점 밖 계정·소속 및 total 미노출 |
| FR-STAFF-03 · 24 | 직원 정보 수정 | employees | `updateEmployee` | B06/F02 | 이름/상태 수정·email 변경422·전역 권한Q3 |
| FR-STAFF-04 · 25 | 소속 지점·권한 설정 | branch_memberships, membership_permission_grants | `updateMembership`, `listPermissionPresets`, `createPermissionPreset`, `updatePermissionPreset` | B03/B06/F02 | 한 지점만 저장·자기 권한 변경 및 관리자 대상 변경403(Q4)·소유권 승격 불가 |
| FR-STAFF-05 · 26 | 직원 계정 비활성화 | employees, SPRING_SESSION | `updateEmployee` | B04/B06/F02 | 제한·비활성화 후 기존 세션 새 업무 요청 거부 |
| FR-STAFF-06 · 27 | 직원 접근 범위 제한 | branch_memberships, membership_permission_grants | `getEmployee`, `updateMembership`, `getLookups` | B03/B06/F01 | 숨겨진 메뉴와 무관하게 직접 API·타 지점 요청 거부 |
| FR-STAFF-07 · 28 | 마지막 관리자 보호 | administration_guard, employees, branch_memberships | `updateEmployee`, `updateMembership` | B03/B06 | 두 연결의 동시 마지막 관리자 회수 방지 |
| FR-STAFF-08 · 29 | 권한 변경 이력 기록 | audit_events | `updateMembership` | B02/B06 | actor/대상/시각/전후 역할·grants 보존 |
| FR-STAFF-10 · 30 | 업무 권한 항목 설정 | permission_definitions, presets, grants | `updateMembership`, `listPermissionDefinitions`, `listPermissionPresets`, `createPermissionPreset`, `updatePermissionPreset` | B03/B06/F02 | STAFF 관리자 명령 차단·일반 직원 기본권한0/관리자9종(Q5)·프리셋 복사 |

## 이용권 상품 관리

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-PROD-01 · 31 | 상품 목록·상세 조회 | products, product_branches | `listProducts`, `getProduct` | B10/F04 | 허용 상품만 조회·branchIds 교집합·없는 데이터 빈 목록 |
| FR-PROD-02 · 32 | 이용권 상품 등록 | products, product_branches, product_revisions | `createProduct` | B10/F04 | 3유형 등록·무기한 null·기간권 날짜 정책Q2 |
| FR-PROD-03 · 33 | 상품 정보 수정 | products, product_revisions + 기존 passes | `updateProduct` | B10/F04 | 가격·구성 수정 후 과거 구매 snapshot 불변 |
| FR-PROD-04 · 34 | 상품 판매 중지·재개 | products, product_revisions | `changeProductStatus` | B10/F04 | 관리자 판매 중지·재개·기존 발급 보존 |
| FR-PROD-05 · 35 | 상품 적용 지점 설정 | product_branches, product_revisions | `listProducts`, `replaceProductBranches` | B10/F04 | 구체적 판매 지점·권한 없는 지점 추가/제거403 |
| FR-PROD-06 · 36 | 상품 가격 검증 | products CHECK | `createProduct`, `updateProduct` | B02/B10/F04 | 0원·0제공량·잘못된 요율·유형별 유효일수 거부 |
| FR-PROD-07 · 37 | 상품 분류 코드 관리 | product_categories, product_revisions | `listProductCategories`, `createProductCategory`, `updateProductCategory`, `listProductHistory` | B10/F04 | 분류 code 불변·과거 이름/분류 snapshot 보존 |
| FR-PROD-08 · 38 | 상품 삭제 제한 | products와 기존 payments/passes FK | `deleteProduct`, `changeProductStatus` | B02/B10 | 미거래 상품만 물리 삭제(Q8)·거래 참조409·삭제 감사/이력 보존 |
| FR-PROD-09 · 39 | 유형별 이용 정책 설정 | products CHECK, product_revisions | `createProduct`, `updateProduct` | B10/F04 | 다른 유형 제공량·요율 동시 입력422·무기한은null |
| FR-PROD-11 · 40 | 가격·구성 변경 이력 조회 | product_revisions | `deleteProduct`, `listProductHistory` | B10/F04 | 최초+변경/삭제 전후·작업자·시각·삭제 후 원래ID의 이력 인가 |

## 직원 초대·승인

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-INV-01 · 41 | 초대 목록 조회 | employee_invitations, invitation_send_jobs | `listInvitations`, `getInvitation` | B07/F03 | 초대 상태와 발송 상태 별도·token 응답 없음 |
| FR-INV-02 · 42 | 초대 발송 | employee_invitations, invitation_permission_grants, invitation_send_jobs | `createInvitation` | B07/B08/F03 | 진행 초대·유효 소속 중복409·메일 실패로 계정 활성화 안 됨 |
| FR-INV-03 · 43 | 초대 재발급 | employee_invitations, invitation_send_jobs | `reissueInvitation` | B07/B08/F03 | 재발급 후 이전 링크 거부·수락 후 재발급409 |
| FR-INV-04 · 44 | 초대 취소·승인 반려 | employee_invitations, branch_memberships | `cancelInvitation`, `rejectInvitation` | B07/B09/F03 | 미수락 취소/수락 후 반려 구분·사유·다른 소속 유지Q1 |
| FR-INV-07 · 45 | 최종 승인·소속 활성화 | employee_invitations, employees, memberships, grants | `approveInvitation` | B09/F03 | 동시 승인·반려 중1전이·반복 승인 중복 활성화 없음 |

## 지점 선택

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-BR-06 · 159 | 작업 지점 선택 | memberships, grants / 프론트 URL·캐시 | `getCurrentUser`, `listBranches`, `getLookups`, `subscribeWorkspaceEvents` | B03/B05/B11/F01 | all도 허용 지점만·변경 시 이전 응답/캐시 제거 |

## 인증·세션

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-AUTH-01 · 160 | 이메일 로그인 | employees, SPRING_SESSION | `login` | B04 | 형식/필수·실제 해시·비활성 업무 차단 |
| FR-AUTH-02 · 161 | 계정 검증·세션 발급 | employees, SPRING_SESSION_ATTRIBUTES | `login` | B04 | 자격증명 검증·비밀값 미노출·확정 제한 세션Q1 |
| FR-AUTH-03 · 162 | 로그인 실패 응답 | employees / 안전한 진단 | `login` | B01/B04 | 없는 이메일·틀린 비밀번호 동일401 |
| FR-AUTH-04 · 163 | 계정 상태별 접근 제한 | employees, memberships, sessionMode | `login`, `getCurrentUser`, `getApprovalStatus` | B04/B09/F03 | PENDING 상태만·SUSPENDED/LEFT 차단·매 요청 인가Q1 |
| FR-AUTH-05 · 164 | 현재 사용자·권한 조회 | employees, memberships, grants | `getCurrentUser` | B04/B06/F02 | 본인 프로필·유효 소속·현재 권한·owner 구분 |
| FR-AUTH-06 · 165 | 로그아웃 | SPRING_SESSION 및 attributes | `logout` | B04/F01 | 로그아웃 후 기존 쿠키 재사용401 |
| FR-AUTH-07 · 166 | 세션 만료 처리 | SPRING_SESSION_ATTRIBUTES | `getCurrentUser` | B04/F01 | Clock으로 유휴/최대 경계·캐시 정리·재로그인 |
| FR-AUTH-08 · 167 | 세션 식별자 갱신 | SPRING_SESSION | `login` | B04 | 로그인 전후 ID 교체·이전 ID 권한 없음 |
| FR-AUTH-09 · 168 | 세션 쿠키 보호 | SPRING_SESSION / 프록시 설정 | `login` | B04/V01 | HttpOnly/SameSite·운영Secure·동일origin Path/삭제 검증 |
| FR-AUTH-10 · 169 | CSRF 방어 | 세션 CSRF 속성 | `getCsrf`, `logout`, `acceptInvitation` | B04/B09/F01 | login/accept/logout/변경의 누락·잘못된 토큰403 |
| FR-AUTH-11 · 170 | 로그인 이메일·계정 개설 정책 | employees.email·global_role | `login`, `updateEmployee` | B04/B06 | 프로필/관리 수정 email422·공개 가입 없음 |
| FR-AUTH-15 · 171 | 최초 관리자 초기화 | employees.owner_slot, administration_guard | 제한된 별도 OWNER 초기화 명령. 공개 HTTP endpoint 없음. | B04 | 제한 초기화 명령·동시 생성1명·기존 해시 덮어쓰기 없음 |

## 초대 수락·대기

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-INV-05 · 226 | 초대 정보 확인 | employee_invitations.token_hash/revision | `previewInvitation` | B08/B09/F03 | 유효 링크 본인 최소 정보·만료/사용됨 안전한 동일오류 |
| FR-INV-06 · 227 | 초대 수락 | employees, invitations, memberships | `acceptInvitation` | B09/F03 | 신규 해시 설정·기존 본인 세션·일회성 수락Q1 |
| FR-INV-08 · 228 | 승인 상태 재확인 | invitations + sessionMode | `logout`, `getApprovalStatus` | B04/B09/F03 | 본인 승인 상태/로그아웃·거절 업무 차단·승인 후 재로그인Q1 |

## 공통 업무 규칙

| 요구사항·SRS행 | 기능 | DB·클라이언트 책임 | operationId 또는 비HTTP 계약 | 개발 이슈 | 인수 확인 |
| --- | --- | --- | --- | --- | --- |
| FR-COM-01 · 239 | 관리 API 인증 검증 | 세션 + 현재 employees | 모든 관련 API·화면에 공통 적용 | B03/B04 | 공개 예외 이외 무세션401·보호 데이터 없음 |
| FR-COM-02 · 240 | 지점 접근 권한 검증 | memberships, grants, branch FK | `getLookups` | B03/V01 | URL/body/scope/검색/total/SSE 지점 우회 차단 |
| FR-COM-03 · 241 | 입력·상태 전이 검증 | 유형/상태 CHECK + DTO | 모든 관련 API·화면에 공통 적용 | B01/B02 | 입력 필수·길이·범위·상태 전이422/409 |
| FR-COM-04 · 242 | 목록 페이징·기본 정렬 | 목록 인덱스 + 안정 정렬 | 모든 관련 API·화면에 공통 적용 | B01/F01 | page1기준·최대100·동률id·빈items+total |
| FR-COM-05 · 243 | 검색·정렬 필드 제한 | 허용 검색/정렬 매핑 | 모든 관련 API·화면에 공통 적용 | B01/B03 | 미허용 필드422·SQL매개변수화·권한 유지 |
| FR-COM-06 · 244 | API 오류 형식 통일 | request_id / 공통 예외 | 모든 관련 API·화면에 공통 적용 | B01/F01 | 안전한 code/message/requestId/fieldErrors·HTTP구분 |
| FR-COM-07 · 245 | 생성·수정 추적 정보 | 각 aggregate 추적 필드, audit_events | `createBranch` | B01/B02 | 서버 설정 시각·actor·클라이언트 감사 필드 거부 |
| FR-COM-08 · 246 | 참조 데이터 삭제 제한 | 기존 FK RESTRICT, revisions | `deleteProduct`; DB 거래 참조 삭제 제한 | B02/B10 | 조건부 상품 삭제·기존 거래 참조/집계·삭제 감사/이력 보존 |
| FR-COM-10 · 247 | 트랜잭션·동시성 처리 | version·UNIQUE·guard·transaction | `deleteProduct` | B02/B03/V01 | 여러 레코드 부분 성공 없음·두 연결 경합 검증 |
| FR-COM-11 · 248 | 변경 결과·재시도 안내 | DTO·공통 오류 / 프론트 입력 | 모든 관련 API·화면에 공통 적용 | B01/F01 | 검증 오류 필드 표시·실패 입력 보존·다시 제출 |
| FR-COM-12 · 249 | 조회 조건·화면 상태 보존 | 프론트 URL·query cache | 비HTTP: URL 조건 보존·현재 권한 캐시 복원 | F01 | 검색/필터/정렬/page 복원·권한 회수 후 범위 재설정 |
| FR-COM-13 · 250 | 실시간 변경 재조회 | workspace_event_outbox·현재 권한 | `subscribeWorkspaceEvents` | B11/F01 | 커밋 후 무효화·재연결resync·회수차단·logout정리 |
| FR-COM-14 · 251 | 오래된 버전 충돌 안내 | aggregate version | `deleteProduct` | B01/F01 | 낡은version409·reloadUrl·입력 유지 후 최신값 확인 |
| FR-COM-15 · 252 | 변경 요청 멱등성 | idempotency_records·상태 전이 | 모든 관련 API·화면에 공통 적용 | B02/B05/B07/B09/B10 | 동일키/같은입력 재사용·다른입력409·동시 중복 없음 |
