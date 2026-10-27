# 통합 V1 개발 기준선 채택과 DB 재생성 방침

## 1. 배경 / 문제

로컬에는 통합 V1과 OWNER 초기화 보완이 있었지만 원격 main `89f52e6`은 기존 V1~V3과 최소 버전 3 검사를 사용했다. PR #33은 문서 초안만 반영했고, 후속 PR들은 이 로컬 변경을 의도적으로 포함하지 않았다. 현재 변경을 단순 push하면 기존 DB의 체크섬 충돌 및 버전 3을 기대하는 시험 실패를 유발한다.

사용자는 2026-10-27 본인·팀원 로컬 및 공유 개발 환경의 DB에 보존할 업무 데이터가 없고 모두 재생성 가능한 테스트 데이터임을 확인했다. 이 답변을 근거로 새 빈 DB 전환을 선택했다. main에서 `chore/consolidated-v1-rollout` 브랜치를 만들고 기존 로컬 변경 중 이번 작업과 관련된 것만 포함한다. AGENTS.md 삭제, 별도 DB 설계·백업·ZIP과 다른 개발 기록은 이 작업에서 스테이징하지 않는다.

## 2. 검토한 선택지

- 기존 V1~V3과 증분 SQL 유지: 데이터 보존에는 필요하지만 현재 보존할 데이터가 없어 이관·백필·레거시 검사 경로를 계속 유지할 실익이 적다.
- 통합 V1로 새 DB 재구축: 팀 기준을 단일 초기 스키마로 맞출 수 있다. 기존 DB와 코드의 동시 전환 및 계정·테스트 데이터 재생성이 필요하다.

## 3. 결정 및 이유

통합 V1을 새로운 개발 기준선으로 채택하고 기존 테스트 데이터를 이관하지 않는다. 팀원 로컬은 새 빈 스키마로 바꾸고, 공유 DB는 사용 중단 시간을 맞춰 새 스키마 적용·검증 후 연결을 전환한다. 기존 스키마는 확인이 끝날 때까지 남기며 실제 삭제는 별도 작업이다. 이후 변경은 V2부터 추가하고 적용된 V1은 수정하지 않는다. 자동 clean/baseline/repair 및 history 조작으로 기존 DB를 호환시키지 않는다.

Backend가 migration SQL과 버전의 정본을 관리한다. Infra는 인스턴스·접속·권한을 관리하며 새 스키마에 자체 구형 V1을 중복 적용하지 않아야 한다. 실제 실행 주체는 배포 전에 하나로 맞춘다. 현재 Infra의 구형 V1과 `SPRING_FLYWAY_ENABLED=false`를 그대로 두고 Backend만 업데이트하는 것으로는 서버 전환이 완료되지 않는다.

## 4. 구현 내용

- 사용자가 준비한 통합 V1과 V2/V3 제거를 실행 경로에 반영한다. V1의 채택 상태를 나타내는 주석을 수정했으며 SQL 본문은 유지한다. 이 주석 변경도 Flyway 체크섬에 영향을 주므로 채택 전 통합 V1을 적용한 로컬 DB 역시 validation 결과에 따라 새로 준비해야 한다.
- 기존 로컬 OWNER 변경을 함께 반영한다. 적용 이력 존재·pending 없음·Flyway validate를 요구하고 번호 3에 묶인 조건을 제거한다. 초기화 명령은 migration을 실행하지 않는다.
- 레거시 이관 시험 3개를 빈 DB 생성·재시작 데이터 보존·다른 V1 체크섬 거부·이력 없는 비어 있지 않은 DB 거부 시험으로 정리한다. 기존 제약·전체 SQL 복원·OWNER 회귀 시험을 유지한다.
- 새 결제 수량 CHECK, 결제당 이용권 유일성, 구매 revision/상품 복합 FK, 기간권 활성화 상태 및 구매 스냅샷 보존을 DB에서 검사한다. 승인 결제와 발급을 원자적으로 묶는 서비스, 최초 입실의 동시성 및 정확히 N×24시간 계산은 이번 DB 제약 시험만으로 검증했다고 주장하지 않는다.
- 전체 시험에서 상품 변경 회귀의 옛 구매 fixture가 `issued_pass_quantity` 기본값 0으로 승인 결제를 만들던 문제가 드러났다. 승인 수량 1, 생성 당시 revision 및 분류 스냅샷을 채우도록 보완하고 상품 변경 이후 구매 revision 참조도 유지되는지 검사한다. 상품 서비스 동작은 변경하지 않았다.
- DB 안내와 팀 전환 절차를 갱신하고 초안·기반 설계에는 현재 안내 링크를 추가한다. 과거 개발·검증 기록은 유지한다.
- 전체 구축 SQL과 ERD는 통합 V1을 적용한 일회용 MySQL 결과로 갱신한다. 원시 metadata와 일회성 제작 스크립트는 커밋하지 않는다.

## 5. 검증 결과

- 1차 집중 검증: Java 21, Testcontainers MySQL 8.4.11에서 `gradlew.bat test --tests '*FoundationMigrationTests' --tests '*DatabaseInitializationTests' --tests '*OwnerBootstrapTests' bootJar -PfoundationExportDir=build/consolidated-v1-export --no-daemon` 성공, 2분 34초. 3개 suite 19개, 실패·오류 0. 이 실행 이후 새 구매 제약 시험과 복원 FK 비교를 추가했으므로 최종 전체 결과는 아래에 별도로 기록한다.
- 첫 전체 검증: 27개 suite 211개 중 1개 실패, 오류·건너뜀 0, 6분 4초. 상품 구매 fixture가 새 `ck_payments_issued_pass_quantity`를 위반한 것이 원인이며 위와 같이 보완했다. 나머지 210개는 통과했다. 수정 후 최종 결과는 다음과 같다.

- 최종 `gradlew.bat test bootJar -PfoundationExportDir=build/consolidated-v1-export --no-daemon`: **27개 suite, 211개 모두 통과**, 실패·오류·건너뜀 0. Java 21·Testcontainers MySQL 8.4.11, 7분 31초 BUILD SUCCESSFUL. 최종 결과의 SQL을 다시 복사했다.
- 65개 업무·세션 테이블, 628개 컬럼, 150개 물리 FK를 metadata와 ERD에서 대조했다. 전체 SQL은 Flyway history를 포함한 66개 테이블이며 업무 계정 INSERT가 없다. 추출 SQL의 빈 DB 복원 후 Flyway validate·재실행과 컬럼/FK metadata 일치를 시험했다.
- 변경 안내 문서 8개의 로컬 링크와 `git diff --cached --check`를 통과했다.
- `graphify update .` 완료(2,281 nodes, 6,394 edges). SQL parser 미설치와 Gradle AST 경고는 그래프 탐색의 제한이며 실제 MySQL·Java 시험 성공과 구분한다. 원래 추적되지 않던 graphify 산출물·캐시는 새로 커밋하지 않는다.

## 6. 남은 작업 / 보류 사항

- 실제 팀원·공유 DB 전환, OWNER 생성, 업무 데이터 재생성 및 브라우저 확인은 수행하지 않았다.
- Infra 적용 주체·구형 migration 중단·RDS 계정/TLS 인계는 Backend #29와 Infra #1에서 진행한다. 이 작업에서 Terraform/AWS 또는 실제 DB를 변경하지 않는다.
- Infra #1의 기존 본문은 V3 업그레이드와 DB 재생성 제외를 전제한다. 현재 사용자가 확인한 테스트 데이터 재생성 방침으로 대체해야 하며, 이전 계획은 이력으로 보존하고 새로운 인수 조건과 Backend PR을 연결한다.
- 작업 브랜치 push·PR 준비 이후 main 병합은 사용자가 진행한다. PR 준비와 실제 환경 전환 완료를 구분한다.

## 관련 자료

- [통합 V1 초안 PR #33](https://github.com/OpenStudyService/OpenStudy-Backend/pull/33)
- [Backend 배포·DB 계약 #29](https://github.com/OpenStudyService/OpenStudy-Backend/issues/29)
- [Infra DB/Flyway 인계 #1](https://github.com/OpenStudyService/OpenStudy-Infra/issues/1)
- [현재 DB 안내](../db/README.md), [팀 전환 절차](../db/foundation-upgrade.md)
- [OWNER 초기화 보완 기록](2026-10-26-owner-bootstrap-consolidated-v1.md). 당시 집중 시험과 이번 전체 검증을 구분한다.

## 전달 상태 (2026-10-27)

- 구현 커밋 `6dc4739`를 `chore/consolidated-v1-rollout`으로 push하고 [PR #40](https://github.com/OpenStudyService/OpenStudy-Backend/pull/40)을 생성했다. main은 변경하지 않았으며 사용자 병합 대기다.
- Infra #1에 기존 내용을 보존하면서 새 재생성 방침·인수 조건·PR을 연결하려 했지만 GitHub 연동이 `403 Resource not accessible by integration`으로 거부했다. 이슈의 제목·본문은 변경하지 못했다. 기존 V3 이관 계획을 현재 방침으로 해석하지 않으며 담당자가 갱신해야 한다.
- 이슈 수정 문안은 작업공간 `outputs/2026-10-27-infra-db-handoff-issue.md`에 로컬 인계 자료로 보관했다. 코드 저장소에 중간 이슈 본문을 중복 등록하지 않는다. 새 방침과 실제 DB 적용 전 인계 조건은 이 PR의 전환 안내에도 포함돼 있다.
