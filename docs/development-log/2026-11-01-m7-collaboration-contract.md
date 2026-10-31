# 2026-11-01 M7 업무·채널·시설·문의·공지 API 계약

관련: Issue [#74](https://github.com/OpenStudyService/OpenStudy-Backend/issues/74), [계약 문서](../design/foundation/collaboration-api.md), [ADR-M7-001](../db/ADR-M7-001.md), [M5 계약 기록](2026-10-29-m5-seat-usage-contract.md).

## 1. 배경 / 문제

인프라 팀원과 업무 관리 메뉴를 실제 서버에서 테스트하려 했지만, 업무 화면은 로컬 샘플 데이터로만 동작하고 저장은 501로 끝났다. Backend `modules/task`가 비어 있기 때문이다. 업무 구현(#75)과 Frontend 연결(#26)의 선행 조건이 이 계약이다.

M7 정책은 ADR-M7-001로 확정됐지만 API 계약이 없었다. M4·M5와 같이 계약을 먼저 두어 #75~#80과 Frontend #26~#28이 같은 기준으로 진행하게 했다.

## 2. 검토한 선택지

- **업무 삭제**
  - 삭제 API를 둔다: 사용자가 테스트 범위를 "조회/등록/수정/삭제"로 말한 적이 있다. 하지만 SRS에 업무 삭제 요구사항이 없고, `task_events`·`messages.related_task_id`가 `RESTRICT`로 참조해 물리 삭제하면 이력과 공유 카드가 끊긴다. 소프트 삭제는 열 추가가 필요하다.
  - 삭제 없음: 상품의 "물리 삭제 미제공" 정책과 같은 방향이다. 이쪽을 택했고 사용자가 확인했다.
- **채널의 설명·버전**: FR-CHAT-09는 설명을, 이슈 인수 조건은 수정 버전을 요구하는데 `conversations`에 해당 열이 없다. ADR은 "스키마 영향 없음"이라고 적었다.
  - 설명을 빼고 버전 없이 마지막 저장 우선으로 둔다: 요구사항과 인수 조건을 어긴다.
  - 열을 추가한다: 이쪽을 택했다. 이슈 범위가 구현 제외이므로 마이그레이션은 #76에서 한다. ADR에는 변경 이력을 추가했다.
- **시설 유형**: V1 `facility_types`에 기본 데이터도 관리 API도 없어 시설을 등록할 수 없다.
  - 마이그레이션에 기본 유형을 넣는다: 유형 이름을 근거 없이 정해야 한다.
  - OWNER 전역 등록 API를 둔다: 상품 분류와 같은 방식이다. 이쪽을 택했다.
- **읽음 위치**: Frontend 코드 주석은 #74가 스레드별 읽음을 정해 주길 기대했다. 저장 위치는 채널·직원당 하나(`last_read_message_id`)뿐이다.
  - 스레드별 표를 추가한다: 요구사항(FR-CHAT-06)에 없다.
  - 채널 단위 하나로 둔다: 이쪽을 택했다. 원문 하단을 읽으면 앞선 답글도 읽음으로 계산되는 한계를 문서에 적었다.
- **메시지 재전송**: 공통 `Idempotency-Key`를 쓸 수도 있지만 `uk_messages_1`이 이미 메시지 키를 보장한다. 이슈도 클라이언트 메시지 키를 요구하므로 `clientMessageKey`로 통일했다.
- **메시지 커서**: 새 메시지가 계속 도착해도 경계가 밀리지 않아야 해서 offset 대신 `id < before`를 쓴다.
- **문의 대상 고객의 서버 검증**: 정확 일치 조회로 찾은 고객은 일반 조회 범위 밖일 수 있다.
  - 임의의 `customerId`를 받는다: ID를 넣어 이름을 알아낼 수 있다.
  - `customer_service_interactions` 기록을 확인한다: 이쪽을 택했다.
- **시설의 점검 대상 기준**: ADR은 다음 점검일 계산만 정했다. 사용 중지 시설을 점검 대상에서 빼는 쪽으로 정했다. 리뷰에서 조정할 수 있다.

## 3. 결정 및 이유

- operation 46개를 정의했다. 업무 11, 채널·메시지 14, 시설 8, 문의 8, 공지 5다.
- 권한은 M7-D01~D05·D07을 그대로 옮겼다. D01·D02·D04의 "MANAGER 이상"은 행위 권한을 명시하지 않았으므로 `role=admin`만 요구한다.
- 수치 API는 목록과 같은 필터를 받고, 각 수치가 같은 조건의 목록 total과 같다고 정의했다.
- 지점 운영 중지는 협업 기능을 막지 않는다. FR-BR-08의 신규 운영 거래가 아니라고 판단했다.
- 업무 상태 전이의 "완료 → 할 일"은 Frontend 데모의 다시 열기와 맞췄다. "진행 중 → 할 일"은 실수 복구를 위해 추가했다.

## 4. 구현 내용

- `docs/design/foundation/collaboration-api.md` 신규
- `docs/design/foundation/openapi.json`: 버전 `0.1.7-m7-collaboration`, operation 46개, 태그 5개 추가, `WorkspaceEvent.resource`에 4개 값 추가
- `src/test/java/com/openstudy/architecture/CollaborationContractTests.java` 신규, 계약 테스트 11개
- `docs/db/ADR-M7-001.md` 변경 이력, `docs/design/foundation/README.md` 안내 추가

서버 코드와 마이그레이션은 바꾸지 않았다.

## 5. 검증 결과

- `swagger-cli validate docs/design/foundation/openapi.json`: 통과
- 스크립트 대조: 계약 문서 표와 OpenAPI의 신규 operation이 46개로 일치(누락·초과 없음), 사용 오류 코드가 모두 문서에 있음
- 기존 operation 변경 없음. 기존 schema 변경은 `WorkspaceEvent`의 enum 추가뿐이다.
- `./gradlew test --tests 'com.openstudy.architecture.*'`: 통과, 신규 계약 테스트 포함

**실행하지 않은 것**

- **전체 테스트를 실행하지 않았다.** 로컬 `.env`에 실제 Gmail SMTP와 `openstudy.invitation-mail.enabled=true`가 들어 있어, 통합 테스트가 이 파일을 읽으면 테스트 초대 메일이 실제로 나갈 수 있다. 이번 변경은 문서·OpenAPI·아키텍처 테스트뿐이라 실행하지 않았다.
- `scripts/validate_foundation_design.py`는 M4 이전 가정 때문에 `main`에서도 실패한다고 M5 기록에 남아 있어 실행하지 않았다.
- Frontend 측 계약 검토는 하지 않았다. 생성 타입 갱신은 #26~#28에서 한다.

## 6. 남은 작업 / 보류 사항

- 업무 삭제 API 없음은 2026-11-01에 사용자가 확인했다.
- #76: `conversations` 마이그레이션. 버전 번호는 병합 직전에 정한다.
- #79: 정확 일치 조회의 `reason` 문자열이 입실 기준이다. 문의 등록 목적으로 찾은 경우에도 같은 문자열이 남는다. 조회 진입점을 나눌지는 #79에서 판단한다.
- Frontend 데모 경로(`/chat/*`, `/tasks/{id}/{action}`)와 입력 모양이 이 계약과 다르다. #26·#27에서 계약에 맞춘다.
- `.env`의 메일 설정이 켜진 상태로 통합 테스트를 돌리지 않도록 별도 조치가 필요하다.
