# BE-118 계정 복구·프로필 사진·1:1 대화·채팅 스트림·감사 조회 API 계약

- 이슈: [#118 계정 복구·프로필 사진·1:1 대화·채팅 스트림·감사 조회 API 계약](https://github.com/OpenStudyService/OpenStudy-Backend/issues/118) · M14 · `type:feature`, `layer:controller`
- 상태: **착수 가능** (M14 전체의 시작점)
- 선행: M1 계약 #7, M7 계약 #74 (완료)
- 후속: #119, #120, #121, #122
- 인수 조건 진행: 0 / 4 (2026-11-06 조회)

## 목표

M14 기능의 API를 먼저 확정해 Backend와 Frontend가 같은 계약으로 병행하게 한다.

## 작업 단계

1. ADR-M14-001 결정과 M1 인증·M7 채팅 계약을 확인한다.
2. `docs/design/foundation/account-collab-api.md` 작성: 비밀번호 변경, 초기화 요청, 재설정 링크 사용, 잠금 해제, 사진 업로드·조회, 1:1 대화, 채팅 스트림, 감사 검색·상세 operation과 오류 응답.
3. 비밀번호·토큰이 어떤 응답에도 들어가지 않도록 DTO를 정의한다.
4. 채팅 스트림: 이벤트 종류, 재연결 후 재조회 대상, 종료 사유. 공통 workspace SSE와 경로를 분리한다.
5. operation별 권한을 ADR과 맞추고 `openapi.json`에 M14 operation을 추가한다.

## 제외 범위

- 구현 (#119~#122)

## 검증

- 계약 문서와 `openapi.json` operation 목록 대조, 계약 테스트

## 참고

- [ADR-M14-001](../db/ADR-M14-001.md)
- [workspace API 계약](../design/foundation/workspace-api.md)
