# 2026-11-02 M5 잔량 노출과 계약 후속 정리

관련: [#64 개발 기록](2026-11-02-pass-adjustment-history.md), [좌석 이용 계약](../design/foundation/seat-usage-api.md), [협업 API 계약](../design/foundation/collaboration-api.md).

## 1. 배경 / 문제

M5 구현을 마치며 세 가지가 남았다.

- 이용 기록의 목록·상세가 응답 시점 이용권 잔량을 준다. 다른 지점에서 산 이용권도 이 지점에서 입실한 적이 있으면, 종료된 이용 건으로 고객의 현재 잔량을 계속 볼 수 있다. 계약의 "입실에 필요한 정보는 `usablePasses`로만 준다"와 맞지 않는다.
- 구현에서 정했지만 계약에 없는 동작이 두 개 있다. 다른 지점 이용의 `usedMinutes`·`seatSessionId` null과 `adjustPass`의 `INVALID_STATE`다.
- `version`만 보낸 `PATCH`가 고객·좌석은 422인데 업무는 현재 값을 돌려준다.

## 2. 검토한 선택지

- **잔량 노출**
  - 현행 유지: 스키마대로지만 위 문제가 남는다.
  - 구매 지점이 요청 범위 안일 때만 잔량을 준다: 진행 중인 이용의 현장 응대에서 잔량을 볼 수 없다.
  - 진행 중인 이용에만 잔량을 주고 종료된 이용은 null: 이쪽을 제안했고 사용자가 정리 진행으로 받아들였다.
- **퇴실 응답의 잔량**: 종료된 이용이지만 계약이 "퇴실 후 이용권 잔량"을 준다고 정했고, 처리 결과를 직원에게 알려야 한다. 조회(GET)에만 규칙을 적용하고 명령 응답은 유지했다.

## 3. 결정 및 이유

- 조회에서는 진행 중인 이용에만 잔량을 준다. 입실·이동·퇴실 명령의 응답은 처리 직후 잔량을 그대로 준다.
- 업무 `PATCH`도 `version` 외 항목이 없으면 422로 맞췄다. 같은 값을 다시 보낸 요청은 지금처럼 현재 값을 돌려준다.
- OpenAPI 버전 번호는 올리지 않았다. 스키마 구조는 그대로이고 설명과 오류 코드 목록만 바뀌었다.

## 4. 구현 내용

- `SeatSessionService`: 조회 경로(`get`, `list`)만 종료된 이용의 잔량을 null로 준다.
- `TaskService.update`: `version` 외 항목이 없으면 422를 준다.
- `seat-usage-api.md`, `collaboration-api.md`: 위 규칙과 다른 지점 이용의 null 필드를 기록했다.
- `openapi.json`: `SeatSession` 잔량 설명, `PassHistoryEvent`·`TaskUpdate` 설명, `adjustPass`의 `INVALID_STATE`
- 테스트: 진행 중·종료 후 다른 지점 이용권 잔량, 종료된 상세, 업무의 `version`만 보낸 `PATCH`

## 5. 검증 결과

- `./gradlew test`: 52개 suite, 476개 통과(실패·오류·건너뜀 0)
- 첫 전체 실행은 1개 실패했다. 새 테스트의 기대 잔량을 600으로 잘못 적었고 실제 시험 데이터는 100분이었다. 기대값을 고쳐 다시 실행했다. 기존 `checkOutRecordsActorReasonAuditAndEventsAndReleasesTheSeat`는 상세가 퇴실 응답과 같다고 단언해 새 규칙과 어긋났으므로, 잔량만 다르다는 단언으로 바꿨다.

- 실제 서버 실행, Frontend 연결은 하지 않았다.

## 6. 남은 작업 / 보류 사항

- Frontend는 종료된 이용의 잔량이 null임을 처리해야 한다(#21·#22).
