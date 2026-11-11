-- #125 (analysis-reuse-api.md, ADR-M15-001 M15-D04·D05): 필터 수정으로 생긴 실행은 사용자가 고친 조건으로 다시 실행돼야 한다.
--   period_from·period_to: 사용자가 고친 기간(Asia/Seoul 날짜, 양 끝 포함). NULL이면 분석의 기본 기간이다.
--   product_ids_csv: 상품 조건. NULL이면 전체 상품이다.
--   origin: 질문(QUESTION)인지 필터 수정(REFINEMENT)인지. 기존 행은 모두 질문이다.
--   source_execution_id: 필터 수정의 원래 실행. 같은 대화 안의 실행이라 대화를 지우면 함께 지워진다.
ALTER TABLE `ai_executions`
  ADD COLUMN `period_from` date DEFAULT NULL AFTER `zero_fill_requested`,
  ADD COLUMN `period_to` date DEFAULT NULL AFTER `period_from`,
  ADD COLUMN `product_ids_csv` varchar(500) DEFAULT NULL AFTER `period_to`,
  ADD COLUMN `origin` varchar(20) NOT NULL DEFAULT 'QUESTION' AFTER `product_ids_csv`,
  ADD COLUMN `source_execution_id` varchar(64) DEFAULT NULL AFTER `origin`;
