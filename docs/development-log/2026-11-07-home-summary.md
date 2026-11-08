# 2026-11-07 운영 홈 요약 API (#90)

## 1. 배경 / 문제

- 사용자가 "메뉴별로 확실하게 완성"하는 방향을 정하고, 업무 메뉴별 남은 작업량을 검토해 빠르게 끝낼 수 있는 작업을 고르도록 했다.
- 메뉴별로 남은 작업을 확인했다(2026-11-07 main 기준).
  - **대시보드**: Frontend 홈은 `GET /api/home`을 부른다. 이 경로는 `LocalPreviewController`(local 프로필 전용)에만 있다. 실제 서버에서는 대시보드가 동작하지 않는다. Backend #90 + Frontend #31이 남았다.
  - **매출 분석**: Backend #87·#88은 병합됐다. Frontend #30(판매량·순위·추세 화면 연결 등)이 남았다. #89는 성능 측정이라 메뉴 완성 조건이 아니다.
  - **업무·회원·상품·결제·예약·공간·시설·문의·공지**: M4~M7 마일스톤이 모두 닫혀 있다. 남은 것은 M13(지점 휴무·판매 한도·고객 병합·마감·고급 필터) 같은 확장이다.
  - **근무 관리**: M8은 닫혔다. M12(근태 정정·휴가·대체근무)가 Backend 4개·Frontend 2개 이슈로 남아 작업량이 크다.
  - **직원·설정**: M14(비밀번호·프로필 사진 S3·감사 로그)가 남아 작업량이 크다.
  - **업무 AI**: 대화 기록(#172)과 결과 미저장(#173/#176)이 병합됐다. #168 마무리 점검, #167·#70(팀 채팅 비활성화), M10 이슈 정리가 남았다.
- 이 중 대시보드가 Backend 이슈 하나(#90)와 Frontend 이슈 하나(#31)로 메뉴가 완성된다. 정책(ADR-M9-001 M9-D08·D09)과 계약(`aggregation-api.md`)이 모두 있어 결정할 것이 적다. 그래서 #90을 골랐다.

## 2. 검토한 선택지

- **"오늘 완료한 업무"를 원본으로 재현하는 방법**(계약 문서가 #90에 결정을 남김)
  - `GET /tasks`에 `completedFrom`·`completedTo` 필터 추가: 원본 API로 바로 재현되지만 M7 operation과 계약이 바뀐다.
  - 홈 전용 수치로 두기: M7은 그대로이고, 재현 방법은 `sources.clientFilter`에 적고 DB 통합 테스트로 대조한다.
- **근무 "오늘과 겹침" 조건**
  - `GET /shifts`에 겹침 필터 추가
  - 원본 목록(`from=어제`, `to=오늘`) + `clientFilter`로 두기
- **카드 목록(items)을 만드는 방법**
  - 홈 전용 질의를 새로 쓰기
  - 원본 서비스의 목록을 상태별로 불러 합치기: "할 일"·"진행 중"(업무), "접수"·"처리 중"(문의) 각 5개를 받아 정렬 후 5개만 남긴다.

## 3. 결정 및 이유

- 두 재현 문제 모두 **기존 M7·M8 operation을 바꾸지 않는** 쪽을 골랐다.
  - 홈 하나 때문에 다른 메뉴의 계약과 Frontend 생성 타입을 바꾸면 범위가 커진다.
  - 계약 문서도 둘 중 하나를 고르라고만 했고, 원본 변경을 요구하지 않았다.
- 목록은 원본 서비스를 그대로 호출해 만든다. 각 상태의 상위 5개를 합치면 전체 상위 5개와 같다. 이렇게 하면 원본 권한·범위를 넘을 수 없다.
- 새 질의는 두 개뿐이다.
  - `TaskSearchRepository.countCompleted`: 목록과 같은 WHERE 절에 완료 시각 범위만 더한다.
  - `ShiftRepository.colleagueShiftIds`: 같은 지점 동료 근무(2026-11-05 사용자 결정). 근무 ID는 모듈 밖으로 나가지 않는다.
- 결정은 이 문서와 `aggregation-api.md`의 "남은 작업"에 남겼다. 사용자 확인이 필요한 정책 변경은 아니라고 판단했다. 두 방식 모두 계약 문서가 제시한 선택지다.

## 4. 구현 내용

- `GET /api/home/summary?scope=&mine=&assigneeId=`(`HomeController`, `HomeSummaryService`, `HomeSummary` DTO)
  - **좌석**: `SeatService.occupancy`의 지점별 값을 합하고 점유율을 `Ratio`로 바꾼다. 좌석이 없으면 "해당 없음"이다.
  - **업무**
    - `today`는 요청 조건, `mine`은 항상 본인 담당이다.
    - open·overdue는 `TaskService.summary`(`dueTo=dayEnd`)로 계산한다. completedToday는 `TaskService.completedBetween`으로 센다.
    - 완료율은 소수 첫째 자리 HALF_UP이고, 분모가 0이면 `notApplicable`이다.
    - 기본값: OWNER는 범위 전체, 그 외(MANAGER 포함)는 본인 담당이다. `mine=true`와 `assigneeId`를 함께 보내면 422다.
  - **문의**: `InquiryService.summary`의 unresolved·received·inProgress를 쓴다. 목록에 고객 정보는 없다.
  - **점검 필요**: `FacilityService.summary`의 inspectionDue와 `inspectionDue=true` 목록을 쓴다.
  - **근무**
    - `ShiftService.list`(`from=어제`, `to=오늘`)를 끝까지 페이지로 읽고 겹침 조건으로 거른다. M8-D02 범위 그대로다.
    - `colleagues`는 요청자가 활성 소속인 scope 지점의 동료 근무다. 이름·시작·종료 시각만 담는다.
  - **공지**: `NoticeService.list`(`게시 중`, 5개)를 쓴다.
  - 각 카드에 `link`(이동 화면·지점·대상 ID·목록 조건)와 `source`(재현 operation·조건·clientFilter)를 담는다.
- **메시지 카드 제거**(ADR-M10-002 M10-D11): `openapi.json`에서 `messages`, `HomeMessageCard`, `HomeChannelItem`, `link.screen=channel`을 지웠다. operation을 `implemented`로 바꿨다.
- 계약 테스트(`AggregationContractTests`)를 구현 완료와 메시지 카드 제거에 맞췄다.
- `LocalPreviewController`의 `GET /api/home` 샘플은 그대로 뒀다. Frontend #31이 `/api/home/summary`로 바꾼 뒤 정리한다.

## 5. 검증 결과

- 신규 `HomeSummaryIntegrationTests`(실제 HTTP + MySQL, 시계를 2026-11-05 10:00 Asia/Seoul로 고정): 5개 통과
  - 계약 예시 T1~T6에서 total 3, open 2, overdue 1, completedToday 1, 33.3%가 나온다. 원본 `GET /tasks`·`/tasks/summary`와 수치가 같다.
  - 역할별 기본 조건과 assigneeId·mine=false 전환이 동작한다. `mine=true`+assigneeId와 잘못된 mine은 422다.
  - 근무: 계약 예시 5건 중 3건이 집계되고, 원본 목록 + 겹침 조건과 같다.
    - 동료 근무는 이름·시각만 나오고 ID는 없다. 다른 지점 직원의 근무는 빠진다.
    - OWNER는 전체를 보고 동료 목록은 비어 있다. 소속을 회수하면 다음 요청부터 동료 목록에서 빠진다.
  - 문의·점검·공지·좌석이 원본 요약과 같다. 초안 공지와 다른 지점 공지는 나오지 않고, 응답에 메시지 카드가 없다.
  - 다른 지점 scope는 403, 잘못된 scope는 422, 미인증은 401이다. 회수 후 다음 요청은 403이다.
- 아키텍처·계약 테스트(9개 클래스): 통과
- 전체 `./gradlew test`(992개, 건너뜀 25)
  - 첫 실행에서 12개 클래스, 107개가 실패했다.
    - 106개는 같은 원인이다. 다른 세션의 전체 테스트와 동시에 돌던 중 테스트 MySQL 연결이 끊겼다("Can not read response from server"). 그 뒤 Spring 컨텍스트를 다시 불러오지 못해 연쇄로 실패했다.
    - 1개는 `CustomerHistoryIntegrationTests`의 기존 단언이다. 응답 전체에서 방 ID 문자열(`7"`)이 없음을 확인하는데, 다른 값에 우연히 걸린 것으로 보인다. 홈 변경과 관련 없는 모듈이다.
  - 실패한 12개 클래스만 다시 실행했다: 125개 모두 통과.
  - 나머지 클래스는 첫 실행에서 통과했다.
- **미검증**
  - Frontend 연동(Frontend #31 범위)
  - 좌석 사용 중(inUse) 수치: 입실 기록을 만들지 않아 0만 확인했다. 값은 원본 `occupancy`를 그대로 합산한다.

## 6. 남은 작업 / 보류 사항

- Frontend #31: 홈 화면을 `/api/home/summary`로 바꾸고 이동 대상을 연결한다. 그 뒤 로컬 샘플 `GET /api/home`을 정리한다.
- #89: 대표 집계의 실행 계획 측정. 메뉴 완성 조건은 아니다.
