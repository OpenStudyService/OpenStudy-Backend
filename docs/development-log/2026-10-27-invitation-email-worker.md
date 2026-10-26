# 초대 이메일 worker·revision·재시도

관련 이슈: [Backend #14](https://github.com/OpenStudyService/OpenStudy-Backend/issues/14). 선행 [PR #34](https://github.com/OpenStudyService/OpenStudy-Backend/pull/34)는 사용자가 병합했다.

## 1. 배경 / 문제

#13은 초대·예정 권한·발송 대기 작업을 원자적으로 예약하지만 실제 메일 발송과 장애 복구는 제공하지 않았다. 원문 토큰을 영속화하지 않으면서도 발송 실패·프로세스 재시작·늦은 완료를 복구하고, 취소·수락 이후의 오래된 발송을 차단할 worker가 필요했다.

사용자 요청대로 로컬 main에서 `git pull --ff-only origin main`을 실행하여 `d7fe106`까지 반영했다. 기존 미커밋 파일의 SHA-256이 pull 전후 일치함을 확인하고 main에서 별도 `feature/invitation-email-worker` worktree를 만들었다. 사용자의 통합 V1·OWNER 수정은 이 작업에 포함하지 않는다.

## 2. 검토한 선택지

- SMTP 동안 초대 잠금을 유지하면 완료 순서를 단순화할 수 있지만 네트워크 지연이 관리 명령과 DB를 막는다. 짧은 claim·preflight·완료 트랜잭션과 lease/revision 검사를 선택했다.
- 초대 부모만 잠그는 worker는 감사/outbox의 branch FK 공유 잠금과 관리자의 branch→초대 잠금 사이에서 역순 경합을 만들 수 있었다. 기존 관리 가드를 먼저 잡는 방식으로 통일했다.
- 상위 트랜잭션을 중지하는 방식도 검토했지만 중지된 호출자의 DB 잠금은 남을 수 있다. worker는 상위 트랜잭션 호출을 거부하고 자체 짧은 트랜잭션만 사용한다.
- Spring의 기본 단일 스케줄러에 SMTP를 올리면 SSE 권한 재검사가 전송 지연에 막힐 수 있어 전용 스케줄러를 선택했다.

## 3. 결정 및 이유

기존 V1~V3의 초대·발송 job·lease 컬럼을 재사용한다. 각 claim은 32바이트 난수 토큰을 만들고 SHA-256만 저장한다. 자동 재시도와 lease 회수는 새 revision/job으로 이전 해시를 폐기하며 최초 TTL을 유지한다. 외부 SMTP 수락과 DB 완료의 원자성은 제공할 수 없으므로 늦은 완료를 fence하고 불확실한 링크를 새 revision으로 대체한다.

발송은 기본 비활성이며 실제 SMTP 설정을 명시해야 실행한다. 제공자를 임의로 선택하거나 기존 환경의 자격증명을 읽어 실제 메일을 보내지 않는다. 예외와 디버그 출력으로 토큰이 노출되지 않도록 SMTP 어댑터와 설정을 함께 검증한다. 설정 기본값은 기술적 선택이며 사용자 확정 정책과 구분한다.

## 4. 구현 내용

- 제한된 후보 탐색, 가드→초대→job 잠금, 짧은 claim·preflight·완료 트랜잭션.
- worker·lease·revision·TTL·토큰 해시 일치 확인과 누적 자동 재시도 예산.
- SMTP 실패·성공 기록 유실·lease 만료 시 새 revision 및 원래 TTL 유지.
- 토큰 원문 없는 DB/job/감사/outbox, redacted claim 객체, 고정 오류 코드와 예외 cause 제거.
- UTF-8 일반 텍스트 SMTP 어댑터, 실제 frontend 수락 경로의 fragment 링크.
- 기본 비활성 설정, 주소·URL·debug·유한 socket timeout·poll/batch/lease 검증과 전용 스케줄러.
- [메일 발송·장애 복구 계약](../design/foundation/invitation-delivery.md)과 `.env.example` 안내. HTTP schema·DB migration은 변경하지 않는다.

## 5. 검증 결과

Windows·Java 21.0.12.1·Gradle Wrapper 9.8.1·Docker Desktop Engine 27.2.0에서 기존 Testcontainers MySQL 8.4.11을 사용했다.

```powershell
$env:JAVA_HOME='C:\Users\happysm\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test bootJar --no-daemon
```

최종 전체 실행은 **5분 2초에 BUILD SUCCESSFUL**이었다. JUnit XML 21개 suite를 합산해 **161개, 실패·오류·건너뜀 0**을 확인했다. 신규 worker DB 13개·SMTP 5개·설정 5개와 기존 인증·조직·초대·세션·DB·SSE·모듈 경계 회귀를 통과했다. `build/libs/openstudy-backend-0.0.1-SNAPSHOT.jar`도 확인했다.

`git diff --check`를 통과했다. `graphify update .`는 AST 그래프 1,792개 노드·4,722개 관계로 갱신했다. SQL/PDF parser 의존성 부재 및 Gradle AST 경고는 탐색 자료의 한계이며 실제 Java/MySQL 실행 검증과 구별한다. main에서 추적하지 않던 그래프는 로컬 탐색 자료로 보관한다.

첫 집중 실행은 38개 중 3개 실패였다. 두 테스트의 장애 주입용 trigger가 Testcontainers 계정에 SUPER 권한을 요구한 경우라 DB 권한을 바꾸지 않고 실제 SQL을 사용하는 테스트 repository에서 감사 저장 실패를 주입하도록 변경했다. 실제 TransactionTemplate과 SQL의 commit/rollback 검증은 유지한다. 이 실행을 성공으로 집계하지 않는다.

수정 후 집중 실행은 40개 중 39개가 통과하고 로컬 SMTP 수신 1개가 실패했다. Windows의 테스트 SMTP 연결에서 응답 읽기가 중단되었으며, 테스트용 `mail.host`·`mail.user`·EHLO hostname을 고정하고 수신 소켓 시간을 명시한 뒤 실제 SMTP 수신 단일 검사가 12초에 BUILD SUCCESSFUL이었다. 정확한 내부 DNS 단계는 따로 추적하지 않았으므로 이를 확인된 제품 SMTP 결함으로 기록하지 않는다. 제품의 예외 비밀값 제거는 그대로 두고 fixture의 hostname·통신 시간 조건을 안정화했다.

### 시험 범위

신규 검사는 worker DB 기반과 SMTP/config를 합쳐 23개다. 기존 초대 관리 HTTP/DB 16개 및 모듈 경계도 집중 실행에 포함했다.

| 검증 대상 | 방법 |
| --- | --- |
| 실제 메일·fragment 링크 | 외부 전달 없는 loopback ServerSocket SMTP 수신기, 실제 JavaMail UTF-8 MIME·단일 수신자·`#token=` 검사 |
| 비밀값 비노출 | DB/job/감사/outbox/멱등 저장소·captured 로그에 원문 토큰 부재, 메시지·cause에 토큰을 넣은 SMTP 오류의 제거 |
| claim·재시도·TTL | 전용 MySQL·제어 Clock, SHA-256 일치·revision 회전·원래 만료 시각 보존·재시도 한도 |
| 프로세스 재시작·lease·늦은 완료 | 독립 worker/service 인스턴스, lease 만료 뒤 새 revision, 이전 완료의 상태 덮어쓰기 거부 |
| SMTP 수락 후 DB 기록 유실 | 실제 SQL 변경 뒤 감사 저장 실패 주입·rollback, PROCESSING 복구·이전 링크 대신 새 토큰 |
| 취소·수락·경합 | 전송 전 상태 차단, 전송 중 취소·수동 재발급, 두 worker 동시 claim·DB 잠금 없는 SMTP |
| 설정·스케줄러 | 기본 비활성, 정상 활성화·독립 스케줄러, 주소/URL·debug·timeout·lease·작업량 검증, 상위 트랜잭션 거부 |

수락은 아직 구현하지 않은 #15 API 대신 테스트 DB에 ACCEPTED 상태를 구성했다. 실제 미리보기·수락·승인을 검증한 것으로 해석하지 않는다. 재시작 복구는 원문 토큰을 갖지 않은 새 service/worker 인스턴스를 구성해 검증했으며 실제 운영 JVM을 강제 종료한 시험과 구별한다. 메일 worker 테스트는 `@MySqlIntegrationTest`의 일회용 MySQL만 사용하며 원래 체크아웃의 `.env`·사용자 DB를 읽지 않는다.

## 6. 남은 작업 / 보류 사항

- 실제 운영 SMTP 제공자의 수신함·자격증명·TLS·발신 도메인·스팸 분류 검증은 수행하지 않았다. 로컬 수신기 성공과 구분하여 #14에서 계속 추적한다.
- #15의 공개 미리보기·수락·최종 승인과 #18의 실제 메일 링크·두 브라우저·운영 HTTPS 전체 흐름은 후속 범위다.
- 전송 직전 검사와 SMTP 사이의 취소 경합에서 이미 전송 중인 메일을 회수하거나 정확히 한 번만 도착하도록 보장하지 않는다. 이전 링크 무효화와 최신 상태 보호를 보장한다.
- 작업 브랜치 push·PR 생성 이후 main 반영과 병합은 사용자가 수행한다.
