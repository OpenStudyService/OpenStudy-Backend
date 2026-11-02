/**
 * 고객의 구매·사용 내역(FR-MEM-07).
 * <p>고객·이용권·좌석 이용·결제 모듈의 공개 service를 묶어 읽기만 한다. 좌석·결제가 고객에 의존하므로 이 조합을 고객 모듈에 두면 의존 순환이 생겨 별도 모듈로 둔다.</p>
 * <p>모듈 간 접근은 명시적으로 공개한 service 진입점과 DTO만 허용한다. 다른 모듈의 entity·repository에는 직접 접근하지 않는다.</p>
 */
package com.openstudy.modules.passhistory;
