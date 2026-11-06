-- Phase 5 (OS-DL-0503): DomainLens AI Workspace, Executions, Work Records, CSV Approvals, Traces, and Embedding Cache
-- Preserves V1 ai_threads and ai_requests untouched (ADR-M10-001 D08).

CREATE TABLE `ai_conversations` (
  `id` varchar(64) NOT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `scope_key` varchar(64) NOT NULL DEFAULT 'all',
  `title` varchar(200) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_ai_conversations_emp` (`employee_id`,`created_at`),
  CONSTRAINT `fk_ai_conversations_emp` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens AI 워크스페이스 대화 세션';

CREATE TABLE `ai_messages` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `conversation_id` varchar(64) NOT NULL,
  `role` varchar(32) NOT NULL,
  `content` text NOT NULL,
  `execution_id` varchar(64) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_ai_messages_conv` (`conversation_id`,`id`),
  CONSTRAINT `fk_ai_messages_conv` FOREIGN KEY (`conversation_id`) REFERENCES `ai_conversations` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens AI 대화 메시지 이력';

CREATE TABLE `ai_executions` (
  `id` varchar(64) NOT NULL,
  `request_ref` varchar(64) NOT NULL,
  `conversation_id` varchar(64) DEFAULT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `scope_key` varchar(64) NOT NULL,
  `branch_ids_csv` varchar(255) NOT NULL,
  `question` varchar(1000) NOT NULL,
  `poc_code` varchar(32) DEFAULT NULL,
  `analysis_id` varchar(120) DEFAULT NULL,
  `intent_domain` varchar(64) DEFAULT NULL,
  `status` varchar(40) NOT NULL,
  `result_category` varchar(80) DEFAULT NULL,
  `error_code` varchar(80) DEFAULT NULL,
  `query_spec_json` text DEFAULT NULL,
  `view_spec_json` text DEFAULT NULL,
  `envelope_json` mediumtext DEFAULT NULL,
  `legacy_execution_json` mediumtext DEFAULT NULL,
  `as_of_utc` datetime(6) NOT NULL,
  `snapshot_version` varchar(64) NOT NULL,
  `policy_version` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_executions_req` (`request_ref`),
  KEY `ix_ai_executions_emp` (`employee_id`,`created_at`),
  CONSTRAINT `fk_ai_executions_emp` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens AI 검증된 실행 스냅샷';

CREATE TABLE `ai_work_records` (
  `id` varchar(64) NOT NULL,
  `execution_id` varchar(64) NOT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `title` varchar(200) NOT NULL,
  `question` varchar(1000) NOT NULL,
  `domain` varchar(64) NOT NULL,
  `scope_key` varchar(64) NOT NULL,
  `branch_ids_csv` varchar(255) NOT NULL,
  `pinned` tinyint(1) NOT NULL DEFAULT '0',
  `archived` tinyint(1) NOT NULL DEFAULT '0',
  `snapshot_json` mediumtext NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_ai_work_records_emp` (`employee_id`,`archived`,`pinned`,`created_at`),
  CONSTRAINT `fk_ai_work_records_emp` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens AI 저장된 작업 기록 아티팩트';

CREATE TABLE `ai_export_approvals` (
  `id` varchar(64) NOT NULL,
  `execution_id` varchar(64) NOT NULL,
  `dataset_id` varchar(80) NOT NULL,
  `requester_employee_id` bigint unsigned NOT NULL,
  `approver_employee_id` bigint unsigned DEFAULT NULL,
  `scope_key` varchar(64) NOT NULL,
  `branch_ids_csv` varchar(255) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `decision_note` varchar(500) DEFAULT NULL,
  `row_count` int unsigned NOT NULL DEFAULT '0',
  `status` varchar(32) NOT NULL DEFAULT 'PENDING',
  `requested_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `decided_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ai_export_approvals_status` (`status`,`requested_at`),
  KEY `ix_ai_export_approvals_req` (`requester_employee_id`,`requested_at`),
  CONSTRAINT `fk_ai_export_approvals_req` FOREIGN KEY (`requester_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_ai_export_approvals_app` FOREIGN KEY (`approver_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens CSV 반출 승인 워크플로';

CREATE TABLE `ai_traces` (
  `id` varchar(64) NOT NULL,
  `request_ref` varchar(64) NOT NULL,
  `execution_id` varchar(64) DEFAULT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `scope_key` varchar(64) NOT NULL,
  `sanitized_question` varchar(1000) DEFAULT NULL,
  `status` varchar(40) NOT NULL,
  `total_duration_ms` bigint unsigned NOT NULL DEFAULT '0',
  `stages_json` mediumtext NOT NULL,
  `retrieval_json` mediumtext NOT NULL,
  `security_gate_json` text NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `question_expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ai_traces_emp` (`employee_id`,`created_at`),
  KEY `ix_ai_traces_exp` (`question_expires_at`),
  CONSTRAINT `fk_ai_traces_emp` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens 6단계 파이프라인 진단 트레이스 (질문 원문 30일 보관 후 삭제)';

CREATE TABLE `ai_embedding_cache` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `model_id` varchar(80) NOT NULL,
  `text_hash` varchar(64) NOT NULL,
  `dimensions` smallint unsigned NOT NULL,
  `vector_json` mediumtext NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_embedding_cache_1` (`model_id`,`text_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ONNX koen-e5-tiny-q8 384차원 임베딩 캐시';
