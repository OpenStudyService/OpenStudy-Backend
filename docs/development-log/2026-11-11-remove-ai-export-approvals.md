# 2026-11-11 이식된 AI 반출 승인 기능 제거 (#214)

## 1. 배경 / 문제

- M15 CSV 내보내기는 승인 없이 권한을 다시 확인해 바로 내려주고 감사에 남긴다(ADR-M15-001 M15-D09, 2026-11-11 사용자 결정).
- 그런데 DomainLens에서 이식된 반출 승인 기능이 계약 밖에 그대로 남아 있었다.
  - 경로: `GET·POST /api/ai/approvals`, `POST /api/ai/approvals/{id}/decide`, `GET /api/ai/approvals/{id}/download`
  - 저장: `ai_export_approvals` 테이블(V7)
  - 승인 권한은 "OWNER 또는 아무 지점의 MANAGER"였다. 권한 기준이 정해지지 않은 쓰기 경로였다.
- DomainLens 엔진 쪽에도 승인 코드(`ApprovalService`, `ApprovalStore`, `JpaApprovalStore`, `ApprovalPolicy`)가 있었다.

## 2. 검토한 선택지

| 항목 | 선택지 | 결과 |
| --- | --- | --- |
| `ai_export_approvals` 테이블 | 새 마이그레이션(V18)으로 삭제 / 유지 | V18로 삭제(2026-11-11 사용자 결정). 쓰는 코드가 없는 테이블을 남기지 않는다. 운영 출시(M11) 전이라 보존할 기록이 없다고 보았다. 운영 DB의 행은 확인하지 못했다(접근 권한 없음) |
| DomainLens 승인 코드 | 승인 클래스만 삭제하고 쓰는 쪽을 고침 / 승인에만 쓰이는 묶음을 함께 삭제 | 묶음 삭제 + 한 클래스 수정. 자세한 내용은 아래 |

## 3. 결정 및 이유

- **DomainLens 쪽 확인 결과**
  - 승인 클래스를 쓰는 곳은 `ArtifactService`, `EvaluationOperations`, `WorkRecordService` 셋뿐이었다.
  - 이 셋은 어디서도 만들지 않는다(Spring bean도 아니고 `new`로 만드는 곳도 없다).
  - 다음 클래스들은 승인 흐름에만 쓰이므로 함께 지웠다.
    - `ArtifactService`, `ArtifactStore`, `JpaArtifactStore`: 승인된 반출 파일만 다룬다.
    - `EvaluationOperations`: 승인·반출 시나리오를 평가한다.
  - `WorkRecordService`는 업무 기록 쪽이라(이슈 제외 범위) 지우지 않았다. 승인·반출 필드와 상태 계산만 뺐다.
  - `ExportExecutor`·`CsvWriter`는 다른 곳(`JpaQueryExecutor`)이 쓰거나 #127 CSV에서 쓸 수 있어 남겼다.
- **V7은 수정하지 않았다.** 새 V18이 `DROP TABLE`만 한다.

## 4. 구현 내용

- `modules/ai`
  - `AiWorkspaceController`: 승인 경로 4개(조회·요청·결정·다운로드)와 요청 record를 지웠다.
  - `AiWorkspaceService`: 승인 메서드, `toApprovalDto`, `selectDataset`, 개인정보 열 제외 목록(승인 다운로드 전용)을 지웠다.
  - `AiExportApprovalEntity`, `AiExportApprovalRepository`를 지웠다.
- `domainlens`
  - `ApprovalService`, `ApprovalStore`, `JpaApprovalStore`, `ApprovalPolicy`, `ArtifactService`, `ArtifactStore`, `JpaArtifactStore`, `EvaluationOperations`를 지웠다.
  - `WorkRecordService`에서 승인 필드를 뺐다.
- `V18__drop_ai_export_approvals.sql`
- 계약 문서: `nl-analysis-api.md`·`analysis-reuse-api.md`에 제거 사실을 적었다.
- 테스트
  - `AiContractTests`: 승인 경로가 없음(404)과 승인 클래스·테이블을 참조하는 Java 소스가 없음을 확인한다.
  - 마이그레이션 버전 고정 테스트 3개를 갱신했다(18개, 테이블 77개). V18 테스트를 추가했다.
  - 승인 경로를 쓰던 통합 테스트 두 곳(`OpenStudyAiLifecycleAndNegativeCasesTests`, `AiExecutionOwnershipIntegrationTests`)에서 승인 부분을 지웠다.

## 5. 검증 결과

- 실행함
  - `./gradlew test --tests "com.openstudy.architecture.*" --tests "com.openstudy.modules.ai.service.*"`: 16개 클래스 123건 통과
    - 기존 AI 계약 operation 10개 검사(`AiContractTests`) 포함
  - `FoundationMigrationTests` 25건(새 V18 테스트 포함), `OwnerBootstrapTests` 10건, `DatabaseInitializationTests` 4건 통과(`-PtestForks=1`)
- 실행하지 않음(마일스톤 검증 때 실행)
  - 승인 부분을 지운 AI 통합 테스트 두 개
  - 전체 `./gradlew test`
  - 운영 DB의 `ai_export_approvals` 행 확인
- `graphify update .`: 이 worktree에 `graphify-out`이 없어 실행하지 않았다.

## 6. 남은 작업 / 보류 사항

- 운영 DB에 승인 기록이 있다면 V18 적용 전에 보존 여부를 확인해야 한다. 지금은 출시 전이라 없다고 보았다.
- #127 CSV 내보내기에서 `CsvWriter` 재사용 여부를 정한다.
- 업무 기록·진단 로그·품질 평가는 이번 범위가 아니다. 쓰이지 않는 DomainLens 코드 정리(2026-11-11 감사 6번)도 별도로 다룬다.
