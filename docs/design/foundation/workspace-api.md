# 기반 선택 데이터와 명시적 조회

> **2026-11-07 SSE 제거**: [ADR-COM-001](../../db/ADR-COM-001.md)에 따라 `GET /workspace/events`, workspace Outbox 기록·발행, 전달 전용 세션 검사와 스케줄러를 제거했다([Backend #170](https://github.com/OpenStudyService/OpenStudy-Backend/issues/170)). 이전 SSE 계약(resync·invalidate·authorization.changed·heartbeat)은 당시 구현의 근거로 git 이력과 [#17](https://github.com/OpenStudyService/OpenStudy-Backend/issues/17)에 남는다. Frontend 전환은 [Frontend #77](https://github.com/OpenStudyService/OpenStudy-Frontend/issues/77)에서 진행한다.

현재 계약은 OpenAPI의 `GET /lookups`다. 서버 접두사는 `/api`이며 WORKSPACE 세션의 현재 ACTIVE 계정만 허용한다.

## 선택 데이터

`GET /api/lookups?scope=all 또는 지점 ID`는 현재 허용 지점 범위를 다시 검사한다. `branches`, `employees`, `products`, `permissionDefinitions`, `permissionPresets`, `productCategories`, `asOf`, `snapshotVersion`을 반환한다.

- 지점은 구조화 영업시간·상태가 있는 현재 지점 DTO다. 허용된 중지 지점은 이력·설정 조회를 위해 포함하며 신규 거래 허용을 뜻하지 않는다.
- 직원 선택 데이터는 정확히 `{id,name}`이다. 허용 지점의 활성 업무 대상과 활성 OWNER만 반환하고 이메일·사진·계정 상태·소속·권한·승인 대기자를 노출하지 않는다. 직원 관리 DTO를 대신하지 않는다.
- 프리셋은 현재 요청 범위에서 관리 권한이 있는 사용자에게만 제공한다. 일반 직원에게는 빈 배열이다. 권한 사전은 기존 공개 서비스를 재사용한다.
- 상품·분류 API는 아직 구현되지 않았으므로 해당 배열은 빈 배열이다. 기존 초안·시연 DB 자료를 운영 선택 데이터로 승격하지 않는다. 다른 업무 영역은 이 계약에 추가하지 않는다.
- scope=all은 허용 지점의 합집합이다. 미배정 계정의 지점·직원 선택지는 정상 빈 배열이며, 인증/권한 실패를 빈 배열로 바꾸지 않는다.

과거 로컬 샘플 선택 데이터는 충돌을 피하기 위해 `/api/local-preview/lookups`로 옮겼다. local 프로필·기존 preview 옵션에서만 제공한다. 운영 `/api/lookups`는 local 프로필에서도 실제 최소 계약을 따른다.

## 명시적 조회와 요청 시 인가

- 서버는 변경 알림을 보내지 않는다. 화면은 메뉴 진입·같은 메뉴 재클릭·새로고침·조건 변경처럼 사용자가 요청할 때 다시 읽는다(COM-D01·D02).
- 다른 세션에서 바뀐 계정·소속·권한은 **다음 API 요청**에서 현재 서버 상태로 판단한다. 로그아웃·계정 제한·유휴/최대 만료는 401, 회수된 지점 범위는 403이다(COM-D05). 열린 화면을 서버가 먼저 정리하는 보장은 없다.
- 오래된 화면에서 보낸 명령도 서버가 최신 상태·권한·버전·중복·금액을 다시 검증한다(COM-D07).
- `/api/me`, `/api/lookups`, `/api/invitations/approval-status`, `/api/auth/csrf` 조회는 세션의 유효 활동 시각을 늘리지 않는다. 유휴 60분·최대 12시간 정책은 바뀌지 않았다.

## 남아 있는 스키마

`workspace_event_outbox` 테이블은 지우지 않았다. 새 행을 쓰는 코드는 없고, 기존 행은 읽지도 지우지도 않는다. 테이블 삭제 여부는 사용처·이력 보존과 함께 별도로 판단하며, 지운다면 새 마이그레이션으로 처리한다.

Frontend는 이 JSON으로 기존 `npm run api:foundation` 생성 경로를 실행해 `Lookups`를 동기화한다. `WorkspaceEvent` 타입은 더 이상 생성되지 않는다. 최소 직원 목록에 관리용 `Employee` 필드가 있다고 가정하지 않아야 한다.
