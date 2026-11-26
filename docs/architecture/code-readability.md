# 백엔드 코드 가독성 규칙

사람이 읽기 쉬운 백엔드 Java 코드를 쓰기 위한 규칙이다.

- 새 코드는 처음부터 이 규칙을 따른다.
- 기존 코드는 해당 파일을 수정할 때, 동작을 바꾸지 않는 범위에서 함께 정리한다.
- 모듈 단위 정리 작업은 [#250](https://github.com/OpenStudyService/OpenStudy-Backend/issues/250)에서 진행한다.

출처: 직접 정리한 두 작업의 패턴에서 시작했고([auth 모듈 정리](../development-log/2026-11-06-auth-readability-refactor.md), [비밀번호 재설정 메일 정리](../development-log/2026-11-12-password-reset-url-config.md)), #250을 진행하며 보완했다.

## 1. 이름

### 1.1 주입 필드 이름은 타입 이름을 따른다

필드 이름만 보고 어떤 의존성인지 알 수 있어야 한다. 줄임말이나 복수형 별칭을 쓰지 않는다.

```java
// 지양
private final CurrentUserService users;
private final LoginAttemptLimiter limiter;
private final ObjectMapper json;
private final AuditTrail audit;

// 권장
private final CurrentUserService currentUserService;
private final LoginAttemptLimiter loginAttemptLimiter;
private final ObjectMapper objectMapper;
private final AuditTrail auditTrail;
```

### 1.2 메서드 이름은 동사로 시작한다

무엇을 하는지 드러나게 동사로 시작한다. 자주 쓰는 접두사는 다음과 같다.

| 접두사 | 쓰임 | 예 |
|---|---|---|
| `get…` | 값을 돌려주는 조회(주로 컨트롤러·공개 API) | `getCurrentUser`, `getCsrfToken` |
| `find…` | 저장소·서비스 조회 | `findCustomer`, `findPaymentBranchIds` |
| `create…` | 새 값 생성 | `createMemberNo`, `createToken` |
| `to…` | 변환 | `toDetail`, `toSummary`, `toLabel` |
| `require…` | 검사하고 어긋나면 예외 | `requireValidKey`, `requireNotMerged` |
| `validate…` | 입력 검증 | `validateMailbox`, `validateWeeklyHours` |
| `is…`·`has…` | boolean 판단 | `isVisible`, `hasLiveLease` |
| `record…` | 감사·이력 기록 | `recordChange`, `recordLookup` |

| 지양 | 권장 |
|---|---|
| `current(employeeId)` | `getCurrentUser(employeeId)` |
| `mailbox(value)` | `validateMailbox(value)` |
| `detail(customer, state)` | `toDetail(customer, state)` |
| `lockedPermissionCodes(id)` | `findPermissionCodesForShare(id)` |

이름이 실제 동작과 다르면 바로잡는다. 예를 들어 잠그지 않고 조회만 하는 `lockedCustomer`는 `findCustomer`로 바꾼다.

### 1.3 매직 값은 이름 있는 상수로 뺀다

의미 있는 숫자, 정규식, 고정 집합은 `private static final` 상수로 선언한다. 정규식은 매번 컴파일하지 않도록 `Pattern`으로 둔다.

```java
private static final int MAX_NAME_LENGTH = 100;
private static final int MAX_EMAIL_LENGTH = 254;
private static final Duration KEY_RETENTION = Duration.ofHours(24);
private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
```

## 2. 타입과 객체 생성

### 2.1 `var` 대신 명시적 타입을 쓴다

읽는 사람이 메서드 반환 타입을 찾아보지 않아도 되게 한다. builder 결과도 마찬가지다.

```java
// 지양
var authentication = authenticationManager.authenticate(...);
var user = CurrentUser.User.builder()...build();

// 권장
Authentication authentication = authenticationManager.authenticate(...);
CurrentUser.User user = CurrentUser.User.builder()...build();
```

### 2.2 같은 모양의 생성이 반복되면 builder를 쓴다

인자가 많은 record·클래스를 여러 곳에서 위치 순서대로 만들고 다음 중 하나에 해당하면, `@Builder`를 붙이고 builder로 생성한다.

- 쓰지 않는 칸을 `null`·`0`으로 채워 넣어야 한다.
- 같은 타입 인자가 나란히 있어 자리가 바뀌어도 컴파일된다.

```java
// 지양: 12칸 중 5칸이 빈 값이고, 같은 타입 인자가 연속된다
return new PassHistoryEvent(trail.approvedAt(), PURCHASE, passId, productName, 0, 0, null,
        branchId, paymentId, null, null, actor);

// 권장: 쓰는 값만 이름으로 넣고, 공통 값은 헬퍼가 채운다
return newEvent(pass)
        .at(trail.approvedAt())
        .type(PURCHASE)
        .branchId(toId(pass.purchaseBranchId()))
        .paymentId(toId(pass.paymentId()))
        .actor(names.toActor(trail.soldBy()))
        .build();
```

반대로, 한 곳에서만 호출하고 모든 칸을 원본 필드로 채우는 응답 변환 생성자(예: `toDetail`의 `new CustomerDetail(...)`)는 생성자로 둔다. 생성자는 필드가 추가되면 변환 코드를 반드시 고치게 만들지만, builder는 빠뜨린 필드를 조용히 `null`로 둔다.

## 3. Lombok

- 생성자 주입은 직접 생성자를 쓰지 않고 `@RequiredArgsConstructor`를 쓴다.
- 로거는 `LoggerFactory.getLogger(...)` 대신 `@Slf4j`의 `log`를 쓴다. 로거 이름을 다른 클래스로 유지해야 하면 `@Slf4j(topic = "...")`를 쓴다.
- JPA 엔티티는 `@Getter`와 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 쓴다.
  - 복사본을 돌려주는 getter(컬렉션, 배열)는 직접 작성하고, 그 필드에는 `@Getter(AccessLevel.NONE)`을 붙여 의도를 드러낸다.
- builder는 2.2의 기준에 맞을 때 `@Builder`로 만든다.

## 4. 줄바꿈과 배치

### 4.1 한 줄은 120자를 넘기지 않는다

주석을 뺀 코드 줄 기준이다. 한글이 섞인 줄은 바이트가 아니라 글자 수로 센다.

### 4.2 한 줄에는 문장 하나만 쓴다

여러 대입이나 `if`·`for`를 한 줄에 몰아 쓰지 않는다.

```java
// 지양
this.name = name; this.phone = phone; this.email = email;

// 권장
this.name = name;
this.phone = phone;
this.email = email;
```

### 4.3 긴 파라미터·인자 목록은 한 줄에 하나씩 쓴다

한 줄에 다 들어가지 않으면 괄호를 열고 줄을 바꾸고, 항목마다 한 줄을 쓴 뒤 닫는 괄호를 따로 한 줄에 둔다. 메서드 선언, 메서드 호출, record 컴포넌트에 모두 적용한다.

```java
public ResponseEntity<CurrentUser> login(
        @Valid @RequestBody LoginRequest body,
        HttpServletRequest request,
        HttpServletResponse response
) {
```

```java
public record EmployeeAccount(
        long id,
        String email,
        String passwordHash,
        // ...
        Instant updatedAt
) {
}
```

필드 어노테이션도 한 줄에 하나씩 쓰고, 필드 사이에는 빈 줄을 둔다.

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

@Enumerated(EnumType.STRING)
@Column(nullable = false)
private CustomerStatus status;
```

### 4.4 메서드 체인은 호출마다 줄을 바꾼다

```java
return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(currentUser);

List<Long> ids = rows.stream()
        .map(Customer::getId)
        .toList();
```

### 4.5 빈 줄로 논리 단위를 나눈다

검증 → 준비 → 처리 → 반환처럼 단계가 바뀌는 곳에 빈 줄을 둔다. `return` 앞, `if`·`try` 블록 앞뒤에도 빈 줄을 둔다. 클래스 선언 바로 다음 줄도 비운다.

```java
ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

scheduler.setPoolSize(1);
scheduler.setThreadNamePrefix("password-reset-mail-");
scheduler.setErrorHandler(failure -> log.warn("Password reset delivery scheduling failed"));

return scheduler;
```

### 4.6 중첩 호출은 지역 변수로 풀어낸다

인자 안에서 다른 호출을 여러 겹 하지 않는다. 의미 있는 이름의 지역 변수에 먼저 담은 뒤 전달한다.

```java
// 지양
return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(users.current(Long.parseLong(authentication.getName()), sessions.mode(request.getSession(false))));

// 권장
long employeeId = Long.parseLong(authentication.getName());
String sessionMode = sessionBoundaryPolicy.mode(request.getSession(false));

CurrentUser currentUser = currentUserService.getCurrentUser(employeeId, sessionMode);
```

## 5. 조건문

### 5.1 `if`·`for`에는 항상 중괄호를 쓴다

한 줄짜리 본문도 블록으로 감싼다.

```java
// 지양
if (!account.canLogin()) throw new DisabledException("Inactive account");

// 권장
if (!account.canLogin()) {
    throw new DisabledException("Inactive account");
}
```

### 5.2 조건이 여러 개면 연산자 앞에서 줄을 바꾼다

`||`, `&&`를 줄 앞에 둔다.

```java
boolean valid = key != null
        && key.length() >= MIN_KEY_LENGTH
        && key.length() <= MAX_KEY_LENGTH;
```

### 5.3 복잡한 조건은 이름 있는 boolean으로 나눈다

조건이 무엇을 검사하는지 이름으로 설명한다.

```java
boolean plainUrl = uri.getHost() != null
        && uri.getUserInfo() == null
        && uri.getRawQuery() == null;
boolean trustedScheme = "https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()));

if (!plainUrl || !trustedScheme) {
    throw new IllegalArgumentException();
}
```

미리 계산해 boolean에 담는 것은 부작용 없는 값만이다. 원래 단락 평가(`&&`, `||`)로 건너뛰던 DB 조회나 상태 변경 호출은 계속 건너뛰도록 조건식 안에 둔다.

### 5.4 중첩 삼항 연산자를 쓰지 않는다

삼항 연산자 안에 삼항 연산자를 넣지 않는다. 기본값을 먼저 두고 `if`로 덮어쓰거나, 갈래가 여럿이면 `if` 순서나 `switch`로 바꾼다. 단순한 삼항 연산자도 길면 `?`와 `:` 앞에서 줄을 바꾼다.

```java
// 지양
String mode = sessionMode == null
        ? ("PENDING".equals(account.status()) ? APPROVAL_ONLY : WORKSPACE)
        : sessionMode;

// 권장
String mode = sessionMode;
if (mode == null) {
    mode = "PENDING".equals(account.status())
            ? SessionBoundaryPolicy.APPROVAL_ONLY
            : SessionBoundaryPolicy.WORKSPACE;
}
```

## 6. 메서드 분리

한 메서드가 검증, 생성, 전송처럼 여러 단계를 하면 단계마다 private 메서드로 나눈다. 공개 메서드는 단계의 흐름만 보여 준다. 긴 람다(트랜잭션 콜백 등) 안의 처리도 같은 기준으로 나눈다.

```java
@Override
public void send(String email, String name, String token) {
    try {
        String recipient = validateMailbox(email);
        validateResetRequest(name, token);

        MimeMessage message = createMessage(recipient, name, token);
        javaMailSender.send(message);
    } catch (MessagingException | RuntimeException failure) {
        throw new DeliveryException();
    }
}
```

## 7. 문자열과 주석

- 여러 줄 문자열은 `+` 연결 대신 text block(`"""`)과 `formatted(...)`를 쓴다.
- `@Query`의 JPQL·네이티브 SQL도 text block으로 쓴다. 옮길 때 토큰은 그대로 두고 줄바꿈과 들여쓰기만 바꾼다.
- 긴 Javadoc은 한 문장에 몰아 쓰지 않고, 요약 한 줄 뒤에 `<p>` 문단이나 문장 단위 줄로 나눈다.

```java
@Query(value = """
        SELECT DISTINCT branch_id
        FROM payments
        WHERE customer_id = :id
        ORDER BY branch_id
        """, nativeQuery = true)
List<Number> findPaymentBranchIds(@Param("id") long id);
```

## 8. 컨트롤러

### 8.1 공통 경로 접두사는 클래스 매핑에 둔다

컨트롤러의 공통 경로 접두사는 클래스의 `@RequestMapping`에 두고, 메서드 매핑에는 나머지 경로만 쓴다. 최종 URL은 바뀌지 않는다.

- 공통 접두사는 그 컨트롤러의 **모든 메서드가 공유하는 가장 긴 경로**다. 모든 메서드가 `/api/customers` 아래에 있으면 클래스에 `@RequestMapping("/api/customers")`를 둔다.
- 메서드 경로가 서로 다른 자원으로 갈라질 때만(예: `/auth/...`와 `/me`, `/branches/{id}/closures`와 `/branch-closures/{id}`) 클래스에 `/api`만 둔다.
- 메서드가 하나뿐이면 어느 쪽이든 된다. 같은 자원의 다른 컨트롤러와 맞춘다.

```java
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    @GetMapping
    public PageResponse<CustomerListItem> listCustomers(...) {

    @GetMapping("/{id}")
    public CustomerDetail getCustomer(...) {
```

### 8.2 메서드 이름은 OpenAPI operationId를 따른다

컨트롤러 메서드 이름은 `docs/design/foundation/openapi.json`의 `operationId`와 같게 한다(예: `listCustomers`, `createBranchClosure`). 계약에 operationId가 없는 엔드포인트는 동작을 드러내는 동사 이름을 붙인다.

## 9. 가독성 정리 작업의 원칙

- 가독성 정리는 동작을 바꾸지 않는다. 검증 규칙과 그 순서, 잠금 순서, 응답, API 경로, 예외 메시지, 쿼리의 의미를 그대로 유지한다.
- 메서드 이름을 바꾸면 `src/main`과 `src/test`의 호출부를 모두 함께 고친다.
- 다른 모듈이 쓰는 public 이름은 모듈 단위 정리에서 바꾸지 않는다. 한 PR이 한 모듈 안에 머물게 하기 위해서다.
- 가독성 정리와 기능 변경은 커밋을 나눠 리뷰에서 구분할 수 있게 한다.

## 아직 정하지 않은 항목

- **import 순서**: `com.openstudy` → 외부 라이브러리 → `java.*` 순서로 묶은 파일과 `java.*`가 먼저인 파일이 섞여 있다. 정할 때까지 각 파일의 기존 순서를 따른다.
- **와일드카드 import**: `org.springframework.web.bind.annotation.*` 같은 와일드카드 import를 쓰는 파일이 있다. 허용 여부를 정하지 않았으므로, 정리할 때 기존 형태를 유지한다.
