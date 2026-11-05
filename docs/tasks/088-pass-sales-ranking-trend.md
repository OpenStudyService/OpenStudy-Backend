# BE-088 이용권 판매량·발급 건수·상품 순위·기간 추세

- 이슈: [#88 이용권 판매량·발급 건수·상품 순위·기간 추세](https://github.com/OpenStudyService/OpenStudy-Backend/issues/88) · M9 · `type:feature`, `layer:controller`, `layer:service`, `layer:persistence`
- 상태: **착수 가능**
- 선행: #87 (완료)
- 후속: #89, #93, #127
- 인수 조건 진행: 0 / 7 (2026-11-06 조회)

## 목표

결제 건수·순판매량·발급 건수를 구분해 반환하고, 판매 당시 상품 스냅샷으로 상품 순위와 일·주·월 추세를 재현한다.

## 작업 단계

1. #86 계약의 판매량·순위·추세 operation과 M9-D06 지표 정의를 다시 확인한다.
2. 세 지표 계산: 결제 건수, 순판매량(환불 제외), 발급 건수(환불돼도 유지). 실패 결제는 어느 지표에도 넣지 않는다.
3. 상품별 판매량: 판매 당시 상품명·분류 스냅샷(`product_revisions`) 기준으로 집계한다.
4. 판매 순위: 판매량이 같으면 일관된 보조 기준으로 정렬한다.
5. 추세(`measure`로 매출·판매량 구분): 일·주(월요일 시작)·월 구간, 빈 구간 0, 경계 부분 구간 메타 표시, 구간 합계 = 전체 합계.
6. #87의 공통 조건·권한·`aggregation` 메타를 재사용하고 컨트롤러를 연결한다.

## 제외 범위

- 좌석 점유 추이 (#116)

## 검증

- HTTP·DB 통합 테스트: 환불 포함 세 지표, 상품명 변경 전후, 동률 정렬, 월·주 경계, 빈 구간, 구간 합계
- `./gradlew test bootJar`

## 참고

- [집계 API 계약](../design/foundation/aggregation-api.md)
- [ADR-M9-001](../db/ADR-M9-001.md) M9-D04·D06
- [#87 개발 기록](../development-log/2026-11-05-sales-revenue-ledger.md)
