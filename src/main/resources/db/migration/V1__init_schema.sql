-- OpenStudy / MySQL 8.4 InnoDB / initial draft v0.5 (2026-10-18)
-- NOTE: initial schema, not a validated production migration; review docs before applying.
-- All DATETIME(6) values are UTC; configure database connections and MySQL session time_zone = +00:00.
-- Local business-day/month calculations and weekly operating hours use Asia/Seoul in application logic.
-- No customer login/QR/real PG/social login/attachment delivery in the stated scope.
-- Requires MySQL 8.4 CHECK constraints and generated columns.

-- ACCESS: 스터디카페 지점
CREATE TABLE `branches` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(32) NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `address` VARCHAR(300) NOT NULL,
  `phone` VARCHAR(30) NULL,
  `status` ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branches_1` (`code`),
  KEY `ix_branches_1` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='스터디카페 지점';

-- ACCESS: 지점 기본 영업시간. 분 단위, 종료 1440 초과는 다음날
CREATE TABLE `branch_weekly_hours` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `weekday` TINYINT UNSIGNED NOT NULL,
  `is_closed` BOOLEAN NOT NULL DEFAULT FALSE,
  `opens_minute` SMALLINT UNSIGNED NULL,
  `closes_minute` SMALLINT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branch_weekly_hours_1` (`branch_id`, `weekday`),
  CONSTRAINT `fk_branch_weekly_hours_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_branch_weekly_hours_1` CHECK (weekday BETWEEN 1 AND 7),
  CONSTRAINT `ck_branch_weekly_hours_2` CHECK (((is_closed = 1 AND opens_minute IS NULL AND closes_minute IS NULL) OR (is_closed = 0 AND opens_minute IS NOT NULL AND closes_minute IS NOT NULL AND opens_minute < 1440 AND closes_minute > opens_minute AND closes_minute <= 2880)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 기본 영업시간. 분 단위, 종료 1440 초과는 다음날';

-- ACCESS: 직원 로그인 계정; 이메일 ID 불변, 최초 소유자 1명
CREATE TABLE `employees` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `email` VARCHAR(254) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `nickname` VARCHAR(100) NOT NULL,
  `avatar_url` VARCHAR(500) NULL,
  `global_role` ENUM('OWNER','EMPLOYEE') NOT NULL DEFAULT 'EMPLOYEE',
  `owner_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN global_role = 'OWNER' THEN 1 ELSE NULL END) STORED,
  `status` ENUM('PENDING','ACTIVE','SUSPENDED','LEFT') NOT NULL DEFAULT 'PENDING',
  `theme` ENUM('FOREST','NAVY','SAND') NOT NULL DEFAULT 'FOREST',
  `last_login_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employees_1` (`email`),
  UNIQUE KEY `uk_employees_2` (`owner_slot`),
  KEY `ix_employees_1` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원 로그인 계정; 이메일 ID 불변, 최초 소유자 1명';

-- ACCESS: 행동 권한 코드 사전
CREATE TABLE `permission_definitions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(80) NOT NULL,
  `description` VARCHAR(255) NOT NULL,
  `is_admin_only` BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_definitions_1` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='행동 권한 코드 사전';

-- ACCESS: 기본 및 소유자 정의 지점 권한 프리셋
CREATE TABLE `permission_presets` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `role` ENUM('MANAGER','STAFF') NOT NULL,
  `is_system` BOOLEAN NOT NULL DEFAULT FALSE,
  `created_by` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_presets_1` (`name`, `role`),
  CONSTRAINT `fk_permission_presets_1` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기본 및 소유자 정의 지점 권한 프리셋';

-- ACCESS: 프리셋 권한 원본 (복사시 기존 직원에 영향 없음)
CREATE TABLE `permission_preset_grants` (
  `preset_id` BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`preset_id`, `permission_id`),
  CONSTRAINT `fk_permission_preset_grants_1` FOREIGN KEY (`preset_id`) REFERENCES `permission_presets` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_permission_preset_grants_2` FOREIGN KEY (`permission_id`) REFERENCES `permission_definitions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='프리셋 권한 원본 (복사시 기존 직원에 영향 없음)';

-- ACCESS: 직원-지점 배정과 관리자 최종 승인 상태
CREATE TABLE `branch_memberships` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `role` ENUM('MANAGER','STAFF') NOT NULL DEFAULT 'STAFF',
  `status` ENUM('PENDING','ACTIVE','REVOKED') NOT NULL DEFAULT 'PENDING',
  `approved_by` BIGINT UNSIGNED NULL,
  `approved_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branch_memberships_1` (`employee_id`, `branch_id`),
  UNIQUE KEY `uk_branch_memberships_2` (`branch_id`, `employee_id`),
  KEY `ix_branch_memberships_1` (`branch_id`, `status`, `role`),
  CONSTRAINT `fk_branch_memberships_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_branch_memberships_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_branch_memberships_3` FOREIGN KEY (`approved_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원-지점 배정과 관리자 최종 승인 상태';

-- ACCESS: 직원 지점별 실효 권한의 스냅샷
CREATE TABLE `membership_permission_grants` (
  `membership_id` BIGINT UNSIGNED NOT NULL,
  `permission_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`membership_id`, `permission_id`),
  CONSTRAINT `fk_membership_permission_grants_1` FOREIGN KEY (`membership_id`) REFERENCES `branch_memberships` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_membership_permission_grants_2` FOREIGN KEY (`permission_id`) REFERENCES `permission_definitions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원 지점별 실효 권한의 스냅샷';

-- ACCESS: 초대 해시/24시간/발송 revision/최종 승인
CREATE TABLE `employee_invitations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `invitee_email` VARCHAR(254) NOT NULL,
  `initial_role` ENUM('MANAGER','STAFF') NOT NULL,
  `preset_id` BIGINT UNSIGNED NULL,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `accepted_employee_id` BIGINT UNSIGNED NULL,
  `approved_by` BIGINT UNSIGNED NULL,
  `status` ENUM('PENDING','ACCEPTED','APPROVED','REJECTED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'PENDING',
  `active_invitation_slot` TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','ACCEPTED') THEN 1 ELSE NULL END) STORED,
  `token_hash` BINARY(32) NULL,
  `revision` INT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `expires_at` DATETIME(6) NOT NULL,
  `accepted_at` DATETIME(6) NULL,
  `approved_at` DATETIME(6) NULL,
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employee_invitations_1` (`branch_id`, `invitee_email`, `active_invitation_slot`),
  KEY `ix_employee_invitations_1` (`invitee_email`, `status`),
  KEY `ix_employee_invitations_2` (`status`, `expires_at`),
  CONSTRAINT `fk_employee_invitations_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_employee_invitations_2` FOREIGN KEY (`preset_id`) REFERENCES `permission_presets` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_employee_invitations_3` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_employee_invitations_4` FOREIGN KEY (`accepted_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_employee_invitations_5` FOREIGN KEY (`approved_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_employee_invitations_1` CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='초대 해시/24시간/발송 revision/최종 승인';

-- ACCESS: 메일 발송 outbox; 토큰 원문/재생성 가능한 시크릿 저장 금지
CREATE TABLE `invitation_send_jobs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `invitation_id` BIGINT UNSIGNED NOT NULL,
  `revision` INT UNSIGNED NOT NULL,
  `status` ENUM('PENDING','PROCESSING','SENT','FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
  `attempts` INT UNSIGNED NOT NULL DEFAULT 0,
  `next_attempt_at` DATETIME(6) NULL,
  `sent_at` DATETIME(6) NULL,
  `last_error_code` VARCHAR(80) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitation_send_jobs_1` (`invitation_id`, `revision`),
  KEY `ix_invitation_send_jobs_1` (`status`, `next_attempt_at`),
  CONSTRAINT `fk_invitation_send_jobs_1` FOREIGN KEY (`invitation_id`) REFERENCES `employee_invitations` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='메일 발송 outbox; 토큰 원문/재생성 가능한 시크릿 저장 금지';

-- ACCESS: 핵심 변경 감사. JSON은 민감정보 제거 후 저장
CREATE TABLE `audit_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `actor_employee_id` BIGINT UNSIGNED NULL,
  `branch_id` BIGINT UNSIGNED NULL,
  `entity_type` VARCHAR(80) NOT NULL,
  `entity_id` BIGINT UNSIGNED NOT NULL,
  `action` VARCHAR(80) NOT NULL,
  `reason` VARCHAR(500) NULL,
  `before_json` JSON NULL,
  `after_json` JSON NULL,
  `occurred_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_audit_events_1` (`entity_type`, `entity_id`, `occurred_at`),
  KEY `ix_audit_events_2` (`actor_employee_id`, `occurred_at`),
  KEY `ix_audit_events_3` (`branch_id`, `occurred_at`),
  CONSTRAINT `fk_audit_events_1` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_audit_events_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='핵심 변경 감사. JSON은 민감정보 제거 후 저장';

-- COLLABORATION: 업무와 담당자·지점, 충돌 감지 version
CREATE TABLE `work_tasks` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `body` TEXT NULL,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `assignee_employee_id` BIGINT UNSIGNED NOT NULL,
  `category` VARCHAR(60) NOT NULL,
  `priority` ENUM('LOW','NORMAL','HIGH','URGENT') NOT NULL DEFAULT 'NORMAL',
  `status` ENUM('TODO','IN_PROGRESS','DONE') NOT NULL DEFAULT 'TODO',
  `due_at` DATETIME(6) NULL,
  `completed_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_work_tasks_1` (`branch_id`, `status`, `due_at`),
  KEY `ix_work_tasks_2` (`assignee_employee_id`, `status`),
  CONSTRAINT `fk_work_tasks_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_work_tasks_2` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_work_tasks_3` FOREIGN KEY (`branch_id`, `assignee_employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업무와 담당자·지점, 충돌 감지 version';

-- COLLABORATION: 업무 체크리스트
CREATE TABLE `task_checklist_items` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT UNSIGNED NOT NULL,
  `body` VARCHAR(500) NOT NULL,
  `sort_order` INT UNSIGNED NOT NULL DEFAULT 0,
  `is_done` BOOLEAN NOT NULL DEFAULT FALSE,
  `updated_by` BIGINT UNSIGNED NULL,
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_task_checklist_items_1` (`task_id`, `sort_order`),
  CONSTRAINT `fk_task_checklist_items_1` FOREIGN KEY (`task_id`) REFERENCES `work_tasks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_task_checklist_items_2` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업무 체크리스트';

-- COLLABORATION: 업무 변경 이력
CREATE TABLE `task_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT UNSIGNED NOT NULL,
  `actor_employee_id` BIGINT UNSIGNED NULL,
  `event_type` VARCHAR(50) NOT NULL,
  `before_json` JSON NULL,
  `after_json` JSON NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_task_events_1` (`task_id`, `created_at`),
  CONSTRAINT `fk_task_events_1` FOREIGN KEY (`task_id`) REFERENCES `work_tasks` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_task_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업무 변경 이력';

-- COLLABORATION: 전체/지점 채널 또는 1대1 DM
CREATE TABLE `conversations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `kind` ENUM('GLOBAL_CHANNEL','BRANCH_CHANNEL','DM') NOT NULL,
  `branch_id` BIGINT UNSIGNED NULL,
  `name` VARCHAR(120) NULL,
  `dm_employee_low_id` BIGINT UNSIGNED NULL,
  `dm_employee_high_id` BIGINT UNSIGNED NULL,
  `created_by` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversations_1` (`dm_employee_low_id`, `dm_employee_high_id`),
  KEY `ix_conversations_1` (`branch_id`, `kind`),
  -- Preserve branch-channel identity; suspend referenced branches instead of deleting them.
  CONSTRAINT `fk_conversations_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversations_2` FOREIGN KEY (`dm_employee_low_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversations_3` FOREIGN KEY (`dm_employee_high_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversations_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_conversations_1` CHECK ((kind = 'DM' AND branch_id IS NULL AND dm_employee_low_id IS NOT NULL AND dm_employee_high_id IS NOT NULL AND dm_employee_low_id < dm_employee_high_id) OR (kind = 'GLOBAL_CHANNEL' AND branch_id IS NULL AND name IS NOT NULL AND dm_employee_low_id IS NULL AND dm_employee_high_id IS NULL) OR (kind = 'BRANCH_CHANNEL' AND branch_id IS NOT NULL AND name IS NOT NULL AND dm_employee_low_id IS NULL AND dm_employee_high_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='전체/지점 채널 또는 1대1 DM';

-- COLLABORATION: 채팅 참여자/읽음 위치
CREATE TABLE `conversation_members` (
  `conversation_id` BIGINT UNSIGNED NOT NULL,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `joined_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `left_at` DATETIME(6) NULL,
  `last_read_message_id` BIGINT UNSIGNED NULL,
  `last_read_at` DATETIME(6) NULL,
  PRIMARY KEY (`conversation_id`, `employee_id`),
  KEY `ix_conversation_members_1` (`employee_id`, `conversation_id`),
  CONSTRAINT `fk_conversation_members_1` FOREIGN KEY (`conversation_id`) REFERENCES `conversations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_conversation_members_2` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='채팅 참여자/읽음 위치';

-- COLLABORATION: 순서/재시도 키/스레드/원본 업무 참조
CREATE TABLE `messages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT UNSIGNED NOT NULL,
  `sender_employee_id` BIGINT UNSIGNED NOT NULL,
  `parent_message_id` BIGINT UNSIGNED NULL,
  `related_task_id` BIGINT UNSIGNED NULL,
  `kind` ENUM('TEXT','TASK_CARD') NOT NULL DEFAULT 'TEXT',
  `body` TEXT NULL,
  `client_message_key` VARCHAR(100) NOT NULL,
  `sent_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_messages_1` (`conversation_id`, `sender_employee_id`, `client_message_key`),
  UNIQUE KEY `uk_messages_2` (`id`, `conversation_id`),
  KEY `ix_messages_1` (`conversation_id`, `id`),
  KEY `ix_messages_2` (`parent_message_id`, `id`),
  CONSTRAINT `fk_messages_1` FOREIGN KEY (`conversation_id`, `sender_employee_id`) REFERENCES `conversation_members` (`conversation_id`, `employee_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_messages_2` FOREIGN KEY (`parent_message_id`, `conversation_id`) REFERENCES `messages` (`id`, `conversation_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_messages_3` FOREIGN KEY (`related_task_id`) REFERENCES `work_tasks` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_messages_1` CHECK ((kind = 'TASK_CARD' AND related_task_id IS NOT NULL) OR (kind = 'TEXT' AND body IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='순서/재시도 키/스레드/원본 업무 참조';

-- COLLABORATION: 내부 공지. 전체/지점 공개범위
CREATE TABLE `announcements` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `author_employee_id` BIGINT UNSIGNED NOT NULL,
  `title` VARCHAR(200) NOT NULL,
  `body` TEXT NOT NULL,
  `target` ENUM('ALL','SELECTED_BRANCHES') NOT NULL,
  `status` ENUM('DRAFT','PUBLISHED','ARCHIVED') NOT NULL DEFAULT 'DRAFT',
  `is_pinned` BOOLEAN NOT NULL DEFAULT FALSE,
  `published_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_announcements_1` (`status`, `is_pinned`, `published_at`),
  CONSTRAINT `fk_announcements_1` FOREIGN KEY (`author_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='내부 공지. 전체/지점 공개범위';

-- COLLABORATION: 지점 대상 공지 배포
CREATE TABLE `announcement_branches` (
  `announcement_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`announcement_id`, `branch_id`),
  KEY `ix_announcement_branches_1` (`branch_id`, `announcement_id`),
  CONSTRAINT `fk_announcement_branches_1` FOREIGN KEY (`announcement_id`) REFERENCES `announcements` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_announcement_branches_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 대상 공지 배포';

-- COMMERCE: 전사 공통 고객 원장; 직원 로그인과 무관
CREATE TABLE `customers` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `member_no` VARCHAR(40) NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `phone` VARCHAR(30) NULL,
  `email` VARCHAR(254) NULL,
  `status` ENUM('NORMAL','RESTRICTED','INACTIVE') NOT NULL DEFAULT 'NORMAL',
  `status_reason` VARCHAR(500) NULL,
  `registered_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customers_1` (`member_no`),
  KEY `ix_customers_1` (`name`),
  KEY `ix_customers_2` (`phone`),
  KEY `ix_customers_3` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='전사 공통 고객 원장; 직원 로그인과 무관';

-- COMMERCE: 응대 중인 특정 고객의 전 지점 이용 조회 근거와 접근 감사
CREATE TABLE `customer_service_interactions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `verified_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_customer_service_interactions_1` (`customer_id`, `branch_id`, `verified_at`),
  KEY `ix_customer_service_interactions_2` (`employee_id`, `verified_at`),
  CONSTRAINT `fk_customer_service_interactions_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_service_interactions_2` FOREIGN KEY (`branch_id`, `employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='응대 중인 특정 고객의 전 지점 이용 조회 근거와 접근 감사';

-- COMMERCE: 고객 상태/제한 변경 이력
CREATE TABLE `customer_status_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `previous_status` VARCHAR(30) NOT NULL,
  `new_status` VARCHAR(30) NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `actor_employee_id` BIGINT UNSIGNED NOT NULL,
  `changed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_customer_status_events_1` (`customer_id`, `changed_at`),
  CONSTRAINT `fk_customer_status_events_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_status_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객 상태/제한 변경 이력';

-- COMMERCE: 시간권/기간권/정액권 상품
CREATE TABLE `products` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(40) NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `kind` ENUM('TIME','PERIOD','STORED_VALUE') NOT NULL,
  `price_krw` BIGINT UNSIGNED NOT NULL,
  `included_minutes` INT UNSIGNED NULL,
  `period_days` INT UNSIGNED NULL,
  `credit_krw` BIGINT UNSIGNED NULL,
  `hourly_rate_krw` BIGINT UNSIGNED NULL,
  `valid_days` INT UNSIGNED NULL,
  `sale_status` ENUM('ON_SALE','STOPPED') NOT NULL DEFAULT 'ON_SALE',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_products_1` (`code`),
  KEY `ix_products_1` (`kind`, `sale_status`),
  CONSTRAINT `ck_products_1` CHECK ((kind = 'TIME' AND included_minutes IS NOT NULL AND included_minutes > 0 AND period_days IS NULL AND credit_krw IS NULL AND hourly_rate_krw IS NULL) OR (kind = 'PERIOD' AND period_days IS NOT NULL AND period_days > 0 AND included_minutes IS NULL AND credit_krw IS NULL AND hourly_rate_krw IS NULL) OR (kind = 'STORED_VALUE' AND credit_krw IS NOT NULL AND hourly_rate_krw IS NOT NULL AND credit_krw > 0 AND hourly_rate_krw > 0 AND included_minutes IS NULL AND period_days IS NULL)),
  CONSTRAINT `ck_products_2` CHECK (valid_days IS NULL OR valid_days > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='시간권/기간권/정액권 상품';

-- COMMERCE: 이용권·스터디룸·사물함 내부 Mock 승인/실패, 구매 지점의 원거래
CREATE TABLE `payments` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NULL,
  `product_name_snapshot` VARCHAR(120) NULL,
  `purpose` ENUM('PASS','ROOM','LOCKER') NOT NULL,
  `method` ENUM('CASH','CARD','TRANSFER','OTHER') NOT NULL,
  `amount_krw` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('PENDING','APPROVED','FAILED','REFUNDED') NOT NULL DEFAULT 'PENDING',
  `idempotency_key` VARCHAR(120) NOT NULL,
  `approved_at` DATETIME(6) NULL,
  `failed_at` DATETIME(6) NULL,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payments_1` (`idempotency_key`),
  UNIQUE KEY `uk_payments_2` (`id`, `customer_id`, `branch_id`),
  UNIQUE KEY `uk_payments_3` (`id`, `customer_id`, `branch_id`, `product_id`),
  KEY `ix_payments_1` (`branch_id`, `status`, `approved_at`),
  KEY `ix_payments_2` (`customer_id`, `created_at`),
  KEY `ix_payments_3` (`product_id`, `approved_at`),
  CONSTRAINT `fk_payments_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_3` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_payments_1` CHECK ((purpose = 'PASS' AND product_id IS NOT NULL) OR (purpose IN ('ROOM','LOCKER') AND product_id IS NULL)),
  CONSTRAINT `ck_payments_2` CHECK (amount_krw > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='이용권·스터디룸·사물함 내부 Mock 승인/실패, 구매 지점의 원거래';

-- COMMERCE: 승인 결제 발급권, 구매 당시 상품 조건 스냅샷
CREATE TABLE `passes` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `product_id` BIGINT UNSIGNED NOT NULL,
  `purchase_payment_id` BIGINT UNSIGNED NOT NULL,
  `purchase_branch_id` BIGINT UNSIGNED NOT NULL,
  `kind` ENUM('TIME','PERIOD','STORED_VALUE') NOT NULL,
  `snapshot_price_krw` BIGINT UNSIGNED NOT NULL,
  `snapshot_product_name` VARCHAR(120) NOT NULL,
  `snapshot_minutes` INT UNSIGNED NULL,
  `snapshot_period_days` INT UNSIGNED NULL,
  `snapshot_credit_krw` BIGINT UNSIGNED NULL,
  `snapshot_hourly_rate_krw` BIGINT UNSIGNED NULL,
  `snapshot_valid_days` INT UNSIGNED NULL,
  `remaining_minutes` INT UNSIGNED NULL,
  `remaining_credit_krw` BIGINT UNSIGNED NULL,
  `status` ENUM('ACTIVE','EXHAUSTED','EXPIRED','REFUNDED') NOT NULL DEFAULT 'ACTIVE',
  `started_at` DATETIME(6) NOT NULL,
  `expires_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_passes_1` (`purchase_payment_id`),
  KEY `ix_passes_1` (`customer_id`, `status`, `expires_at`),
  KEY `ix_passes_2` (`purchase_branch_id`, `created_at`),
  CONSTRAINT `fk_passes_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_2` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_3` FOREIGN KEY (`purchase_payment_id`, `customer_id`, `purchase_branch_id`, `product_id`) REFERENCES `payments` (`id`, `customer_id`, `branch_id`, `product_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_4` FOREIGN KEY (`purchase_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_passes_1` CHECK ((kind = 'TIME' AND snapshot_minutes IS NOT NULL AND snapshot_minutes > 0 AND remaining_minutes IS NOT NULL AND snapshot_credit_krw IS NULL AND remaining_credit_krw IS NULL) OR (kind = 'PERIOD' AND snapshot_period_days IS NOT NULL AND snapshot_period_days > 0 AND remaining_minutes IS NULL AND remaining_credit_krw IS NULL) OR (kind = 'STORED_VALUE' AND snapshot_credit_krw IS NOT NULL AND snapshot_hourly_rate_krw IS NOT NULL AND snapshot_credit_krw > 0 AND snapshot_hourly_rate_krw > 0 AND remaining_credit_krw IS NOT NULL AND remaining_minutes IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='승인 결제 발급권, 구매 당시 상품 조건 스냅샷';

-- COMMERCE: 원거래 1회 전액 환불, 처리지점/원결제지점 분리
CREATE TABLE `refunds` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `payment_id` BIGINT UNSIGNED NOT NULL,
  `processing_branch_id` BIGINT UNSIGNED NOT NULL,
  `processed_by` BIGINT UNSIGNED NOT NULL,
  `amount_krw` BIGINT UNSIGNED NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `idempotency_key` VARCHAR(120) NOT NULL,
  `refunded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refunds_1` (`payment_id`),
  UNIQUE KEY `uk_refunds_2` (`idempotency_key`),
  KEY `ix_refunds_1` (`refunded_at`),
  KEY `ix_refunds_2` (`processing_branch_id`, `refunded_at`),
  CONSTRAINT `fk_refunds_1` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_refunds_2` FOREIGN KEY (`processing_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_refunds_3` FOREIGN KEY (`processed_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_refunds_1` CHECK (amount_krw > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='원거래 1회 전액 환불, 처리지점/원결제지점 분리';

-- SPACE: 지점 좌석: OCCUPIED는 활성 seat_session으로 계산
CREATE TABLE `seats` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `zone` VARCHAR(80) NOT NULL,
  `seat_no` VARCHAR(30) NOT NULL,
  `kind` ENUM('GENERAL','MONITOR','OTHER') NOT NULL DEFAULT 'GENERAL',
  `status` ENUM('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seats_1` (`branch_id`, `seat_no`),
  UNIQUE KEY `uk_seats_2` (`id`, `branch_id`),
  KEY `ix_seats_1` (`branch_id`, `zone`, `status`),
  CONSTRAINT `fk_seats_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 좌석: OCCUPIED는 활성 seat_session으로 계산';

-- SPACE: 일반 좌석 현장 이용 및 실측/정정 시간. 활성 고객/좌석 유일
CREATE TABLE `seat_sessions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `pass_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `seat_id` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE',
  `active_seat_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN seat_id ELSE NULL END) STORED,
  `active_customer_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN customer_id ELSE NULL END) STORED,
  `checked_in_at` DATETIME(6) NOT NULL,
  `effective_checked_in_at` DATETIME(6) NOT NULL,
  `checked_out_at` DATETIME(6) NULL,
  `effective_checked_out_at` DATETIME(6) NULL,
  `billed_minutes` INT UNSIGNED NOT NULL DEFAULT 0,
  `billed_krw` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `overage_minutes` INT UNSIGNED NOT NULL DEFAULT 0,
  `overage_krw` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `exception_actor_id` BIGINT UNSIGNED NULL,
  `exception_reason` VARCHAR(500) NULL,
  `start_request_key` VARCHAR(120) NOT NULL,
  `end_request_key` VARCHAR(120) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_sessions_1` (`active_seat_id`),
  UNIQUE KEY `uk_seat_sessions_2` (`active_customer_id`),
  UNIQUE KEY `uk_seat_sessions_3` (`start_request_key`),
  UNIQUE KEY `uk_seat_sessions_4` (`end_request_key`),
  KEY `ix_seat_sessions_1` (`branch_id`, `status`, `checked_in_at`),
  KEY `ix_seat_sessions_2` (`pass_id`, `checked_in_at`),
  CONSTRAINT `fk_seat_sessions_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_2` FOREIGN KEY (`pass_id`) REFERENCES `passes` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_3` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_4` FOREIGN KEY (`seat_id`, `branch_id`) REFERENCES `seats` (`id`, `branch_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_5` FOREIGN KEY (`exception_actor_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_seat_sessions_1` CHECK ((status = 'ACTIVE' AND checked_out_at IS NULL AND effective_checked_out_at IS NULL) OR (status = 'CLOSED' AND checked_out_at IS NOT NULL AND effective_checked_out_at IS NOT NULL)),
  CONSTRAINT `ck_seat_sessions_2` CHECK (effective_checked_out_at IS NULL OR effective_checked_out_at >= effective_checked_in_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='일반 좌석 현장 이용 및 실측/정정 시간. 활성 고객/좌석 유일';

-- SPACE: 같은 지점 내 좌석 이동 이력
CREATE TABLE `seat_session_moves` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `seat_session_id` BIGINT UNSIGNED NOT NULL,
  `from_seat_id` BIGINT UNSIGNED NOT NULL,
  `to_seat_id` BIGINT UNSIGNED NOT NULL,
  `actor_employee_id` BIGINT UNSIGNED NULL,
  `reason` VARCHAR(500) NULL,
  `request_key` VARCHAR(120) NOT NULL,
  `moved_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_session_moves_1` (`request_key`),
  KEY `ix_seat_session_moves_1` (`seat_session_id`, `moved_at`),
  CONSTRAINT `fk_seat_session_moves_1` FOREIGN KEY (`seat_session_id`) REFERENCES `seat_sessions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_moves_2` FOREIGN KEY (`from_seat_id`) REFERENCES `seats` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_moves_3` FOREIGN KEY (`to_seat_id`) REFERENCES `seats` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_moves_4` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_seat_session_moves_1` CHECK (from_seat_id <> to_seat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='같은 지점 내 좌석 이동 이력';

-- SPACE: 시간 원본/정정값 및 사용량 차이의 변경 이력
CREATE TABLE `seat_session_corrections` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `seat_session_id` BIGINT UNSIGNED NOT NULL,
  `actor_employee_id` BIGINT UNSIGNED NOT NULL,
  `before_start_at` DATETIME(6) NOT NULL,
  `before_end_at` DATETIME(6) NOT NULL,
  `after_start_at` DATETIME(6) NOT NULL,
  `after_end_at` DATETIME(6) NOT NULL,
  `before_billed_minutes` INT UNSIGNED NOT NULL,
  `before_billed_krw` BIGINT UNSIGNED NOT NULL,
  `after_billed_minutes` INT UNSIGNED NOT NULL,
  `after_billed_krw` BIGINT UNSIGNED NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `request_key` VARCHAR(120) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_session_corrections_1` (`request_key`),
  KEY `ix_seat_session_corrections_1` (`seat_session_id`, `created_at`),
  CONSTRAINT `fk_seat_session_corrections_1` FOREIGN KEY (`seat_session_id`) REFERENCES `seat_sessions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_corrections_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_seat_session_corrections_1` CHECK (after_end_at >= after_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='시간 원본/정정값 및 사용량 차이의 변경 이력';

-- COMMERCE: 발급권 차감·보정의 불변 원장, 세션/정정 단일 참조
CREATE TABLE `pass_balance_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `pass_id` BIGINT UNSIGNED NOT NULL,
  `seat_session_id` BIGINT UNSIGNED NULL,
  `correction_id` BIGINT UNSIGNED NULL,
  `kind` ENUM('USE','ADJUST','RESTORE') NOT NULL,
  `delta_minutes` BIGINT NOT NULL DEFAULT 0,
  `delta_krw` BIGINT NOT NULL DEFAULT 0,
  `actor_employee_id` BIGINT UNSIGNED NULL,
  `reason` VARCHAR(500) NULL,
  `event_key` VARCHAR(120) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pass_balance_events_1` (`event_key`),
  KEY `ix_pass_balance_events_1` (`pass_id`, `created_at`),
  KEY `ix_pass_balance_events_2` (`seat_session_id`, `kind`),
  CONSTRAINT `fk_pass_balance_events_1` FOREIGN KEY (`pass_id`) REFERENCES `passes` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_pass_balance_events_2` FOREIGN KEY (`seat_session_id`) REFERENCES `seat_sessions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_pass_balance_events_3` FOREIGN KEY (`correction_id`) REFERENCES `seat_session_corrections` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_pass_balance_events_4` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_pass_balance_events_1` CHECK (NOT (seat_session_id IS NOT NULL AND correction_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='발급권 차감·보정의 불변 원장, 세션/정정 단일 참조';

-- SPACE: 스터디룸/정원/30분당 요금
CREATE TABLE `rooms` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `room_no` VARCHAR(40) NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `capacity` SMALLINT UNSIGNED NOT NULL,
  `rate_per_30min_krw` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rooms_1` (`branch_id`, `room_no`),
  UNIQUE KEY `uk_rooms_2` (`id`, `branch_id`),
  CONSTRAINT `fk_rooms_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_rooms_1` CHECK (capacity > 0),
  CONSTRAINT `ck_rooms_2` CHECK (rate_per_30min_krw > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='스터디룸/정원/30분당 요금';

-- SPACE: 월~일 룸 시간; 익일 종료 지원/날짜별 휴무 미지원
CREATE TABLE `room_weekly_hours` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `weekday` TINYINT UNSIGNED NOT NULL,
  `is_closed` BOOLEAN NOT NULL DEFAULT FALSE,
  `opens_minute` SMALLINT UNSIGNED NULL,
  `closes_minute` SMALLINT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_weekly_hours_1` (`room_id`, `weekday`),
  CONSTRAINT `fk_room_weekly_hours_1` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_room_weekly_hours_1` CHECK (weekday BETWEEN 1 AND 7),
  CONSTRAINT `ck_room_weekly_hours_2` CHECK (((is_closed = 1 AND opens_minute IS NULL AND closes_minute IS NULL) OR (is_closed = 0 AND opens_minute IS NOT NULL AND closes_minute IS NOT NULL AND opens_minute < 1440 AND closes_minute > opens_minute AND closes_minute <= 2880)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='월~일 룸 시간; 익일 종료 지원/날짜별 휴무 미지원';

-- SPACE: 룸 30분 단위 정수 요금 선결제, start 포함/end 제외
CREATE TABLE `room_reservations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `payment_id` BIGINT UNSIGNED NULL,
  `start_at` DATETIME(6) NOT NULL,
  `end_at` DATETIME(6) NOT NULL,
  `rate_per_30min_snapshot_krw` BIGINT UNSIGNED NOT NULL,
  `slot_count` INT UNSIGNED NOT NULL,
  `amount_krw` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('PAYMENT_PENDING','CONFIRMED','PAYMENT_FAILED','IN_USE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PAYMENT_PENDING',
  `cancelled_at` DATETIME(6) NULL,
  `cancelled_by` BIGINT UNSIGNED NULL,
  `cancel_reason` VARCHAR(500) NULL,
  `request_key` VARCHAR(120) NOT NULL,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_reservations_1` (`request_key`),
  UNIQUE KEY `uk_room_reservations_2` (`payment_id`),
  UNIQUE KEY `uk_room_reservations_3` (`id`, `room_id`),
  KEY `ix_room_reservations_1` (`room_id`, `start_at`, `end_at`, `status`),
  KEY `ix_room_reservations_2` (`branch_id`, `start_at`, `status`),
  KEY `ix_room_reservations_3` (`customer_id`, `start_at`),
  CONSTRAINT `fk_room_reservations_1` FOREIGN KEY (`room_id`, `branch_id`) REFERENCES `rooms` (`id`, `branch_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_2` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_3` FOREIGN KEY (`payment_id`, `customer_id`, `branch_id`) REFERENCES `payments` (`id`, `customer_id`, `branch_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  -- Preserve cancellation attribution; retain referenced employee accounts for audit history.
  CONSTRAINT `fk_room_reservations_5` FOREIGN KEY (`cancelled_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_room_reservations_1` CHECK (end_at > start_at),
  CONSTRAINT `ck_room_reservations_2` CHECK (SECOND(start_at) = 0 AND MICROSECOND(start_at) = 0),
  CONSTRAINT `ck_room_reservations_3` CHECK (MINUTE(start_at) IN (0, 30)),
  CONSTRAINT `ck_room_reservations_4` CHECK (SECOND(end_at) = 0 AND MICROSECOND(end_at) = 0),
  CONSTRAINT `ck_room_reservations_5` CHECK (MINUTE(end_at) IN (0, 30)),
  CONSTRAINT `ck_room_reservations_6` CHECK (MOD(TIMESTAMPDIFF(SECOND, start_at, end_at), 1800) = 0),
  CONSTRAINT `ck_room_reservations_7` CHECK (TIMESTAMPDIFF(SECOND, start_at, end_at) = slot_count * 1800),
  CONSTRAINT `ck_room_reservations_8` CHECK (slot_count > 0),
  CONSTRAINT `ck_room_reservations_9` CHECK (rate_per_30min_snapshot_krw > 0),
  CONSTRAINT `ck_room_reservations_10` CHECK (amount_krw = rate_per_30min_snapshot_krw * slot_count),
  CONSTRAINT `ck_room_reservations_11` CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL) OR (status <> 'CANCELLED' AND cancelled_at IS NULL AND cancelled_by IS NULL AND cancel_reason IS NULL)),
  CONSTRAINT `ck_room_reservations_12` CHECK (status NOT IN ('CONFIRMED','IN_USE','COMPLETED') OR payment_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='룸 30분 단위 정수 요금 선결제, start 포함/end 제외';

-- SPACE: 확정 예약의 30분 점유 슬롯. 취소 시 슬롯 삭제, 예약/감사는 유지
CREATE TABLE `room_reservation_slots` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `reservation_id` BIGINT UNSIGNED NOT NULL,
  `room_id` BIGINT UNSIGNED NOT NULL,
  `slot_start_at` DATETIME(6) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_reservation_slots_1` (`room_id`, `slot_start_at`),
  KEY `ix_room_reservation_slots_1` (`reservation_id`, `slot_start_at`),
  CONSTRAINT `fk_room_reservation_slots_1` FOREIGN KEY (`reservation_id`, `room_id`) REFERENCES `room_reservations` (`id`, `room_id`) ON DELETE CASCADE,
  CONSTRAINT `ck_room_reservation_slots_1` CHECK (SECOND(slot_start_at) = 0 AND MICROSECOND(slot_start_at) = 0),
  CONSTRAINT `ck_room_reservation_slots_2` CHECK (MINUTE(slot_start_at) IN (0,30))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='확정 예약의 30분 점유 슬롯. 취소 시 슬롯 삭제, 예약/감사는 유지';

-- SPACE: 예약된 룸 실제 사용 기록
CREATE TABLE `room_usages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `reservation_id` BIGINT UNSIGNED NOT NULL,
  `started_at` DATETIME(6) NOT NULL,
  `ended_at` DATETIME(6) NULL,
  `started_by` BIGINT UNSIGNED NULL,
  `ended_by` BIGINT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_usages_1` (`reservation_id`),
  CONSTRAINT `fk_room_usages_1` FOREIGN KEY (`reservation_id`) REFERENCES `room_reservations` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_usages_2` FOREIGN KEY (`started_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_room_usages_3` FOREIGN KEY (`ended_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_room_usages_1` CHECK (ended_at IS NULL OR ended_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='예약된 룸 실제 사용 기록';

-- SPACE: 지점 사물함
CREATE TABLE `lockers` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `locker_no` VARCHAR(40) NOT NULL,
  `rate_per_4weeks_krw` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lockers_1` (`branch_id`, `locker_no`),
  CONSTRAINT `fk_lockers_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_lockers_1` CHECK (rate_per_4weeks_krw > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 사물함';

-- SPACE: 사물함 배정/유료 최초 결제·연장, 날짜 구간 [start,end)
CREATE TABLE `locker_assignments` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `locker_id` BIGINT UNSIGNED NOT NULL,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `start_date` DATE NOT NULL,
  `end_date` DATE NOT NULL,
  `returned_at` DATETIME(6) NULL,
  `status` ENUM('PAYMENT_PENDING','PAYMENT_FAILED','ACTIVE','RETURNED','CANCELLED') NOT NULL DEFAULT 'PAYMENT_PENDING',
  `assigned_by` BIGINT UNSIGNED NOT NULL,
  `last_changed_by` BIGINT UNSIGNED NULL,
  `reason` VARCHAR(500) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_locker_assignments_1` (`locker_id`, `start_date`, `end_date`, `status`),
  KEY `ix_locker_assignments_2` (`customer_id`, `status`),
  CONSTRAINT `fk_locker_assignments_1` FOREIGN KEY (`locker_id`) REFERENCES `lockers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignments_2` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignments_3` FOREIGN KEY (`assigned_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignments_4` FOREIGN KEY (`last_changed_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_locker_assignments_1` CHECK (end_date > start_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사물함 배정/유료 최초 결제·연장, 날짜 구간 [start,end)';

-- SPACE: 사물함 최초 결제/유료 연장: 4주(28일) 정액 요금과 원거래 보존
CREATE TABLE `locker_assignment_charges` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `assignment_id` BIGINT UNSIGNED NOT NULL,
  `payment_id` BIGINT UNSIGNED NOT NULL,
  `charge_kind` ENUM('INITIAL','EXTENSION') NOT NULL,
  `initial_assignment_slot` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN charge_kind = 'INITIAL' AND status IN ('PENDING','APPLIED') THEN assignment_id ELSE NULL END) STORED,
  `start_date` DATE NOT NULL,
  `end_date` DATE NOT NULL,
  `charged_4week_terms` INT UNSIGNED NOT NULL,
  `rate_per_4weeks_snapshot_krw` BIGINT UNSIGNED NOT NULL,
  `amount_krw` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('PENDING','APPLIED','FAILED') NOT NULL DEFAULT 'PENDING',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `applied_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_locker_assignment_charges_1` (`payment_id`),
  UNIQUE KEY `uk_locker_assignment_charges_2` (`initial_assignment_slot`),
  KEY `ix_locker_assignment_charges_1` (`assignment_id`, `created_at`),
  KEY `ix_locker_assignment_charges_2` (`status`, `created_at`),
  CONSTRAINT `fk_locker_assignment_charges_1` FOREIGN KEY (`assignment_id`) REFERENCES `locker_assignments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignment_charges_2` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_locker_assignment_charges_1` CHECK (end_date > start_date),
  CONSTRAINT `ck_locker_assignment_charges_2` CHECK (DATEDIFF(end_date, start_date) = charged_4week_terms * 28),
  CONSTRAINT `ck_locker_assignment_charges_3` CHECK (charged_4week_terms > 0),
  CONSTRAINT `ck_locker_assignment_charges_4` CHECK (rate_per_4weeks_snapshot_krw > 0),
  CONSTRAINT `ck_locker_assignment_charges_5` CHECK (amount_krw = charged_4week_terms * rate_per_4weeks_snapshot_krw),
  CONSTRAINT `ck_locker_assignment_charges_6` CHECK ((status = 'APPLIED' AND applied_at IS NOT NULL) OR (status IN ('PENDING','FAILED') AND applied_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사물함 최초 결제/유료 연장: 4주(28일) 정액 요금과 원거래 보존';

-- FACILITY: 시설 유형과 기본 점검 주기
CREATE TABLE `facility_types` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(80) NOT NULL,
  `default_inspection_days` SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_facility_types_1` (`name`),
  CONSTRAINT `ck_facility_types_1` CHECK (default_inspection_days > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='시설 유형과 기본 점검 주기';

-- FACILITY: 지점 시설: 상태/점검 주기/마지막 완료일
CREATE TABLE `facilities` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `type_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `location` VARCHAR(200) NULL,
  `description` TEXT NULL,
  `status` ENUM('NORMAL','CHECK_NEEDED','BROKEN','OUT_OF_SERVICE') NOT NULL DEFAULT 'NORMAL',
  `inspection_interval_days` SMALLINT UNSIGNED NOT NULL,
  `last_inspected_on` DATE NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_facilities_1` (`branch_id`, `status`),
  KEY `ix_facilities_2` (`branch_id`, `last_inspected_on`),
  CONSTRAINT `fk_facilities_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_facilities_2` FOREIGN KEY (`type_id`) REFERENCES `facility_types` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_facilities_1` CHECK (inspection_interval_days > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 시설: 상태/점검 주기/마지막 완료일';

-- FACILITY: 점검·청소·수리 이력. 작업완료 != 시설 정상
CREATE TABLE `facility_works` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `facility_id` BIGINT UNSIGNED NOT NULL,
  `kind` ENUM('INSPECTION','CLEANING','REPAIR') NOT NULL,
  `status` ENUM('OPEN','IN_PROGRESS','DONE','CANCELLED') NOT NULL DEFAULT 'OPEN',
  `problem` VARCHAR(500) NULL,
  `result_note` TEXT NULL,
  `assigned_employee_id` BIGINT UNSIGNED NULL,
  `completed_by` BIGINT UNSIGNED NULL,
  `opened_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `completed_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  KEY `ix_facility_works_1` (`facility_id`, `opened_at`),
  KEY `ix_facility_works_2` (`status`, `opened_at`),
  CONSTRAINT `fk_facility_works_1` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_facility_works_2` FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_facility_works_3` FOREIGN KEY (`completed_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='점검·청소·수리 이력. 작업완료 != 시설 정상';

-- FACILITY: 고객 전화/현장 문의 백오피스 대리 접수
CREATE TABLE `inquiries` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `subject` VARCHAR(200) NOT NULL,
  `body` TEXT NOT NULL,
  `source` ENUM('IN_PERSON','PHONE','OTHER') NOT NULL,
  `status` ENUM('RECEIVED','IN_PROGRESS','ANSWERED') NOT NULL DEFAULT 'RECEIVED',
  `assigned_employee_id` BIGINT UNSIGNED NULL,
  `created_by` BIGINT UNSIGNED NOT NULL,
  `answered_by` BIGINT UNSIGNED NULL,
  `answer_body` TEXT NULL,
  `answered_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_inquiries_1` (`branch_id`, `status`, `created_at`),
  KEY `ix_inquiries_2` (`customer_id`, `created_at`),
  CONSTRAINT `fk_inquiries_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiries_2` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiries_3` FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_inquiries_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiries_5` FOREIGN KEY (`answered_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객 전화/현장 문의 백오피스 대리 접수';

-- FACILITY: 문의 답변·상태 변경 이력
CREATE TABLE `inquiry_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `inquiry_id` BIGINT UNSIGNED NOT NULL,
  `actor_employee_id` BIGINT UNSIGNED NOT NULL,
  `old_status` VARCHAR(30) NULL,
  `new_status` VARCHAR(30) NOT NULL,
  `message` TEXT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_inquiry_events_1` (`inquiry_id`, `created_at`),
  CONSTRAINT `fk_inquiry_events_1` FOREIGN KEY (`inquiry_id`) REFERENCES `inquiries` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiry_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='문의 답변·상태 변경 이력';

-- HR: 계획 근무; 지점 간 시간 충돌은 잠금으로 검증
CREATE TABLE `shifts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `starts_at` DATETIME(6) NOT NULL,
  `ends_at` DATETIME(6) NOT NULL,
  `planned_break_minutes` SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  `status` ENUM('SCHEDULED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
  `created_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_shifts_1` (`employee_id`, `starts_at`, `ends_at`),
  KEY `ix_shifts_2` (`branch_id`, `starts_at`, `status`),
  CONSTRAINT `fk_shifts_1` FOREIGN KEY (`branch_id`, `employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shifts_2` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_shifts_1` CHECK (ends_at > starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='계획 근무; 지점 간 시간 충돌은 잠금으로 검증';

-- HR: 근무 계획 변경의 원본/후 이력
CREATE TABLE `shift_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `shift_id` BIGINT UNSIGNED NOT NULL,
  `actor_employee_id` BIGINT UNSIGNED NOT NULL,
  `event_type` ENUM('CREATED','CHANGED','CANCELLED','SWAPPED') NOT NULL,
  `before_json` JSON NULL,
  `after_json` JSON NULL,
  `reason` VARCHAR(500) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_shift_events_1` (`shift_id`, `created_at`),
  CONSTRAINT `fk_shift_events_1` FOREIGN KEY (`shift_id`) REFERENCES `shifts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='근무 계획 변경의 원본/후 이력';

-- HR: 실제 출퇴근은 계획 근무와 독립
CREATE TABLE `attendance_records` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `branch_id` BIGINT UNSIGNED NOT NULL,
  `shift_id` BIGINT UNSIGNED NULL,
  `clock_in_at` DATETIME(6) NOT NULL,
  `clock_out_at` DATETIME(6) NULL,
  `status` ENUM('WORKING','LEFT') NOT NULL DEFAULT 'WORKING',
  `active_employee_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'WORKING' THEN employee_id ELSE NULL END) STORED,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attendance_records_1` (`active_employee_id`),
  KEY `ix_attendance_records_1` (`employee_id`, `clock_in_at`),
  KEY `ix_attendance_records_2` (`branch_id`, `clock_in_at`),
  CONSTRAINT `fk_attendance_records_1` FOREIGN KEY (`branch_id`, `employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_records_2` FOREIGN KEY (`shift_id`) REFERENCES `shifts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_attendance_records_1` CHECK ((status = 'WORKING' AND clock_out_at IS NULL) OR (status = 'LEFT' AND clock_out_at IS NOT NULL)),
  CONSTRAINT `ck_attendance_records_2` CHECK (clock_out_at IS NULL OR clock_out_at >= clock_in_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='실제 출퇴근은 계획 근무와 독립';

-- HR: 출근 중 휴게 구간
CREATE TABLE `attendance_breaks` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `attendance_id` BIGINT UNSIGNED NOT NULL,
  `started_at` DATETIME(6) NOT NULL,
  `ended_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  KEY `ix_attendance_breaks_1` (`attendance_id`, `started_at`),
  CONSTRAINT `fk_attendance_breaks_1` FOREIGN KEY (`attendance_id`) REFERENCES `attendance_records` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_attendance_breaks_1` CHECK (ended_at IS NULL OR ended_at >= started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='출근 중 휴게 구간';

-- HR: 직원 신청, 관리자 승인 시 실제 근태 반영
CREATE TABLE `attendance_corrections` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `attendance_id` BIGINT UNSIGNED NOT NULL,
  `requested_by` BIGINT UNSIGNED NOT NULL,
  `correction_kind` ENUM('CLOCK','BREAK') NOT NULL DEFAULT 'CLOCK',
  `break_id` BIGINT UNSIGNED NULL,
  `before_clock_in_at` DATETIME(6) NULL,
  `before_clock_out_at` DATETIME(6) NULL,
  `after_clock_in_at` DATETIME(6) NULL,
  `after_clock_out_at` DATETIME(6) NULL,
  `before_break_start_at` DATETIME(6) NULL,
  `before_break_end_at` DATETIME(6) NULL,
  `after_break_start_at` DATETIME(6) NULL,
  `after_break_end_at` DATETIME(6) NULL,
  `reason` VARCHAR(500) NOT NULL,
  `status` ENUM('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
  `decided_by` BIGINT UNSIGNED NULL,
  `decided_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_attendance_corrections_1` (`attendance_id`, `created_at`),
  KEY `ix_attendance_corrections_2` (`status`, `created_at`),
  CONSTRAINT `fk_attendance_corrections_1` FOREIGN KEY (`attendance_id`) REFERENCES `attendance_records` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_corrections_2` FOREIGN KEY (`break_id`) REFERENCES `attendance_breaks` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_corrections_3` FOREIGN KEY (`requested_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_corrections_4` FOREIGN KEY (`decided_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_attendance_corrections_1` CHECK ((correction_kind = 'CLOCK' AND after_clock_in_at IS NOT NULL AND break_id IS NULL) OR (correction_kind = 'BREAK' AND break_id IS NOT NULL AND after_break_start_at IS NOT NULL)),
  CONSTRAINT `ck_attendance_corrections_2` CHECK (after_clock_out_at IS NULL OR after_clock_out_at >= after_clock_in_at),
  CONSTRAINT `ck_attendance_corrections_3` CHECK (after_break_end_at IS NULL OR after_break_end_at >= after_break_start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원 신청, 관리자 승인 시 실제 근태 반영';

-- HR: 직원·연도 공통 휴가 부여, 원부여 지점 유지
CREATE TABLE `leave_grants` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `origin_branch_id` BIGINT UNSIGNED NOT NULL,
  `grant_year` SMALLINT UNSIGNED NOT NULL,
  `valid_from` DATE NOT NULL,
  `valid_through` DATE NOT NULL,
  `granted_days` DECIMAL(7,1) NOT NULL,
  `remaining_days` DECIMAL(7,1) NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `granted_by` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_leave_grants_1` (`employee_id`, `grant_year`, `valid_through`, `id`),
  CONSTRAINT `fk_leave_grants_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_grants_2` FOREIGN KEY (`origin_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_grants_3` FOREIGN KEY (`granted_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_leave_grants_1` CHECK (valid_through >= valid_from),
  CONSTRAINT `ck_leave_grants_2` CHECK (granted_days > 0),
  CONSTRAINT `ck_leave_grants_3` CHECK (remaining_days >= 0),
  CONSTRAINT `ck_leave_grants_4` CHECK (MOD(granted_days * 2, 1) = 0),
  CONSTRAINT `ck_leave_grants_5` CHECK (MOD(remaining_days * 2, 1) = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원·연도 공통 휴가 부여, 원부여 지점 유지';

-- HR: 기간+총일수 휴가 신청 (반차 시간대/비연속 날짜는 제외)
CREATE TABLE `leave_requests` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `request_year` SMALLINT UNSIGNED NOT NULL,
  `starts_on` DATE NOT NULL,
  `ends_on` DATE NOT NULL,
  `requested_days` DECIMAL(7,1) NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `status` ENUM('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
  `decided_by` BIGINT UNSIGNED NULL,
  `decided_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_leave_requests_1` (`employee_id`, `starts_on`, `ends_on`, `status`),
  CONSTRAINT `fk_leave_requests_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_requests_2` FOREIGN KEY (`decided_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_leave_requests_1` CHECK (ends_on >= starts_on),
  CONSTRAINT `ck_leave_requests_2` CHECK (YEAR(starts_on) = request_year AND YEAR(ends_on) = request_year),
  CONSTRAINT `ck_leave_requests_3` CHECK (requested_days > 0),
  CONSTRAINT `ck_leave_requests_4` CHECK (MOD(requested_days * 2, 1) = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기간+총일수 휴가 신청 (반차 시간대/비연속 날짜는 제외)';

-- HR: 승인 시 소비한 각 부여 배분과 취소 복원 마커
CREATE TABLE `leave_allocations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `leave_request_id` BIGINT UNSIGNED NOT NULL,
  `leave_grant_id` BIGINT UNSIGNED NOT NULL,
  `allocated_days` DECIMAL(7,1) NOT NULL,
  `restored_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leave_allocations_1` (`leave_request_id`, `leave_grant_id`),
  CONSTRAINT `fk_leave_allocations_1` FOREIGN KEY (`leave_request_id`) REFERENCES `leave_requests` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_allocations_2` FOREIGN KEY (`leave_grant_id`) REFERENCES `leave_grants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_leave_allocations_1` CHECK (allocated_days > 0),
  CONSTRAINT `ck_leave_allocations_2` CHECK (MOD(allocated_days * 2, 1) = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='승인 시 소비한 각 부여 배분과 취소 복원 마커';

-- HR: 휴가 부여/보정/소비/복원 불변 원장
CREATE TABLE `leave_balance_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `leave_grant_id` BIGINT UNSIGNED NOT NULL,
  `leave_request_id` BIGINT UNSIGNED NULL,
  `event_type` ENUM('GRANT','ADJUST','USE','RESTORE') NOT NULL,
  `delta_days` DECIMAL(7,1) NOT NULL,
  `reason` VARCHAR(500) NULL,
  `actor_employee_id` BIGINT UNSIGNED NOT NULL,
  `event_key` VARCHAR(120) NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leave_balance_events_1` (`event_key`),
  KEY `ix_leave_balance_events_1` (`leave_grant_id`, `created_at`),
  KEY `ix_leave_balance_events_2` (`leave_request_id`, `event_type`),
  CONSTRAINT `fk_leave_balance_events_1` FOREIGN KEY (`leave_grant_id`) REFERENCES `leave_grants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_balance_events_2` FOREIGN KEY (`leave_request_id`) REFERENCES `leave_requests` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_balance_events_3` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_leave_balance_events_1` CHECK (delta_days <> 0),
  CONSTRAINT `ck_leave_balance_events_2` CHECK (MOD(delta_days * 2, 1) = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='휴가 부여/보정/소비/복원 불변 원장';

-- HR: 기존 근무 인원/시간 대체 신청, 승인 시 원 근무 업데이트
CREATE TABLE `shift_swaps` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `original_shift_id` BIGINT UNSIGNED NOT NULL,
  `requested_by` BIGINT UNSIGNED NOT NULL,
  `replacement_employee_id` BIGINT UNSIGNED NOT NULL,
  `proposed_starts_at` DATETIME(6) NOT NULL,
  `proposed_ends_at` DATETIME(6) NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `status` ENUM('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
  `decided_by` BIGINT UNSIGNED NULL,
  `decided_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_shift_swaps_1` (`original_shift_id`, `status`),
  KEY `ix_shift_swaps_2` (`replacement_employee_id`, `proposed_starts_at`),
  CONSTRAINT `fk_shift_swaps_1` FOREIGN KEY (`original_shift_id`) REFERENCES `shifts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_swaps_2` FOREIGN KEY (`requested_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_swaps_3` FOREIGN KEY (`replacement_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_swaps_4` FOREIGN KEY (`decided_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_shift_swaps_1` CHECK (proposed_ends_at > proposed_starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기존 근무 인원/시간 대체 신청, 승인 시 원 근무 업데이트';

-- AI: 별도 AI 대화. 팀 채팅과 데이터·권한 분리
CREATE TABLE `ai_threads` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `employee_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `last_used_at` DATETIME(6) NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ai_threads_1` (`employee_id`, `created_at`),
  CONSTRAINT `fk_ai_threads_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='별도 AI 대화. 팀 채팅과 데이터·권한 분리';

-- AI: DomainLens 소비 호출 상태/계약 버전, 결과 캐시 비저장
CREATE TABLE `ai_requests` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `thread_id` BIGINT UNSIGNED NOT NULL,
  `engine_request_ref` VARCHAR(150) NULL,
  `engine_contract_version` VARCHAR(80) NULL,
  `status` ENUM('RECEIVED','CLARIFICATION','SUCCEEDED','EMPTY','DENIED','UNSUPPORTED','FAILED','TIMED_OUT','CANCELLED') NOT NULL DEFAULT 'RECEIVED',
  `requested_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `finished_at` DATETIME(6) NULL,
  `error_code` VARCHAR(80) NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ai_requests_1` (`thread_id`, `requested_at`),
  KEY `ix_ai_requests_2` (`status`, `requested_at`),
  CONSTRAINT `fk_ai_requests_1` FOREIGN KEY (`thread_id`) REFERENCES `ai_threads` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens 소비 호출 상태/계약 버전, 결과 캐시 비저장';

-- SESSION: Spring Session JDBC 표준 테이블 (프레임워크 공식 DDL 버전 확인)
CREATE TABLE `SPRING_SESSION` (
  `PRIMARY_ID` CHAR(36) NOT NULL,
  `SESSION_ID` CHAR(36) NOT NULL,
  `CREATION_TIME` BIGINT NOT NULL,
  `LAST_ACCESS_TIME` BIGINT NOT NULL,
  `MAX_INACTIVE_INTERVAL` INT NOT NULL,
  `EXPIRY_TIME` BIGINT NOT NULL,
  `PRINCIPAL_NAME` VARCHAR(100) NULL,
  PRIMARY KEY (`PRIMARY_ID`),
  UNIQUE KEY `uk_SPRING_SESSION_1` (`SESSION_ID`),
  KEY `ix_SPRING_SESSION_1` (`EXPIRY_TIME`),
  KEY `ix_SPRING_SESSION_2` (`PRINCIPAL_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Spring Session JDBC 표준 테이블 (프레임워크 공식 DDL 버전 확인)';

-- SESSION: Spring Session JDBC 저장된 세션 속성
CREATE TABLE `SPRING_SESSION_ATTRIBUTES` (
  `SESSION_PRIMARY_ID` CHAR(36) NOT NULL,
  `ATTRIBUTE_NAME` VARCHAR(200) NOT NULL,
  `ATTRIBUTE_BYTES` BLOB NOT NULL,
  PRIMARY KEY (`SESSION_PRIMARY_ID`, `ATTRIBUTE_NAME`),
  CONSTRAINT `fk_SPRING_SESSION_ATTRIBUTES_1` FOREIGN KEY (`SESSION_PRIMARY_ID`) REFERENCES `SPRING_SESSION` (`PRIMARY_ID`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Spring Session JDBC 저장된 세션 속성';

-- TRANSACTION BOUNDARIES (not guaranteed by FK/UNIQUE alone):
-- room_reservations: price = 30-minute slot count * KRW rate; payment/confirmation atomic.
-- room_reservations: SELECT room FOR UPDATE, then assert no overlap [start,end) for CONFIRMED/IN_USE before APPROVE.
-- Confirm by atomically approving Mock payment, marking reservation CONFIRMED and inserting one room_reservation_slots row per 30-minute segment.
-- UNIQUE (room_id,slot_start_at) prevents double booking if another writer skips room locking; transaction rollback on duplicate.
-- On cancellation of unpaid/unused booking: delete owned room_reservation_slots and retain room_reservations CANCELLED + audit.
-- Cancel/Mock full refund must update payment REFUNDED and create refunds once in the same transaction; never issue refund for IN_USE/COMPLETED.
-- Check slot count and timestamps against reservation in application; FK/CHECK cannot establish count(*) = slot_count.
-- locker_assignments: SELECT locker FOR UPDATE, then assert no overlapping ACTIVE date intervals.
-- Locker billing: charged_4week_terms * rate_per_4weeks_snapshot_krw. 1 term = exactly 28 days.
-- Locker rental/charge date ranges use [start_date,end_date), end exclusive.
--   initial end = start_date.plusDays(28 * terms); extension end = current end_date.plusDays(28 * added_terms).
--   e.g. 2027-01-31 + 4 weeks = 2027-02-28; + 8 weeks total = 2027-03-28.
-- CHECK asserts each charge interval is 28 * charged_4week_terms days; application validates contiguous charges and parent assignment.
-- locker_assignment_charges INITIAL: payment APPROVED -> charge APPLIED + assignment ACTIVE in one transaction.
-- locker_assignment_charges EXTENSION: payment APPROVED -> charge APPLIED + extend assignment.end_date atomically.
-- FAILED locker payments cannot activate/extend a rental; retry is controlled by payments.idempotency_key.
-- Locker 4-week term billing confirmed; return/cancellation refund eligibility remains undecided: see README.
-- shifts: SELECT employee FOR UPDATE, then assert no overlapping scheduled shifts across all branches.
-- attendance_breaks: SELECT attendance_record FOR UPDATE, then reject overlapping break intervals/outside work.
-- pass payment FK enforces matching customer, purchase branch and product ID; room payment FK matches customer and branch.
-- payment.status=APPROVED, purpose and amount require service-level validation (cross-row CHECK impossible).
-- refund amount equals original payment; one refund max per payment by UNIQUE and same transaction, no partial refunds.
-- paid room cancellation preserves cancellation actor/reason/time, refunds history, frees 30-minute slots atomically.
-- overage_minutes/overage_krw on seat_sessions are display-only; no automatic extra charge/debt collection in MVP.
-- cross-entity coherency: payment->pass/reservation, branch->seat/room, passes.customer, invitation approval.
-- all write workflows require authorization recheck, transaction, idempotency key/version, audit as relevant.
-- Spring Session max absolute auth age 12h is enforced by application, not by SPRING_SESSION expiry alone.
-- employee email immutability is an API/service contract, not a DDL update restriction.
-- account suspension/logout invalidates Spring sessions + SSE stream from app layer.
-- permission_presets do not dynamically grant rights; copy into membership_permission_grants.
-- invitation sender generates raw token only in transient memory, persists digest/revision, never outbox payload.
-- After this migration is applied, make schema changes in a new Flyway version; do not edit or rerun V1.
