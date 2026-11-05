# BE-111 지점 휴무·판매 기간/한도·고객 병합·마감·고급 필터 API 계약

- 이슈: [#111 지점 휴무·판매 기간/한도·고객 병합·마감·고급 필터 API 계약](https://github.com/OpenStudyService/OpenStudy-Backend/issues/111) · M13 · `type:feature`, `layer:controller`
- 상태: **착수 가능** (M13 전체의 시작점)
- 선행: M4 계약 #55, M9 계약 #86 (완료)
- 후속: #112, #113, #114, #115, #116
- 인수 조건 진행: 0 / 4 (2026-11-06 조회)

## 목표

M13 확장 기능의 API를 먼저 확정해 Backend와 Frontend가 같은 계약으로 병행하게 한다.

## 작업 단계

1. ADR-M13-001 결정과 기존 상품·결제·집계 계약(M4·M9)을 확인한다.
2. `docs/design/foundation/operations-extension-api.md` 작성: 휴무일, 판매 기간/한도, 고객 병합, 마감·마감 조정, 고급 필터, 점유 추이 operation과 오류 응답.
3. 판매 불가 사유(판매 중지·기간 외·한도 초과)를 서로 다른 오류 코드로 정의한다.
4. 고급 필터를 목록과 집계에 같은 조건 구조로 정의하고, 응답 메타에 적용 필터를 넣는다.
5. 기존 상품·결제·집계 API에 추가되는 필드·필터를 정리한다.
6. operation별 권한을 ADR과 맞추고 `openapi.json`에 M13 operation을 추가한다.

## 제외 범위

- 구현 (#112~#116)

## 검증

- 계약 문서와 `openapi.json` operation 목록 대조, 계약 테스트

## 참고

- [ADR-M13-001](../db/ADR-M13-001.md), [ADR-M9-001](../db/ADR-M9-001.md)
- [집계 API 계약](../design/foundation/aggregation-api.md)
