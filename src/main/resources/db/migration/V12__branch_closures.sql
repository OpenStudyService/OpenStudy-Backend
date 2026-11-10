-- Branch closures (Issue #112, ADR-M13-001 M13-D01·D05, operations-extension-api.md "지점 휴무일").
-- One row is one whole Asia/Seoul day on which the branch is closed. It only informs the screens and the home summary and
-- refuses new room reservations that start on that day; sales, check-in, clock-in and shifts are not blocked.
-- A branch has at most one closure per day.
CREATE TABLE `branch_closures` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `closed_on` date NOT NULL,
  `note` varchar(200) DEFAULT NULL,
  `created_by` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branch_closures_1` (`branch_id`, `closed_on`),
  KEY `fk_branch_closures_2` (`created_by`),
  CONSTRAINT `fk_branch_closures_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_branch_closures_2` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 휴무일(Asia/Seoul 하루), 그날 시작하는 룸 신규 예약만 차단';
