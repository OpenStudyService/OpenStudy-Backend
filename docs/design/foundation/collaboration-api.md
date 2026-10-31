# 업무·채널·시설·문의·공지 API 계약

Backend [#74](https://github.com/OpenStudyService/OpenStudy-Backend/issues/74)의 M7 계약이다. 구현은 업무(#75), 채널·메시지(#76), 업무 카드(#77), 시설(#78), 문의(#79), 공지(#80)에서 이 문서를 기준으로 진행한다. Frontend는 같은 [OpenAPI](openapi.json)(`0.1.7-m7-collaboration`)로 연동한다(#26~#28).

정책 근거는 [ADR-M7-001](../../db/ADR-M7-001.md)(M7-D01~D07)이다. 공통 요청·응답·오류·목록 규칙은 [API 계약](api.md)을 따르고, 고객 조회 범위는 [M4 계약](customer-sales-api.md), 정확 일치 조회는 [M5 계약](seat-usage-api.md)을 따른다.

이 문서의 operation은 모두 설계 계약(`design-target`)이며 아직 서버에 구현되어 있지 않다. DM과 채팅 실시간 전송은 M14 범위다.

## 제공 범위

### 업무

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /tasks` | scope 지점의 유효 소속 | 업무 목록·검색·필터·정렬 | #75 |
| `POST /tasks` | `branchId` 지점의 유효 소속 | 업무 등록. `Idempotency-Key` 필수 | #75 |
| `GET /tasks/summary` | scope 지점의 유효 소속 | 지점별·전체 수치 | #75 |
| `GET /tasks/{id}` | 업무 지점의 유효 소속 | 상세와 체크리스트 | #75 |
| `PATCH /tasks/{id}` | 수정자(M7-D01) | 내용·담당자·기한 수정 | #75 |
| `POST /tasks/{id}/status` | 수정자 | 상태 변경 | #75 |
| `GET /tasks/{id}/events` | 업무 지점의 유효 소속 | 처리 이력 | #75 |
| `POST /tasks/{id}/checklist-items` | 수정자 | 체크 항목 추가 | #75 |
| `PATCH /tasks/{id}/checklist-items/{itemId}` | 수정자 | 체크 항목 수정·완료 | #75 |
| `DELETE /tasks/{id}/checklist-items/{itemId}` | 수정자 | 체크 항목 삭제 | #75 |
| `GET /assignee-candidates` | `branchId` 지점의 유효 소속 | 담당자 후보(업무·시설·문의 공통) | #75 |

### 채널·메시지

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /channels` | 유효 소속 | 내 참여 채널과 읽지 않은 수 | #76 |
| `POST /channels` | 채널 관리자(M7-D04) | 채널 생성. `Idempotency-Key` 필수 | #76 |
| `GET /channels/manageable` | 채널 관리자 | 관리 가능한 채널 목록 | #76 |
| `GET /channels/member-candidates` | 채널 관리자 | 참여자 후보 | #76 |
| `GET /channels/{id}` | 참여자 또는 관리자 | 채널 상세 | #76 |
| `PATCH /channels/{id}` | 채널 관리자 | 이름·설명 수정 | #76 |
| `GET /channels/{id}/members` | 참여자 또는 관리자 | 참여자 목록 | #76 |
| `PUT /channels/{id}/members` | 채널 관리자 | 참여자 전체 변경 | #76 |
| `POST /channels/{id}/read` | 참여자 | 읽음 위치 갱신 | #76 |
| `GET /channels/{id}/messages` | 참여자 | 원문 메시지 과거 조회 | #76 |
| `POST /channels/{id}/messages` | 참여자 | 메시지·답글 전송. `clientMessageKey` 필수 | #76 |
| `GET /channels/{id}/messages/{messageId}` | 참여자 | 메시지 단건 | #76 |
| `GET /channels/{id}/messages/{messageId}/replies` | 참여자 | 스레드 답글 과거 조회 | #76 |
| `POST /channels/{id}/task-cards` | 참여자 + 업무 지점의 유효 소속 | 업무 카드 공유. `clientMessageKey` 필수 | #77 |

### 시설

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /facility-types` | 활성 업무 세션 | 시설 유형 코드표 | #78 |
| `POST /facility-types` | OWNER | 시설 유형 등록. `Idempotency-Key` 필수 | #78 |
| `GET /facilities` | scope 지점의 유효 소속 | 시설 목록과 열린 점검 작업 | #78 |
| `POST /facilities` | `branchId` 지점의 `facilities:write` | 점검 항목 등록. `Idempotency-Key` 필수 | #78 |
| `GET /facilities/summary` | scope 지점의 유효 소속 | 지점별·전체 수치 | #78 |
| `GET /facilities/{id}` | 시설 지점의 유효 소속 | 상세와 마지막 점검 결과 | #78 |
| `PATCH /facilities/{id}` | 시설 지점의 `facilities:write` | 시설·담당자·처리 상태 수정 | #78 |
| `POST /facilities/{id}/inspection/complete` | 작업 담당자 또는 `facilities:write`(M7-D05) | 점검 완료. `Idempotency-Key` 필수 | #78 |

### 고객 문의

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /inquiries` | scope 지점의 유효 소속 | 문의 목록 | #79 |
| `POST /inquiries` | `branchId` 지점의 유효 소속 | 대리 등록. `Idempotency-Key` 필수 | #79 |
| `GET /inquiries/summary` | scope 지점의 유효 소속 | 지점별·전체 수치 | #79 |
| `GET /inquiries/{id}` | 문의 지점의 유효 소속 | 내용과 답변 | #79 |
| `GET /inquiries/{id}/events` | 문의 지점의 유효 소속 | 처리 이력 | #79 |
| `POST /inquiries/{id}/assignee` | 현재 담당자 또는 해당 지점 MANAGER(M7-D02) | 담당자 변경 | #79 |
| `POST /inquiries/{id}/answer` | 문의 지점의 유효 소속 | 답변 저장 | #79 |
| `POST /inquiries/{id}/status` | 문의 지점의 유효 소속 | 접수·처리 중 변경 | #79 |

### 공지

| API | 권한 | 동작 | 구현 |
| --- | --- | --- | --- |
| `GET /notices` | scope 지점의 유효 소속 | 공지 목록 | #80 |
| `POST /notices` | 공지 관리자(M7-D04) | 공지 등록. `Idempotency-Key` 필수 | #80 |
| `GET /notices/{id}` | 볼 수 있는 사용자 | 공지 상세 | #80 |
| `PATCH /notices/{id}` | 공지 관리자 | 공지 수정 | #80 |
| `POST /notices/{id}/status` | 공지 관리자 | 게시·게시 종료 | #80 |

## 공통 규칙

- OWNER는 모든 권한 조건을 만족한다. 이 문서의 "MANAGER"는 해당 지점의 `role=admin`이다. M7-D01·D02·D04는 행위 권한을 따로 요구하지 않으므로 지점 MANAGER 조건에 행위 권한을 더하지 않는다. `facilities:write`·`notices:write`는 명시한 곳에서만 요구한다.
- **수정 충돌**: 수정 API는 조회한 `version`을 받는다. 다르면 409 `VERSION_CONFLICT`이고 `latestVersion`과 `reloadUrl`을 준다. `reloadUrl`은 해당 상세 주소다(`/api/tasks/{id}`, `/api/channels/{id}`, `/api/facilities/{id}`, `/api/inquiries/{id}`, `/api/notices/{id}`). 클라이언트는 입력을 유지하고 이 주소로 최신값을 확인한 뒤 다시 저장한다.
- **범위**: 범위 밖 자원은 목록·total에 없고 상세는 404다. scope에 없는 지점을 지정하면 403이다.
- **요청 키**: 새 행을 만드는 명령과 점검 완료는 `Idempotency-Key`가 필수다. 메시지는 `clientMessageKey`를 쓴다. `version`으로 막히는 나머지 수정은 받지 않는다.
- `canEdit` 같은 응답 필드는 화면 안내용이다. 서버는 쓰기마다 다시 검사한다.
- **운영 중지 지점**: 이 문서의 기능은 내부 협업이라 FR-BR-08의 신규 운영 거래가 아니다. 지점이 운영 중지여도 조회·등록·수정을 모두 허용한다.
- 업무·문의·시설의 담당자는 해당 지점의 활성 계정·유효 소속 직원만 지정할 수 있다. 아니면 422 `INVALID_ASSIGNEE`다. `work_tasks`의 담당자가 `branch_memberships`를 참조하므로 소속 행이 없는 OWNER는 담당자가 될 수 없다. 후보는 `GET /assignee-candidates`로 얻는다.
- 변경 알림(`GET /workspace/events`)의 `resource`에 `tasks`·`facilities`·`inquiries`·`notices`를 추가했다. 본문 없이 `invalidate`만 보낸다. 채팅 실시간 전송은 M14다.

### 추가 오류 코드

| HTTP | code | 의미 |
| --- | --- | --- |
| 422 | `INVALID_ASSIGNEE` | 해당 지점의 활성 직원이 아닌 담당자 |
| 422 | `INVALID_CHANNEL_MEMBER` | 채널 참여 자격이 없는 직원 |
| 422 | `INVALID_PARENT_MESSAGE` | 답글에 답글을 달려는 요청 |
| 409 | `DUPLICATE_FACILITY_TYPE` | 같은 이름의 시설 유형 |

## 업무(FR-TASK-01~06·08, M7-D01)

- **등록**: 지점 직원 누구나 등록한다. 등록자는 세션 사용자이며 입력으로 받지 않는다. 분류(`category`)는 1~60자 자유 입력이다.
- **수정자**: 업무 지점의 유효 소속이면서 등록자·담당자·해당 지점 MANAGER 중 하나다. 소속이 회수되면 등록자나 담당자여도 수정할 수 없다. 조회는 가능한데 수정자가 아니면 403이다.
- **수정**: `PATCH`는 제목·내용·담당자·기한·우선순위·분류를 받는다. `branchId`·`status`·`checklist`를 보내면 422 `IMMUTABLE_FIELD`다. 담당자를 실제로 바꿀 때만 새 담당자의 자격을 검사한다.
- **삭제 API는 없다.** SRS에 업무 삭제 요구사항이 없고, `task_events`와 `messages.related_task_id`가 업무를 `RESTRICT`로 참조해 처리 이력·공유 카드를 보존한다. 잘못 등록한 업무는 완료 처리한다. 삭제는 체크 항목에만 있다.

### 상태 전이

| 현재 | 허용 |
| --- | --- |
| 할 일 | 진행 중, 완료 |
| 진행 중 | 할 일, 완료 |
| 완료 | 할 일(다시 열기) |

- 그 외는 409 `INVALID_STATE`다. 같은 상태 요청은 현재 값을 돌려주며 이력을 만들지 않는다.
- 완료가 되면 `completedAt`을 기록하고, 완료에서 벗어나면 지운다. 완료 상태로 등록하면 등록 시각을 기록한다.

### 체크리스트

체크 항목에는 별도 version이 없어 업무 version을 쓴다. 항목을 바꾸면 업무 version이 오르고, 응답은 변경된 업무 전체다. 다른 사용자의 항목 변경도 409가 난다. 업무당 100개까지다. `DELETE`는 본문이 없으므로 `version`을 query로 받는다.

### 처리 이력

변경 하나마다 `task_events` 한 행을 남긴다. 한 번의 `PATCH`가 여러 묶음을 바꾸면 묶음마다 남긴다.

| type | 대상 |
| --- | --- |
| `CREATED` | 등록 |
| `CONTENT_CHANGED` | 제목·내용·우선순위·분류 |
| `ASSIGNEE_CHANGED` | 담당자 |
| `DUE_CHANGED` | 기한 |
| `STATUS_CHANGED` | 상태 |
| `CHECKLIST_ADDED`, `CHECKLIST_CHANGED`, `CHECKLIST_REMOVED` | 체크 항목 |

응답은 처리 시각·작업자·변경 전후 값을 담는다. 탈퇴 등으로 작업자가 없으면 `actor`는 null이다.

### 목록과 수치

- 필터: `q`(제목·내용), `status`, `assigneeId`, `mine`(담당자가 나), `dueFrom`, `dueTo`, `overdue`. `sort`: `dueAt`, `priority`, `createdAt`(기본), `updatedAt`.
- 기한 필터는 `[dueFrom, dueTo)`이고 기한 없는 업무는 제외한다. `dueAt` 정렬에서 기한 없는 업무는 방향과 무관하게 마지막이다.
- `overdue`는 `dueAt < 현재 시각`이고 완료가 아닌 업무다.
- `GET /tasks/summary`는 `status`를 뺀 같은 필터를 받는다. `todo`·`inProgress`·`done`은 같은 조건에 `status`를, `overdue`는 `overdue=true`를 더한 `GET /tasks`의 total과 같다. 지점별 수치와 합계를 같은 기준 시점(`asOf`)으로 계산한다. M9 홈도 이 기준을 재사용한다.

## 채널(FR-CHAT-01·09, M7-D04·D07)

- 전체 채널은 OWNER만, 지점 채널은 해당 지점 MANAGER 이상이 만들고 수정한다. 채널 관리자는 참여자가 아니어도 채널 정보와 참여자를 볼 수 있지만 메시지는 참여자만 읽는다.
- **참여 자격**: 지점 채널은 그 지점의 유효 소속 활성 직원, 전체 채널은 소속이 하나 이상 유효한 활성 직원이다. 활성 OWNER는 모든 지점의 유효 관리자이므로 자격이 있다. 자격이 없으면 422 `INVALID_CHANNEL_MEMBER`다.
- 생성자는 자동으로 참여한다. `PUT /channels/{id}/members`는 최종 참여자 전체를 받는다. 빠진 직원은 `left_at`을 기록하고 즉시 접근이 끝난다. 다시 넣으면 `left_at`을 지우고 이전 메시지도 다시 볼 수 있다.
- **참여 자동 종료(M7-D07)**: 소속이 회수되거나 계정이 비활성·이용 제한되면 관련 채널의 참여를 같은 트랜잭션에서 종료한다. 조회·전송도 매 요청 현재 자격을 다시 확인한다.
- 채널 삭제 API는 없다. 요구사항이 없고 메시지가 채널을 참조한다.
- `GET /channels`는 페이지가 없다. 참여 중인 전체 채널과 scope 지점 채널을 이름순으로 준다. DM은 어떤 M7 API에도 나오지 않는다.

### 스키마 변경(#76)

V1 `conversations`에는 FR-CHAT-09의 설명과 이슈 인수 조건의 버전을 담을 열이 없다. #76이 `description varchar(500) NULL`, `version bigint unsigned NOT NULL DEFAULT 0`을 추가하는 마이그레이션을 포함한다. 버전 번호는 병합 직전에 정한다. `description`·`version`·참여자 변경이 같은 채널 version을 쓴다.

## 메시지(FR-CHAT-02·04~07)

- 본문은 일반 텍스트 1~4000자이고 trim 후 비면 422다. 서버는 변환하지 않고 그대로 저장·반환하며, 화면은 HTML로 해석하지 않는다.
- 참여자가 아니면 채널·메시지·답글 모두 404다. 메시지 ID를 직접 요청해도 같다. 관리자도 예외가 아니다.
- 답글(`parentId`)은 같은 채널의 원문에만 단다. 다른 채널의 ID는 404, 답글에 다시 답글을 달면 422 `INVALID_PARENT_MESSAGE`다.
- 메시지 수정·삭제 API는 없다. 요구사항이 없다.

### 클라이언트 메시지 키

`clientMessageKey`(1~100자)는 필수다. 클라이언트가 전송 시도마다가 아니라 **작성한 메시지마다** 만들고, 재시도에 같은 값을 쓴다. 범위는 채널·발신자별이다(DB `uk_messages_1`).

| 요청 | 결과 |
| --- | --- |
| 새 키 | 201, 새 메시지 |
| 같은 키·같은 입력(본문·답글 대상·업무) | 200, 원래 메시지. 새 메시지 없음 |
| 같은 키·다른 입력 | 409 `IDEMPOTENCY_CONFLICT` |

동시 재전송은 유일 제약으로 하나만 저장되고 나머지는 저장된 행을 읽어 같은 규칙으로 응답한다. 이 경로는 `Idempotency-Key`를 쓰지 않는다.

### 커서와 페이지 경계

메시지 순서의 기준은 메시지 ID다. 서버가 증가 순서로 부여하며, 답글과 원문이 같은 순서를 공유한다.

- `GET /channels/{id}/messages`는 원문만, `.../replies`는 해당 원문의 답글만 준다.
- 요청: `before`(선택), `limit`(1~100, 기본 50).
- 서버는 `id < before`인 행을 `id` 내림차순으로 `limit + 1`개 읽는다. 초과분이 있으면 `hasMore=true`다. 응답 `items`는 뒤집어 **오래된 메시지부터** 준다.
- `nextCursor`는 `items`의 첫 항목(가장 오래된 메시지)의 ID이고, `hasMore=false`면 null이다.
- 경계 조건이 `id < before`로 엄격하고 ID가 바뀌지 않으므로, 새 메시지가 도착해도 **페이지 사이에 중복·누락이 없다.** offset을 쓰지 않는 이유다.
- `before`는 불투명 값으로 다룬다. 형식이 잘못되면 422다. 다른 채널의 값이어도 경계로만 쓰이며 그 메시지의 내용을 노출하지 않는다.
- 첫 조회 이후 도착한 메시지는 M7에서 첫 페이지를 다시 조회해 얻는다. 실시간 전송은 M14다.

### 읽음(FR-CHAT-06)

- 읽음 위치는 **채널·직원당 하나**(`last_read_message_id`)다. V1에 스레드별 저장 위치가 없고, 스레드별 읽음은 범위 밖이다.
- `POST /channels/{id}/read`는 `messageId`(같은 채널의 메시지, 원문·답글 모두)를 받는다. 다른 채널의 ID는 404다.
- 앞으로만 이동한다. 현재 위치 이하의 값은 위치를 바꾸지 않고 현재 값을 200으로 준다.
- 요청자의 위치만 바꾼다. 다른 사용자의 위치를 지정하는 입력이 없다.
- `unreadCount`는 이 위치보다 뒤이고 본인이 보내지 않은 메시지 수이며 답글을 포함한다.
- 채널 전체 위치 하나이므로, 원문 목록 하단을 읽으면 그보다 앞선 답글도 읽은 것으로 계산된다. 화면이 보이고 하단 메시지를 확인했을 때만 호출하는 것은 클라이언트 책임이다.

## 업무 카드(FR-TASK-07·08, M7-D07)

- `POST /channels/{id}/task-cards`는 요청자가 참여한 채널에, 요청자가 접근할 수 있는 업무만 공유한다. 업무가 없거나 접근할 수 없으면 404다.
- 메시지는 `kind=업무 카드`, `related_task_id`로 저장하고 공유 시점의 상태를 복사하지 않는다. 선택 `text`를 함께 쓸 수 있다.
- 응답의 `task`는 **조회 시점의 요청자 기준**으로 만든다.

| 요청자 | `task` |
| --- | --- |
| 업무 지점의 유효 소속 | `accessible=true`, 현재 제목·상태·우선순위·담당자·기한 |
| 그 외 | `accessible=false`, 나머지 필드 모두 null. 업무 ID도 담지 않음 |

- 채널 참여가 업무 접근 권한을 넓히지 않는다. 카드에서 상세를 열 때는 `GET /tasks/{id}`와 `GET /tasks/{id}/events`를 쓰므로 업무 화면과 같은 권한·이력이 적용된다.

## 시설(FR-FAC-01~05, M7-D03·D05·D06)

- **두 상태를 분리한다.** `status`는 시설 상태(정상·점검 필요·고장·사용 중지), `openWork.status`는 처리 상태(대기·진행 중)다.
- 담당자와 처리 상태의 원천은 열린 점검 작업(`facility_works`, `kind=INSPECTION`, 상태 `OPEN`·`IN_PROGRESS`)이다. 시설마다 항상 하나다. DB 유일 제약이 없으므로 서비스가 시설 행을 잠그고 보장한다. `대기`는 `OPEN`, `진행 중`은 `IN_PROGRESS`다.
- 청소·수리 작업과 작업 `CANCELLED` 상태는 이 계약에 나오지 않는다.

### 등록과 수정

- 등록은 시설과 첫 점검 작업을 한 트랜잭션으로 만든다. `inspectionIntervalDays`를 생략하면 유형의 기본 주기를 쓴다.
- 등록 시 `lastInspectedOn`을 줄 수 있다. 오늘(Asia/Seoul) 이후는 422다. `lastInspectionResult`까지 주면 완료된 점검 작업으로 함께 저장한다(FR-FAC-02의 선택 입력).
- `PATCH`는 시설 정보, `assigneeId`, `workStatus`를 함께 수정한다. `branchId`·`lastInspectedOn`은 받지 않는다(422 `IMMUTABLE_FIELD`). 시설에 변경 이력 표가 없으므로 전후 값을 `audit_events`에 남긴다.
- 시설 유형은 지점 구분이 없는 전역 코드표다. V1에 기본 데이터가 없어 `POST /facility-types`(OWNER)를 둔다. 상품 분류와 같은 OWNER 전역 명령이다. 수정·삭제는 없다.

### 점검 완료

`POST /facilities/{id}/inspection/complete`. `version`, `result`(필수, 1~5000자), `completedOn`(실제 완료일), `facilityStatus`를 받는다.

- 권한은 그 작업의 담당자 또는 `facilities:write` 보유자다. 둘 다 시설 지점의 유효 소속이어야 한다.
- `completedOn`이 오늘(Asia/Seoul)보다 뒤이거나 현재 `lastInspectedOn`보다 앞이면 422다.
- 한 트랜잭션에서 다음을 처리한다: 작업을 `DONE`으로 닫고 결과·완료자·완료 시각을 저장 → 시설의 상태·`last_inspected_on` 갱신 → 같은 담당자로 새 `OPEN` 작업 생성 → 감사 기록.

### 다음 점검일과 수치

- `nextInspectionOn` = `lastInspectedOn` + `inspectionIntervalDays`. `lastInspectedOn`이 없으면 등록일(Asia/Seoul)이다(M7-D06). 저장하지 않고 조회할 때 계산한다.
- `inspectionDue`(점검 대상) = `nextInspectionOn ≤ 오늘(Asia/Seoul)`이고 시설 상태가 사용 중지가 아닌 시설이다. 사용 중지 시설은 점검을 요구하지 않는다.
- `GET /facilities/summary`는 `status`를 뺀 같은 필터를 받는다. 상태별 수치는 같은 조건에 `status`를, `inspectionDue`는 `inspectionDue=true`를 더한 `GET /facilities`의 total과 같다. M9 홈도 이 기준을 재사용한다.
- 목록 `sort`는 `nextInspectionOn`(기본, 오름차순), `name`, `createdAt`이다.

## 고객 문의(FR-INQ-01~04, M7-D02)

- 지점 직원 누구나 대리 등록하고 답변한다. 문의가 속한 지점의 유효 소속이 기준이다.
- 응답의 고객은 `CustomerSummary`(ID·회원번호·이름)만 담는다. 연락처·이메일·메모는 담지 않는다.
- **대상 고객**: `customerId`는 다음 중 하나여야 하고 아니면 404다.
  - 요청자의 고객 조회 범위(M4-D05) 안의 고객
  - 같은 지점에서 요청자가 `POST /customer-lookups/check-in`으로 찾은 고객(`customer_service_interactions` 기록으로 확인)

  서버는 "조회로 찾았다"는 클라이언트 주장을 믿지 않고 이 기록을 확인한다. 임의의 고객 ID로 이름을 알아내는 것을 막기 위해서다.
- 문의를 등록해도 고객의 일반 조회 범위는 넓어지지 않는다.
- **담당자 변경**: 현재 담당자 또는 해당 지점 MANAGER 이상이 한다. 담당자가 없으면 MANAGER 이상만 지정할 수 있다. null은 담당자 해제다.
- **답변**: 답변·답변자·시각을 저장하고 상태를 `답변 완료`로 바꾼다. 다시 답변하면 답변을 교체하고 이전 답변은 처리 이력의 `message`에 남긴다.
- **상태**: `POST /inquiries/{id}/status`는 `접수`·`처리 중`만 받는다. `답변 완료`는 답변으로만 된다. 답변 완료에서 처리 중으로 되돌려도 답변은 유지한다.
- **이력**: 상태·담당자·답변 변경마다 `inquiry_events`에 남긴다. 담당자 변경은 상태가 그대로이므로 `oldStatus`와 `newStatus`가 같고 `message`에 변경 내용을 쓴다.
- 고객 직접 접수 채널은 범위 밖이다. 문의 삭제·제목 수정 API는 없다.

### 목록과 수치

- 필터: `q`(제목·고객 이름), `status`, `assigneeId`, `customerId`. `sort`: `createdAt`(기본), `updatedAt`.
- `unresolved`(미처리) = 접수 + 처리 중. `GET /inquiries/summary`는 `status`를 뺀 같은 필터를 받고, 각 수치는 같은 조건에 `status`를 더한 `GET /inquiries`의 total과 같다. M9 홈도 이 기준을 재사용한다.

## 공지(FR-NOTI-01~03, M7-D04)

- **관리 권한**: 전체 공지는 OWNER만, 지점 공지는 대상 지점 **모두**에 `notices:write`가 있는 사용자가 등록·수정·상태 변경한다.
- `PATCH`로 대상을 바꾸면 현재 대상과 변경 후 대상 모두에 권한이 있어야 한다.
- 대상: `전체`는 `branchIds`를 보낼 수 없고, `선택 지점`은 1개 이상 필수다.
- 응답의 `branchIds`는 요청자에게 보이는 지점만 담는다. 다른 지점 이름·ID를 드러내지 않는다.

### 공개 범위

| 사용자 | 보이는 공지 |
| --- | --- |
| 일반 직원 | 게시 중이고 전체 대상이거나 scope 지점이 대상인 공지 |
| 공지 관리자 | 위에 더해, 관리할 수 있는 임시 저장·게시 종료 공지 |
| OWNER | 모든 공지 |

볼 수 없는 공지의 상세는 404다. 일반 직원이 `status=게시 종료`로 조회하면 빈 목록이다.

### 상태와 정렬

- 전이: 임시 저장 → 게시 중, 게시 중 → 게시 종료, 게시 종료 → 게시 중. 그 외는 409 `INVALID_STATE`다. 같은 상태는 현재 값을 돌려준다.
- 게시할 때 `publishedAt`을 기록한다. 다시 게시하면 새 시각으로 바꾼다.
- 정렬: **게시 상태·지점 제한을 먼저 적용한 뒤** `pinned` → `publishedAt`(임시 저장은 `createdAt`) → `id` 내림차순으로 고정한다. 게시 종료된 고정 공지가 일반 직원 목록 위에 오지 않는다.
- 공지 읽음 확인과 공지 삭제 API는 없다.

## 트랜잭션과 구현 메모

- 쓰기는 대상 행을 잠근 뒤 권한·version·상태를 다시 확인한다.
- 변경과 이력·감사·알림 outbox는 같은 트랜잭션이다. 실패하면 함께 롤백한다.
- 목록 요청마다 현재 유효 소속으로 지점 범위를 계산한다. 세션에 저장된 과거 권한을 쓰지 않는다.
- 집계와 목록은 필터를 같은 코드로 만든다. 수치와 목록이 어긋나지 않게 하기 위해서다.
