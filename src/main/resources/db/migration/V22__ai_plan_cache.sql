-- 2026-11-16: AI 질문의 조회 계획 캐시. 같은 뜻의 질문이면 LLM을 다시 부르지 않고 저장된 계획을 재사용한다.
-- 조회 결과(데이터)는 저장하지 않는다. 계획을 재사용할 때도 요청자의 권한·지점 범위로 다시 검증·실행한다.
-- kind: REGISTERED_PICK(등록 분석 선택) 또는 GENERATED(생성 경로의 QuerySpec·ViewSpec).
-- version_key가 다른 행(카탈로그·모델·프롬프트 변경 전)은 조회하지 않는다. expires_at이 지나면 쓰지 않는다.
CREATE TABLE `ai_plan_cache` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `kind` varchar(20) NOT NULL,
  `version_key` varchar(64) NOT NULL,
  `question_key` char(64) NOT NULL,
  `question_text` varchar(500) NOT NULL,
  `vector_json` mediumtext NOT NULL,
  `analysis_id` varchar(80) DEFAULT NULL,
  `intent_domain` varchar(30) DEFAULT NULL,
  `refund_basis_override` varchar(40) DEFAULT NULL,
  `zero_fill_override` tinyint(1) NOT NULL DEFAULT 0,
  `query_spec_json` mediumtext DEFAULT NULL,
  `view_spec_json` mediumtext DEFAULT NULL,
  `hit_count` int NOT NULL DEFAULT 0,
  `created_at` datetime(6) NOT NULL,
  `last_used_at` datetime(6) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_plan_cache_question` (`question_key`),
  KEY `idx_ai_plan_cache_lookup` (`kind`, `version_key`, `expires_at`)
);
