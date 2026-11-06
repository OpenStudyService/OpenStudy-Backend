# 2026-11-06 DomainLens 백엔드 이식([M] 파일 반영)

## 1. 배경 / 문제

- AI 채팅은 DomainLens로 자연어 DB 조회만 하기로 했다(SRS v1.14, ADR-M10-002). DomainLens를 OpenStudy에 맞게 고친 코드는 `OpenStudy-DomainLenz-Receive`에 있고, 반영 방법은 `porting.md`에 정리돼 있다.
- 사용자가 신규 파일(`[+]`)은 작업 트리에 대부분 옮겼고, 기존 파일 수정(`[M]`)은 반영하지 못한 상태였다. 프런트엔드는 이번 범위에서 제외한다.
- `[M]` 파일은 이식본을 만든 시점의 원본을 기준으로 수정돼 있어, 그대로 덮어쓰면 그 사이 `main`에 들어간 변경(#84 출퇴근 명령, #87·#88 매출 집계, 테스트 힙 설정)이 사라진다.

## 2. 검토한 선택지

- **덮어쓰기**: porting.md가 허용하지만, 출퇴근 엔티티의 생성자·상태 변경 메서드와 `@GeneratedValue`가 사라지고(#84 명령이 깨짐), `build.gradle`의 테스트 힙 1g 설정도 사라진다.
- **diff 반영**: 이식본과 현재 파일을 하나씩 비교해, 이식본이 추가한 부분만 옮긴다. 이 방법을 택했다.

아래 항목은 그대로 반영하면 문제가 생겨 사용자에게 먼저 확인했다.

| 항목 | 이식본 그대로일 때의 문제 | 결정 |
| --- | --- | --- |
| `SecurityConfiguration` | `/api/ai/**`를 비로그인 허용·CSRF 제외. AI 서비스가 비로그인 요청을 직원 1(대표)로 처리하고 본문 `actor`로 다른 직원을 대신할 수 있어, 누구나 대표 권한으로 DB를 조회 | 반영하지 않음. 로그인·CSRF 유지, `actor` 대신 조회 차단 |
| `sales` 모듈 | `SalesAggregationService`가 #87·#88 집계와 겹치고, 계약에 없는 `GET /api/sales`가 `SalesController`에 추가됨 | 엔드포인트만 제거하고 서비스는 AI 내부용으로 유지 |
| `application.yaml` | 모델 모드 기본값 `live` | 기본값 `playback`, `live`는 환경 변수로 켬 |
| ONNX 모델 파일 | 약 40MB를 저장소에 추적 | 커밋 제외(`.gitignore`) |
| 모델 부재 | 모델이 없으면 앱이 시작되지 않아 모든 통합 테스트 실패 | 모델이 없어도 기동하고 AI 검색만 비활성화 |
| AI 통합 테스트 4종 | 로컬 DB 비밀번호 평문, 저장소에 없는 기대값 파일(`docs/04_TEST_DATA`, `docs/05_TEST_CASES`) 의존 | 비밀번호는 환경 변수로, 명시적으로 켰을 때만 실행 |

## 3. 결정 및 이유

- 엔티티 `[M]`은 대부분 이식본에서 줄이 추가되기만 했으므로(`@SemanticEntity`·`@SemanticField`·`@SemanticPolicy`·`@SemanticRelation`) 이식본 파일을 그대로 썼다. 삭제가 섞인 `AttendanceRecord`·`AttendanceBreak`는 현재 코드를 유지하고 어노테이션만 추가했다. 이식본처럼 컬럼을 `insertable=false`로 바꾸면 #84의 출퇴근 쓰기가 불가능해진다.
- 보안은 기존 원칙(모든 API에 세션과 CSRF 필요)을 유지했다. AI 응답에는 매출 등 권한이 필요한 데이터가 들어가므로 예외를 둘 이유가 없다.
- 매출: M10-D05 경계는 AI가 공식 매출 서비스를 호출하는 것이다. 다만 AI 분석이 `SalesAggregationService`의 결과 형태에 맞춰 있어, 지금 바꾸면 AI 결과가 달라질 수 있다. 그래서 외부 노출(엔드포인트)만 막고 전환은 후속 작업으로 남겼다.

## 4. 구현 내용

- **엔티티 어노테이션**: 지점, 회원, 직원, 초대, 사물함·배정, 결제·환불, 스터디룸·예약, 좌석·이용, 업무, 이용권·상품·분류, 출퇴근·휴게, `IdempotencyRecord`. 읽기 전용 `AttendanceCorrection` 엔티티를 신규로 추가했다(기존 V1 `attendance_corrections` 테이블).
- **`build.gradle`**: Spring AI BOM, ONNX Runtime, DJL tokenizers, Spring AI chat·openai, jspecify를 추가했다. 테스트 힙 1g 설정은 유지했다.
- **`application.yaml`**: `.env.qwen.local` 가져오기, `openstudy.ai.*`·`domainlens.*` 설정을 추가했다. 모델 모드 기본값은 `playback`이다. 코드의 기본값(`OpenStudyPlaybackGateway`, `DomainLensProperties`)도 `playback`으로 맞췄다.
- **`.gitignore`**: `/.models/`를 추가했다.
- **모델 부재 대응**: `OpenStudyCatalogConfiguration`이 모델 디렉터리가 없을 때 경고 로그와 함께 `UnavailableEmbeddingGateway`를 쓰고, 빈 색인을 만든다.
  - 이때 AI 검색 후보는 빈 목록이 된다. `retrieveCandidates`가 예외를 빈 목록으로 처리하기 때문이다.
  - 디렉터리는 있는데 모델이 손상된 경우는 기존처럼 기동에 실패한다.
- **사용자 식별**: `OpenStudyUserContextResolver.resolve`가 로그인한 직원만 인정한다. 본문 `actor`는 무시하고, 인증이 없으면 `AuthenticationCredentialsNotFoundException`을 던진다.
- **테스트**:
  - `LocalAiFixtures` 조건을 추가했다. 모델이 필요한 테스트는 모델이 있을 때만 돌고, AI 통합 테스트 4종은 모델이 있고 `OPENSTUDY_AI_LOCAL_TESTS=true`일 때만 돈다.
  - 평문 DB 비밀번호는 `${OPENSTUDY_AI_TEST_DB_PASSWORD:}`로 바꿨다.
  - 생명주기 테스트의 부정 사례는 본문 `actor` 대신 해당 직원의 인증으로 호출한다.
- **README**: AI 로컬 실행 절차를 추가했다.
- **함께 커밋한 `[+]` 파일**: 사용자가 옮긴 DomainLens 엔진, AI 모듈, 계약 스키마, V7 마이그레이션, 모델 매니페스트(`config/models`). `[M]` 변경이 이 파일들에 의존한다.

## 5. 검증 결과

- `compileJava compileTestJava`: 성공.
- 모델이 있는 상태에서 `com.openstudy.domainlens.*`, `com.openstudy.modules.ai.*` 실행: DomainLens 테스트 5개 통과(ONNX 임베딩, 시맨틱 카탈로그·임베딩 검색 포함). AI 통합 테스트 23개는 의도대로 건너뜀.
- 모델을 치운 상태에서 전체 `./gradlew test`(87개 스위트, 997개 테스트, 45분):
  - 993개 통과, 25개 건너뜀(AI 통합 23개와 모델이 필요한 2개), 4개 실패.
  - 모델 부재 경고 로그를 남긴 뒤 앱이 정상 기동하는 것을 확인했다.
  - 4개 실패는 모두 최신 마이그레이션을 V6·테이블 66개로 고정한 테스트(`FoundationMigrationTests` 2개, `OwnerBootstrapTests`, `DatabaseInitializationTests`)였다. V7(ai_* 테이블 7개)에 맞게 V7·73개로 고친 뒤, 세 클래스를 다시 실행해 모두 통과했다.
  - 전체 스위트는 이 수정 뒤에 다시 돌리지 않았다.
- `bootJar`: 성공. jar는 ONNX Runtime·토크나이저 네이티브 라이브러리 때문에 약 300MB다.
- **미검증**:
  - AI 통합 테스트 4종(기대값 파일·시드 DB 필요)
  - 실제 Qwen `live` 호출
  - 로그인 세션으로 `/api/ai/**`를 HTTP로 호출하는 시나리오

## 6. 남은 작업 / 보류 사항

- AI 매출 분석을 `SalesAggregationService`에서 공식 매출 서비스(`SalesRevenueService` 등)로 전환한다(M10-D05).
- DomainLens 기대값 파일(`docs/04_TEST_DATA`, `docs/05_TEST_CASES`)을 테스트 리소스로 옮겨 AI 통합 테스트를 저장소 안에서 실행할 수 있게 한다.
- 모델 파일 배포 방법(다운로드 주소·해시 검증)을 정한다. 운영 이미지에 모델이 없으면 AI 검색이 비활성화된다.
- 프런트엔드 이식 시 OpenLens 화면이 `/api/ai/**` 호출에 세션 쿠키와 CSRF 토큰을 보내야 한다.
- 이식본이 추가한 `/api/conversations`, `/api/catalog/readiness` 같은 별칭 경로가 API 계약 문서에 아직 없다.
- 루트 `docker-compose.yml`·`db/` 덤프는 백엔드 저장소 밖 항목이라 이번 커밋에 넣지 않았다.
