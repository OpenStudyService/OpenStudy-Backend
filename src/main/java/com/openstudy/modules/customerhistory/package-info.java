/**
 * 고객의 전 지점 이용 요약과 룸·사물함 이력(FR-MEM-03, M6-D06).
 * <p>고객·이용권·좌석·룸·사물함 모듈의 공개 service를 묶어 읽는다. 이 모듈들이 고객에 의존하므로 조합을 고객 모듈에 두면 의존 순환이 생겨 별도 모듈로 둔다.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다.</p>
 */
package com.openstudy.modules.customerhistory;
