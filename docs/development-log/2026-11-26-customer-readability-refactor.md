# 2026-11-26 customer 모듈 가독성 정리

## 1. 배경 / 문제

#250의 네 번째 묶음(customer, customerhistory, passhistory, audit, retention, home, workspace, notice, inquiry)의 첫 모듈이다. customer 모듈(24개 파일, 약 1,270줄)은 다음을 담당한다.

- 전사 고객 원장: 등록·검색·상세·수정·상태 변경
- 중복 고객 확인과 병합
- 체크인 조회·판매·문의 대상 확인처럼 다른 모듈이 쓰는 진입점

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`·`for`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성자
- 130자가 넘는 응답 조립과 스트림 체인
- 문자열을 이어 붙인 네이티브 SQL

이 작업은 앞서 서브에이전트로 시도했다가 정리한 작업을 처음부터 다시 진행한 것이다.

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 다른 모듈과 테스트가 쓰는 `CustomerService`의 public 메서드는 바꾸지 않았다.
  - 예: `requireVisible`, `requirePurchasable`, `matchPhone`, `recordLookup`, `summaries`, `normalizedPhone`
- 모듈 안에서만 쓰는 이름은 동사로 시작하게 바꿨다.
  - 저장소: `lockShared` → `lockForShare`, `duplicatePhones` → `findDuplicatePhones`, `involving` → `findInvolving`, `visible` → `isVisible` 등
  - `CustomerMergeService`: `duplicates` → `findDuplicates`, `history` → `findHistory`(컨트롤러만 호출한다)
  - `CustomerInputs`: `name`·`phone` → `readName`·`readPhone`, `status`·`label` → `toStatus`·`toLabel`
- 컨트롤러 메서드는 openapi operationId에 맞췄다.
  - `listCustomers`, `getCustomer`, `createCustomer`, `updateCustomer`, `changeCustomerStatus`
  - `listCustomerDuplicates`, `mergeCustomer`, `listCustomerMerges`
- 검증 순서, 잠금 순서, 감사 기록, 멱등 키 처리는 그대로 둔다.
  - 고객 상세의 조회 순서도 원래와 같다: 수정 가능 여부 → 병합 대상 → 흡수 수.
- 동적 검색 SQL(`CustomerSearchRepository`)은 조건 덧붙이기를 메서드로 나눴지만, 만들어지는 SQL 문자열과 바인딩 순서는 원래와 글자 단위로 같다.

## 4. 구현 내용

- `CustomerService`
  - 주입 필드 이름을 타입에 맞췄다.
  - 변경 필드 계산(`findChangedFields`), 멱등 재실행(`replay`), 응대 기록 저장(`recordInteraction`), 감사 기록(`recordChange`)을 메서드로 뺐다.
  - 이름이 실제 동작과 달랐던 `lockedCustomer`는 잠금 없이 조회만 하므로 `findCustomer`로 바꿨다.
  - 매직 넘버(키 길이, 201, 24시간, 회원번호 길이, 사유 길이)를 상수로 바꿨다.
- `CustomerMergeService`
  - 중복 그룹 구성원 조립(`toMember`), 병합 이력 변환(`toRecord`), 병합 감사 기록(`recordMerge`)을 뺐다.
  - 병합된 고객 확인 반복문을 `requireNotMerged` 두 번 호출로 바꿨다. 확인 순서는 같다.
- `CustomerSearchRepository`
  - `find`의 상태 조건과 검색어 조건을 `appendStatus`·`appendKeyword`로 나눴다.
  - 정규화된 클래스 이름을 import로 바꿨다.
- 저장소: `CustomerRepository`·`CustomerMergeRepository`의 쿼리 여덟 개를 텍스트 블록으로 옮겼다. 줄바꿈과 들여쓰기만 달라졌다.
- 엔티티 네 개: 필드 어노테이션을 한 줄에 하나씩 쓰고, 한 줄 대입을 풀었다.
- DTO 레코드: 구성 요소를 한 줄에 하나씩 썼다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- customer `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- customer 모듈 테스트: 처음 실행은 Docker Desktop이 꺼져 있어 14개 모두 컨텍스트 로딩에서 실패했다(Testcontainers MySQL을 띄우지 못함). 코드 원인이 아니다. Docker를 켠 뒤 전체 테스트에서 14개 모두 통과했다.
- 전체 테스트 1차(`./gradlew test`): 1,270개 중 실패 11, 건너뜀 52.
  - 실패는 모두 AI·DomainLens 테스트(`DomainLensPipelineIntegrationTests` 등)였고 원인은 `INDEX_MISMATCH`였다.
  - 같은 테스트가 `main`에서도 똑같이 실패해 이번 변경과 무관함을 확인했다.
  - 원인은 로컬 임베딩 모델(`.models/koen-e5-tiny-q8`, Git 미포함) 삭제였다. 앞서 서브에이전트 워크트리를 정리하면서 폴더를 강제 삭제했는데, 그 안의 `.models` 디렉터리 링크(junction)를 따라 원본 모델 파일까지 지워졌다.
  - 다른 로컬 체크아웃에 있던 같은 모델을 복사해 복구했고, 해시가 `manifest.json`과 일치함을 확인했다. 복구 후 실패했던 AI 테스트 3개 클래스(21개)가 통과했다.
- 전체 테스트 2차(`./gradlew test`): 1,270개 중 실패 0, 건너뜀 28 (BUILD SUCCESSFUL, 약 19분). 건너뛴 28개는 ai·sales 패키지의 AI 관련 조건부 테스트다.

## 6. 남은 작업 / 보류 사항

- 멱등 키 검증과 SHA-256 계산이 고객·지점·초대·프리셋 서비스에 비슷하게 반복된다. 공통화는 여러 모듈에 걸치므로 별도로 판단한다.
- #250의 같은 묶음에서 남은 모듈: customerhistory, passhistory, audit, retention, home, workspace, notice, inquiry
