# 2026-11-26 invitation 모듈 가독성 정리

## 1. 배경 / 문제

#250의 세 번째 묶음 중 마지막 모듈이다. invitation 모듈(29개 파일, 약 1,860줄)은 다음을 담당한다.

- 초대 관리(생성·재발급·취소·반려·승인)
- 공개 링크 수락
- 승인 상태 조회
- 초대 메일 발송 워커와 SMTP 어댑터

다음 문제들이 읽기를 어렵게 했다.

- 한 줄 `if`, `var`
- 한 줄에 여러 대입을 쓴 엔티티 생성
- 100자가 넘는 JPQL 문자열 연결
- 긴 람다 안에 몰린 수락·발송 처리
- 120자를 넘는 조건식

## 2. 검토한 선택지

따로 검토한 대안은 없다. 앞선 단계와 같이 동작은 유지하고 형식과 모듈 안의 이름만 정리했다.

## 3. 결정 및 이유

- 테스트와 다른 모듈이 직접 부르는 public 이름은 바꾸지 않았다.
  - 서비스: `InvitationManagementService`, `InvitationAcceptanceService`, `InvitationDeliveryService`, `ApprovalStatusService`
  - 저장소 래퍼 `InvitationDeliveryRepository`·`InvitationManagementRepository`의 메서드와 레코드(`Candidate`, `InvitationState`, `Job`, `Stored`, `Link`): 테스트가 하위 클래스로 결함을 주입하기도 한다.
  - account 모듈이 쓰는 `InvitationDeliverySettings`, `InvitationMailConfiguration.validateTransport`
  - 생성자 시그니처: 테스트가 직접 생성한다.
- 컨트롤러 메서드는 openapi operationId에 맞췄다.
  - `listInvitations`, `getInvitation`, `createInvitation`, `reissueInvitation`, `cancelInvitation`, `rejectInvitation`, `approveInvitation`
  - `previewInvitation`, `acceptInvitation`, `getApprovalStatus`
- 모듈 안에서만 쓰는 Spring Data 메서드는 동사로 시작하게 바꿨다.
  - 예: `candidates` → `findCandidates`, `sent` → `markSent`, `lockedPermissionIds` → `findPermissionIdsForShare`
- 잠금 순서, 토큰·해시 처리, 감사 기록, 오류 코드는 그대로 둔다.
  - 조건을 이름 있는 boolean으로 나눌 때 미리 계산하는 것은 부작용 없는 값뿐이다.
  - 조회나 상태 변경 호출의 순서는 원래와 같다.
- 스케줄러 오류 로그는 원래처럼 `InvitationEmailScheduling` 로거 이름으로 남도록 `@Slf4j(topic = ...)`을 지정했다.

## 4. 구현 내용

- `InvitationAcceptanceService`
  - 수락 명령의 잠금 안 처리를 `acceptLocked`로 옮겼다.
  - 계정 생성(`createAccount`)과 기존 계정 확인(`requireExistingAccount`)을 나눴다.
  - 세션 조건 검사를 `requireSessionFor`로 묶었다.
- `InvitationDeliveryService`
  - 후보 하나를 잠그고 확보하는 처리를 `claimCandidate`로 뺐다.
  - `stillCurrent`와 `finish`에 똑같이 있던 "발송 가능 → 리스 유효 → 토큰 일치" 확인을 `isLiveAttempt`로 합쳤다.
  - 시도 횟수 소진 기록을 `exhaustAttempts`로 합쳤다.
- `InvitationManagementService`
  - 수락한 직원 확인(`requireAcceptedBy`)과 변경 감사 기록(`recordChange`)을 메서드로 뺐다.
  - 상태 집합과 길이 제한을 상수로 바꿨다.
- 저장소
  - `InvitationRepository`와 `InvitationSendJobRepository`의 JPQL을 텍스트 블록으로 옮겼다.
  - `InvitationManagementRepository`·`InvitationDeliveryRepository`의 응답 조립을 `toView`·`toLink`·`toState`·`toJob`으로 뺐다.
- `SmtpInvitationMailSender`·`InvitationMailConfiguration`·`InvitationDeliverySettings`
  - 긴 검증 조건을 이름 있는 boolean으로 나눴다.
  - 제한 값을 상수로 바꿨다.
- 엔티티
  - `EmployeeInvitation`·`InvitationSendJob`에 Lombok `@Getter`·`@NoArgsConstructor(PROTECTED)`를 썼다.
  - 복사본을 돌려주는 `getTokenHash`·`getPermissionIds`는 직접 둔다.
- 컨트롤러·DTO
  - 클래스 경로를 `/api`로 두고 파라미터와 레코드 구성 요소를 한 줄에 하나씩 썼다.
  - 최종 경로는 같다.

## 5. 검증 결과

- `./gradlew compileJava compileTestJava`: 통과.
- invitation `src/main`에 `var`, 중괄호 없는 `if`·`for`, 120자를 넘는 코드 줄(주석 제외)이 남아 있지 않은 것을 확인했다.
- `./gradlew test --tests "com.openstudy.modules.invitation.*"`: 54개 모두 통과.
- `./gradlew test`: 1,270개 중 실패 0, 건너뜀 28 (BUILD SUCCESSFUL, 약 20분). 건너뛴 28개는 ai·sales 패키지의 AI 관련 조건부 테스트다.
- 실제 SMTP 서버로의 발송은 검증하지 않았다(테스트는 가짜 전송 객체를 쓴다).

## 6. 남은 작업 / 보류 사항

- 초대 관리 저장소, 프리셋 서비스, branch 서비스에 멱등 키 검증과 SHA-256 계산이 비슷하게 반복된다. 공통화는 여러 모듈에 걸치므로 별도로 판단한다.
- #250의 다음 묶음: customer, customerhistory, passhistory, audit, retention, home, workspace, notice, inquiry
