-- Password reset links sent by an administrator (Issue #119, ADR-M14-001 M14-D01·D05·D11, account-collab-api.md).
-- One row is one requested reset and its mail delivery. The token is made when the worker claims the row, sent by mail
-- and never stored: only its SHA-256 hash is kept while the link can be used (SENDING, SENT). A new request revokes the
-- employee's earlier rows. A lost lease or a failed send clears the hash, so the next claim makes a new token.
CREATE TABLE `password_reset_tokens` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `requested_by` bigint unsigned DEFAULT NULL,
  `status` enum('PENDING','SENDING','SENT','USED','REVOKED','FAILED') NOT NULL DEFAULT 'PENDING',
  `token_hash` binary(32) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `attempts` int unsigned NOT NULL DEFAULT 0,
  `next_attempt_at` datetime(6) NOT NULL,
  `worker_id` varchar(100) DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `failure_reason` varchar(40) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `used_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_password_reset_tokens_1` (`token_hash`),
  KEY `ix_password_reset_tokens_1` (`status`, `next_attempt_at`),
  KEY `ix_password_reset_tokens_2` (`employee_id`, `status`),
  KEY `fk_password_reset_tokens_2` (`requested_by`),
  CONSTRAINT `fk_password_reset_tokens_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_password_reset_tokens_2` FOREIGN KEY (`requested_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_password_reset_tokens_1` CHECK (((`status` IN ('SENDING','SENT')) = (`token_hash` IS NOT NULL))),
  CONSTRAINT `ck_password_reset_tokens_2` CHECK (((`status` IN ('SENDING','SENT')) = (`expires_at` IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='관리자 비밀번호 초기화 링크와 발송 상태, 토큰은 해시만 저장';
