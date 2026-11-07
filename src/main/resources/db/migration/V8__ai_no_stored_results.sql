-- ADR-M10-002 M10-D12: AI 대화·실행에는 질문, 검증된 QuerySpec/ViewSpec, 처리 상태, 기준 시각만 남기고 결과 데이터는 저장하지 않는다.
-- 결과가 필요한 곳(실행 재조회, 작업 기록 열기, CSV 반출)은 현재 권한으로 다시 실행한다. 이미 저장된 결과 값도 함께 지운다.
ALTER TABLE `ai_executions`
  DROP COLUMN `envelope_json`,
  DROP COLUMN `legacy_execution_json`;

ALTER TABLE `ai_work_records`
  DROP COLUMN `snapshot_json`;
