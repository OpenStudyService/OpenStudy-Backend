-- Daily closings and their adjustments (Issue #115, ADR-M13-001 M13-D04·D08·D11, operations-extension-api.md "일별 마감").
-- A closing is one branch and one ended Asia/Seoul day, closed once. It keeps the M9 totals of that day for both refund
-- bases as a JSON snapshot; nothing is blocked by it. A refund of a payment approved on a closed day leaves one
-- adjustment row in the refund's transaction; its reason stays in refunds and is not copied.
CREATE TABLE `daily_closings` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `business_date` date NOT NULL,
  `note` varchar(500) DEFAULT NULL,
  `snapshot_json` json NOT NULL,
  `closed_by` bigint unsigned NOT NULL,
  `closed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_closings_1` (`branch_id`, `business_date`),
  KEY `fk_daily_closings_2` (`closed_by`),
  CONSTRAINT `fk_daily_closings_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_daily_closings_2` FOREIGN KEY (`closed_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점·날짜별 일별 마감 스냅샷, 업무를 막지 않음';

CREATE TABLE `daily_closing_adjustments` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `closing_id` bigint unsigned NOT NULL,
  `refund_id` bigint unsigned NOT NULL,
  `payment_id` bigint unsigned NOT NULL,
  `amount_krw` bigint unsigned NOT NULL,
  `processed_by` bigint unsigned NOT NULL,
  `processed_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_closing_adjustments_1` (`refund_id`),
  KEY `ix_daily_closing_adjustments_1` (`closing_id`, `processed_at`),
  KEY `fk_daily_closing_adjustments_3` (`payment_id`),
  KEY `fk_daily_closing_adjustments_4` (`processed_by`),
  CONSTRAINT `fk_daily_closing_adjustments_1` FOREIGN KEY (`closing_id`) REFERENCES `daily_closings` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_daily_closing_adjustments_2` FOREIGN KEY (`refund_id`) REFERENCES `refunds` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_daily_closing_adjustments_3` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_daily_closing_adjustments_4` FOREIGN KEY (`processed_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_daily_closing_adjustments_1` CHECK ((`amount_krw` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='마감된 날 결제의 이후 환불, 금액은 양수로 저장하고 응답에서 차감으로 표시';
