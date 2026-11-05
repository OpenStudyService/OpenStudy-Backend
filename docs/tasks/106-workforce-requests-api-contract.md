# BE-106 근태 정정·휴가·대체근무 API 계약

- 이슈: [#106 근태 정정·휴가·대체근무 API 계약](https://github.com/OpenStudyService/OpenStudy-Backend/issues/106) · M12 · `type:feature`, `layer:controller`
- 상태: **착수 가능** (M12 전체의 시작점)
- 선행: #82 M8 계약 (완료)
- 후속: #107, #108, #109
- 인수 조건 진행: 0 / 4 (2026-11-06 조회)

## 목표

정정·휴가·대체근무의 신청·승인·반려·취소 API를 먼저 확정해 Backend와 Frontend가 같은 계약으로 병행하게 한다.

## 작업 단계

1. ADR-M12-001 M12-D01~D09와 M8 계약(근무표·근태)을 다시 읽고 반영할 결정을 목록으로 만든다.
2. `docs/design/foundation/workforce-requests-api.md` 작성: 엔드포인트, DTO, 오류 코드.
3. 정정·휴가·대체근무 각각의 상태 전이표(신청·승인·반려·취소)와 상태별 허용 동작을 정의한다.
4. operation별 권한: 본인, 기록 지점 MANAGER, 신청 지점 MANAGER, 원부여 지점 MANAGER.
5. 충돌 오류 응답(휴가·근무 충돌, 정정 구간 겹침, 대체근무 버전 불일치)에 대상 정보를 담는다.
6. 반복·동시 요청 응답(같은 결과 또는 409), 버전·멱등 처리를 정한다.
7. `openapi.json`에 M12 operation을 추가한다.

## 제외 범위

- 구현 (#107~#109)

## 검증

- 계약 문서와 `openapi.json` operation 목록 대조, 계약 테스트

## 참고

- [ADR-M12-001](../db/ADR-M12-001.md), [ADR-M8-001](../db/ADR-M8-001.md)
- [M8 근태 API 기록](../development-log/2026-11-04-attendance-api.md)
