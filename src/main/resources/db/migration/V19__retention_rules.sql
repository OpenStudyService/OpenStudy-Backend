-- #100 (ADR-M11-001 M11-D01·D05, ADR-M11-002): 정보 종류별 파기·정리 규칙과 처리 기록.
-- 모든 규칙은 비활성으로 시작한다. 운영자가 수동 백업 뒤 문서 절차(docs/privacy/personal-information-handling.md)로 켠다.
-- 기간 없이 켤 수 없고, 법정 최소 보관 기간보다 짧게 둘 수 없다.

CREATE TABLE `retention_rules` (
  `code` varchar(40) NOT NULL,
  `description` varchar(200) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '0',
  `period_days` int unsigned DEFAULT NULL,
  `legal_minimum_days` int unsigned DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`code`),
  CONSTRAINT `ck_retention_rules_1` CHECK ((`enabled` = 0) OR (`period_days` IS NOT NULL)),
  CONSTRAINT `ck_retention_rules_2` CHECK ((`period_days` IS NULL) OR (`legal_minimum_days` IS NULL) OR (`period_days` >= `legal_minimum_days`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='정보 종류별 파기·정리 규칙(#100)';

-- 확정 항목(ADR-M11-002)은 기간을 넣어 두되 비활성이다. 미결정 항목(거래·문의·근태)은 규칙을 두지 않는다.
-- 감사 이벤트는 법정 접속기록(1년)·권한 이력(3년)이 섞여 있어 최소 3년이며, 일반 감사 기간이 미결정이라 기간이 없다.
INSERT INTO `retention_rules` (`code`, `description`, `period_days`, `legal_minimum_days`) VALUES
  ('CUSTOMER_IDENTITY', '고객 식별 정보 익명화: 마지막 이용 후 기간(M11R-D01)', 365, NULL),
  ('AI_CONVERSATION', 'AI 대화 삭제: 마지막 질문 후 기간(M11R-D06)', 365, NULL),
  ('ACCOUNT_MAIL_HISTORY', '재설정 링크·초대 발송 작업·가입하지 않은 초대 삭제: 끝난 뒤 기간(M11R-D10)', 30, NULL),
  ('IDEMPOTENCY_RECORD', '멱등 기록 삭제: 만료 뒤 기간(M11R-D11)', 0, NULL),
  ('AUDIT_EVENT', '감사 이벤트 삭제: 발생 후 기간(M11R-D07, 기간 미결정)', NULL, 1095);

-- 규칙 실행마다 한 행. 대상 수·처리 수·결과만 남기고 파기한 값은 남기지 않는다.
CREATE TABLE `retention_runs` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `rule_code` varchar(40) NOT NULL,
  `period_days` int unsigned NOT NULL,
  `cutoff_at` datetime(6) NOT NULL,
  `started_at` datetime(6) NOT NULL,
  `finished_at` datetime(6) NOT NULL,
  `target_count` int unsigned NOT NULL DEFAULT '0',
  `processed_count` int unsigned NOT NULL DEFAULT '0',
  `result` enum('SUCCESS','FAILED') NOT NULL,
  `error_code` varchar(120) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_retention_runs_1` (`rule_code`, `started_at`),
  CONSTRAINT `fk_retention_runs_1` FOREIGN KEY (`rule_code`) REFERENCES `retention_rules` (`code`) ON DELETE RESTRICT,
  CONSTRAINT `ck_retention_runs_1` CHECK ((`result` = 'FAILED') = (`error_code` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='파기·정리 처리 기록(#100)';

-- 익명화한 고객은 행을 남기고 식별 필드만 바꾼다(M11-D05). 다시 처리하지 않도록 시각을 남긴다.
ALTER TABLE `customers`
  ADD COLUMN `anonymized_at` datetime(6) DEFAULT NULL AFTER `memo`;
