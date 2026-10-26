# 초대 관리 명령·예정 권한·발송 작업

관련 이슈: [Backend #13](https://github.com/OpenStudyService/OpenStudy-Backend/issues/13).

## 1. 배경 / 문제

초대 관리 화면은 이미 명세와 MSW를 바탕으로 구현되었지만 실제 초대 관리 API가 없었다. 서버에는 본인 승인 상태 조회만 존재하여 초대 생성·예정 권한 저장·관리 명령·발송 작업의 원자성과 현재 권한 검증이 필요했다.

원래 Backend 체크아웃에는 통합 V1 스키마와 OWNER 초기화 관련 미커밋 변경이 있었다. 최신 원격 main `3484d1c`에서 별도 worktree와 `feature/invitation-management`를 만들어 관련 변경을 분리했다. 원래 변경은 이 작업에 포함하지 않는다.

## 2. 검토한 선택지

초대 모듈에서 직원 테이블을 직접 변경하거나 직원 공개 서비스를 통해 대상 계정·소속을 검사하는 방법을 검토했다. 직접 Repository 접근은 기존 모듈 경계를 깨고 직원 변경·감사 동작을 분산시키므로 공개 서비스의 최소 진입점을 추가하기로 했다.

메일 발송과 수락·승인까지 함께 구현하지 않고 이슈의 관리 명령·발송 작업 예약을 하나의 검증 가능한 단위로 유지했다. worker(#14)와 수락·승인(#15)은 이 결과를 재사용한다.

## 3. 결정 및 이유

기존 OpenAPI의 관리자 DTO와 상태 전이를 구현하고 기존 인가·입력·버전·감사·outbox 기반을 재사용한다. 공통 보안 설정이나 스키마를 새로 설계하지 않는다. 관리 가드와 부모 잠금 아래 현재 계정·역할·권한을 읽어 중복 생성·권한 회수·동시 상태 변경을 직렬화한다.

토큰은 worker가 발송할 때 생성한다는 기존 계약을 유지한다. 관리 명령은 NULL 해시·증가 revision·발송 대기 작업만 예약한다. DB·관리 응답·감사·outbox에 원문 또는 복호화 가능한 토큰을 저장하지 않는다.

반려는 수락된 초대에 대응하는 대기 소속만 회수한다. 전역 계정이나 기존 활성 소속·다른 지점 권한을 일괄 변경하지 않는다.

## 4. 구현 내용

- 초대 목록·상세·생성·재발급·취소·반려 API와 관리자 DTO.
- 예정 권한 snapshot, 정규화 이메일·기존 ACTIVE 소속·진행 초대 중복 검사, 생성 멱등성. 진행 초대가 없는 PENDING 소속 자체는 신규 초대를 차단하지 않는다.
- 만료 PENDING 초대의 신규 발급 시 정리, 수동 재발급 새 24시간·revision·발송 작업 무효화.
- 현재 관리자·초대 역할·대상 소속 재검사, 낡은 version 충돌과 동일 종료 명령의 재조회.
- 직원 공개 서비스를 통한 대상 검사·대기 소속 회수, 원자적 감사·outbox와 기존 SSE 인계.
- [구현 계약](../design/foundation/invitation-management-api.md) 및 OpenAPI의 해당 operation 구현 상태.

OpenAPI는 기존 6개 operation의 `x-implementation-status`만 변경했다. 요청·응답 schema와 계약 버전은 그대로이며 공개 미리보기·수락·승인은 `design-target`으로 남긴다. 기존 SSE dispatcher가 초대 자원을 이미 현재 관리 권한으로 필터링하므로 공통 SSE·보안 설정은 변경하지 않았다.

## 5. 검증 결과

### 실행 환경과 명령

Windows·Java 21.0.12.1·Gradle Wrapper 9.8.1·Docker Desktop Engine 27.2.0에서 기존 Testcontainers MySQL 8.4.11 설정을 사용했다. `.env`를 복사하지 않고 테스트 서비스 연결을 사용하여 사용자 DB에 접근하지 않았다.

```powershell
$env:JAVA_HOME='C:\Users\happysm\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat test bootJar --no-daemon
```

최종 `test bootJar --no-daemon`은 **5분 5초에 BUILD SUCCESSFUL**이었다. JUnit XML 18개 suite를 합산해 **138개, 실패·오류·건너뜀 0**을 확인했다. 신규 초대 통합 테스트는 16개이며 모듈 경계 1개와 기존 인증·조직·세션·DB·SSE 회귀를 함께 통과했다. 실행 JAR `build/libs/openstudy-backend-0.0.1-SNAPSHOT.jar`를 확인했다.

| 인수 조건 / 주요 위험 | 실제 확인 결과 |
| --- | --- |
| 초대 입력·예정 권한·발송 상태 | HTTP 201·상세 조회, 정규화 이메일, 프리셋 변경 후 권한 snapshot 보존, 별도 delivery, 관리자 응답·감사의 비밀값 미포함 |
| 현재 역할·범위·CSRF | OWNER/MANAGER/STAFF, STAFF의 people:manage 우회 거부, 타 지점 total·상세·명령 미노출, 대상 OWNER/MANAGER·제한 세션·CSRF·잘못된 입력 거부 |
| 중복·멱등성 | 활성 소속·진행 초대 중복 409, 다른 키 동시 생성 1성공/1충돌, 같은 키 동시 생성 단일 초대·응답 재사용, 다른 입력·권한 회수 후 재사용 거부 |
| 만료·재발급 | 스케줄러 지연 시 기존 PENDING 만료·신규 생성, EXPIRED/PENDING 재발급의 새 24시간·revision·단일 신규 작업·기존 해시 폐기, 만료 조회·필터 |
| 취소·반려·버전 | 수락 전 취소·수락 후 반려, 낡은 version 409 메타데이터, 동일 종료 상태 재조회 전 현재 대상 역할 검사, ACCEPTED 기한 미적용·수락 이력 보존 |
| 소속 보존 | 일치하는 PENDING 소속만 회수, ACTIVE·다른 지점·계정 보존, PENDING 역할 불일치 409 |
| 경합·롤백 | 별도 DB 트랜잭션의 수락 잠금 뒤 취소가 대기하고 ACCEPTED를 덮어쓰지 않음, 초대·권한·작업·감사·outbox·멱등 기록의 전체 롤백 |

수락 경합은 아직 구현하지 않은 #15 API 대신 DB 트랜잭션으로 상태를 구성한 시험이다. 실제 수락 API나 메일 수신의 성공으로 해석하지 않는다. 브라우저·SMTP·운영 프록시 검증은 수행하지 않았다.

### 구현 중 발견·수정한 실패

- 첫 집중 실행은 초대 14개·모듈 경계 1개 중 멱등 재요청 2개가 500으로 실패했다. MySQL DATETIME을 `queryForList`의 Object에서 Timestamp로 직접 변환한 것이 원인이었다. typed RowMapper에서 `getTimestamp(...).toInstant()`와 명시 해시·JSON 타입을 사용하도록 수정했다.
- 추가 회귀를 포함한 첫 전체 실행은 18개 suite·138개 중 1개 실패, 오류·건너뜀 0이었다. 멱등 재요청은 통과했고 만료 테스트의 fixture가 `status='EXPIRED'`만 설정하여 `ck_invitations_token_disposal`을 위반했다. 프로덕션은 이미 해시를 폐기하고 있었으며, fixture도 `token_hash=NULL`을 함께 지정하도록 수정했다. 이 실행을 전체 성공으로 집계하지 않는다.
- 코드 검토에서 관리 범위가 없는 STAFF의 정상 빈 목록 응답을 403으로 바로잡고, 변경 명령도 상세 조회와 같은 가시성 검사를 거쳐 범위 밖 ID의 존재 여부를 노출하지 않도록 보완했다. 반려 대상의 PENDING 소속 역할이 초대와 달라진 경우에도 409로 거부한다.

### 계약·탐색 자료

- OpenAPI JSON과 local `$ref` 549개 해석을 확인했다. Git 기준본과 비교해 변경이 6개 구현 상태 표기뿐임을 검사했다. 요청·응답·버전은 유지하며 미리보기·수락·승인은 구현 전 표기다.
- `git diff --check` 통과.
- 최종 `graphify update .`로 AST 그래프 1,590개 노드·4,127개 관계를 갱신했다. SQL parser·PDF 추출 의존성 부재와 Gradle parser 경고는 그래프의 탐색 한계이며 Java/SQL 실행 검증과 구별한다. 그래프는 기존 main에서 추적하지 않던 로컬 탐색 자료로 보관한다.

## 6. 남은 작업 / 보류 사항

- #14: SMTP·worker lease·revision별 발송과 자동 재시도, 실제 수신 확인.
- #15: 공개 미리보기·신규/기존 계정 수락·최종 승인.
- #18: 실제 메일 링크와 두 브라우저의 전체 초대 흐름 및 운영 HTTPS 검증.
- 기존 사용자 체크아웃의 통합 V1 변경과 운영 DB 인계는 이 작업의 검증 대상이 아니다.
- 작업 브랜치 push·PR 생성 후 main 반영과 PR 병합은 사용자가 수행한다.
