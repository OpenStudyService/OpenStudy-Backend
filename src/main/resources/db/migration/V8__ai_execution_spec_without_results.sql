-- ADR-M10-002 M10-D12: AI 대화·실행에는 질문, 검증된 실행 명세, 처리 상태, 기준 시각만 남기고 결과 데이터는 저장하지 않는다.
-- 결과가 필요한 곳(대화 다시 열기, 실행 재조회, 작업 기록 열기, CSV 반출)은 현재 권한으로 다시 실행한다.

-- 1) 결과 데이터 제거: 이미 저장된 결과 값도 함께 지운다. 질문·명세·상태 행은 그대로 남는다.
ALTER TABLE `ai_executions`
  DROP COLUMN `envelope_json`,
  DROP COLUMN `legacy_execution_json`;

ALTER TABLE `ai_work_records`
  DROP COLUMN `snapshot_json`;

-- 2) 실행 명세 보존: 분석 ID만으로는 담기지 않는 질문의 조건을 남겨, 다시 실행해도 질문의 의미가 같게 한다.
--    refund_basis: 질문이 요청한 환불 기준(NULL이면 기본값인 환불 처리일 기준), zero_fill_requested: 0원인 날짜 채우기 요청.
--    V8 이전에 저장된 질문은 조건을 알 수 없어 기본값으로 남는다.
ALTER TABLE `ai_executions`
  ADD COLUMN `refund_basis` varchar(40) DEFAULT NULL AFTER `view_spec_json`,
  ADD COLUMN `zero_fill_requested` tinyint(1) NOT NULL DEFAULT '0' AFTER `refund_basis`;
