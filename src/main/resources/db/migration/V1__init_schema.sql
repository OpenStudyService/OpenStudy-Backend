-- OpenStudy consolidated clean V1 schema / SRS v1.1 / MySQL 8.4
-- Fresh empty database only. Replaces the legacy development baseline; existing databases must be recreated.
-- Generated from existing verified V3 full-schema export plus M3/M4 V4/V5 proposed physical changes.
-- PERIOD passes start on first admission, exactly N*24h; first-use deadline does not exist.
SET NAMES utf8mb4;
SET time_zone = '+00:00';
CREATE TABLE `SPRING_SESSION` (
  `PRIMARY_ID` char(36) NOT NULL,
  `SESSION_ID` char(36) NOT NULL,
  `CREATION_TIME` bigint NOT NULL,
  `LAST_ACCESS_TIME` bigint NOT NULL,
  `MAX_INACTIVE_INTERVAL` int NOT NULL,
  `EXPIRY_TIME` bigint NOT NULL,
  `PRINCIPAL_NAME` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`PRIMARY_ID`),
  UNIQUE KEY `uk_SPRING_SESSION_1` (`SESSION_ID`),
  KEY `ix_SPRING_SESSION_1` (`EXPIRY_TIME`),
  KEY `ix_SPRING_SESSION_2` (`PRINCIPAL_NAME`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Spring Session JDBC 표준 테이블 (프레임워크 공식 DDL 버전 확인)';

CREATE TABLE `SPRING_SESSION_ATTRIBUTES` (
  `SESSION_PRIMARY_ID` char(36) NOT NULL,
  `ATTRIBUTE_NAME` varchar(200) NOT NULL,
  `ATTRIBUTE_BYTES` blob NOT NULL,
  PRIMARY KEY (`SESSION_PRIMARY_ID`,`ATTRIBUTE_NAME`),
  CONSTRAINT `fk_SPRING_SESSION_ATTRIBUTES_1` FOREIGN KEY (`SESSION_PRIMARY_ID`) REFERENCES `SPRING_SESSION` (`PRIMARY_ID`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Spring Session JDBC 저장된 세션 속성';

CREATE TABLE `administration_guard` (
  `id` tinyint unsigned NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  CONSTRAINT `ck_administration_guard_singleton` CHECK ((`id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `customers` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `member_no` varchar(40) NOT NULL,
  `name` varchar(100) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `email` varchar(254) DEFAULT NULL,
  `status` enum('NORMAL','RESTRICTED','INACTIVE') NOT NULL DEFAULT 'NORMAL',
  `status_reason` varchar(500) DEFAULT NULL,
  `registered_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customers_1` (`member_no`),
  KEY `ix_customers_1` (`name`),
  KEY `ix_customers_2` (`phone`),
  KEY `ix_customers_3` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='전사 공통 고객 원장; 직원 로그인과 무관';

CREATE TABLE `employees` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
  `password_hash` varchar(255) NOT NULL,
  `name` varchar(100) NOT NULL,
  `nickname` varchar(100) NOT NULL,
  `avatar_url` varchar(500) DEFAULT NULL,
  `global_role` enum('OWNER','EMPLOYEE') NOT NULL DEFAULT 'EMPLOYEE',
  `owner_slot` tinyint GENERATED ALWAYS AS ((case when (`global_role` = _utf8mb4'OWNER') then 1 else NULL end)) STORED,
  `status` enum('PENDING','ACTIVE','SUSPENDED','LEFT') NOT NULL DEFAULT 'PENDING',
  `theme` enum('FOREST','NAVY','SAND') NOT NULL DEFAULT 'FOREST',
  `last_login_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `created_by` bigint unsigned DEFAULT NULL,
  `updated_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employees_1` (`email`),
  UNIQUE KEY `uk_employees_2` (`owner_slot`),
  KEY `ix_employees_1` (`status`),
  KEY `fk_employees_created_by` (`created_by`),
  KEY `fk_employees_updated_by` (`updated_by`),
  CONSTRAINT `fk_employees_created_by` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_employees_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원 로그인 계정; 이메일 ID 불변, 최초 소유자 1명';

CREATE TABLE `facility_types` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(80) NOT NULL,
  `default_inspection_days` smallint unsigned NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_facility_types_1` (`name`),
  CONSTRAINT `ck_facility_types_1` CHECK ((`default_inspection_days` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='시설 유형과 기본 점검 주기';

CREATE TABLE `idempotency_records` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `actor_employee_id` bigint unsigned NOT NULL,
  `operation` varchar(120) NOT NULL,
  `scope_key` varchar(160) NOT NULL,
  `idempotency_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `request_hash` binary(32) NOT NULL,
  `state` enum('PROCESSING','COMPLETED') NOT NULL,
  `http_status` smallint unsigned DEFAULT NULL,
  `response_json` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `expires_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_request` (`actor_employee_id`,`operation`,`scope_key`,`idempotency_key`),
  KEY `ix_idempotency_expiry` (`expires_at`,`id`),
  CONSTRAINT `fk_idempotency_actor` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `leave_requests` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `request_year` smallint unsigned NOT NULL,
  `starts_on` date NOT NULL,
  `ends_on` date NOT NULL,
  `requested_days` decimal(7,1) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `status` enum('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
  `decided_by` bigint unsigned DEFAULT NULL,
  `decided_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_leave_requests_1` (`employee_id`,`starts_on`,`ends_on`,`status`),
  KEY `fk_leave_requests_2` (`decided_by`),
  CONSTRAINT `fk_leave_requests_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_requests_2` FOREIGN KEY (`decided_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_leave_requests_1` CHECK ((`ends_on` >= `starts_on`)),
  CONSTRAINT `ck_leave_requests_2` CHECK (((year(`starts_on`) = `request_year`) and (year(`ends_on`) = `request_year`))),
  CONSTRAINT `ck_leave_requests_3` CHECK ((`requested_days` > 0)),
  CONSTRAINT `ck_leave_requests_4` CHECK ((((`requested_days` * 2) % 1) = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기간+총일수 휴가 신청 (반차 시간대/비연속 날짜는 제외)';

CREATE TABLE `permission_definitions` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(80) NOT NULL,
  `description` varchar(255) NOT NULL,
  `is_admin_only` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_definitions_1` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='행동 권한 코드 사전';

CREATE TABLE `permission_presets` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL,
  `role` enum('MANAGER','STAFF') NOT NULL,
  `is_system` tinyint(1) NOT NULL DEFAULT '0',
  `created_by` bigint unsigned DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `updated_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_presets_1` (`name`,`role`),
  KEY `fk_permission_presets_created_by` (`created_by`),
  KEY `fk_permission_presets_updated_by` (`updated_by`),
  CONSTRAINT `fk_permission_presets_created_by` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_permission_presets_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기본 및 소유자 정의 지점 권한 프리셋';

CREATE TABLE `product_categories` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(40) NOT NULL,
  `name` varchar(100) NOT NULL,
  `kind` enum('TIME','PERIOD','STORED_VALUE') NOT NULL,
  `status` enum('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `created_by` bigint unsigned DEFAULT NULL,
  `updated_by` bigint unsigned DEFAULT NULL,
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_categories_code` (`code`),
  KEY `ix_categories_kind_status` (`kind`,`status`,`id`),
  KEY `fk_categories_created_by` (`created_by`),
  KEY `fk_categories_updated_by` (`updated_by`),
  CONSTRAINT `fk_categories_created_by` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_categories_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `products` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(40) NOT NULL,
  `name` varchar(120) NOT NULL,
  `kind` enum('TIME','PERIOD','STORED_VALUE') NOT NULL,
  `price_krw` bigint unsigned NOT NULL,
  `included_minutes` int unsigned DEFAULT NULL,
  `period_days` int unsigned DEFAULT NULL,
  `credit_krw` bigint unsigned DEFAULT NULL,
  `hourly_rate_krw` bigint unsigned DEFAULT NULL,
  `valid_days` int unsigned DEFAULT NULL,
  `sale_status` enum('ON_SALE','STOPPED') NOT NULL DEFAULT 'ON_SALE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `category_id` bigint unsigned NOT NULL,
  `created_by` bigint unsigned DEFAULT NULL,
  `updated_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_products_1` (`code`),
  KEY `ix_products_1` (`kind`,`sale_status`),
  KEY `ix_products_category` (`category_id`,`id`),
  KEY `fk_products_created_by` (`created_by`),
  KEY `fk_products_updated_by` (`updated_by`),
  CONSTRAINT `fk_products_category_required` FOREIGN KEY (`category_id`) REFERENCES `product_categories` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_products_created_by` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_products_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_products_1` CHECK ((((`kind` = _utf8mb4'TIME') and (`included_minutes` is not null) and (`included_minutes` > 0) and (`period_days` is null) and (`credit_krw` is null) and (`hourly_rate_krw` is null)) or ((`kind` = _utf8mb4'PERIOD') and (`period_days` is not null) and (`period_days` > 0) and (`included_minutes` is null) and (`credit_krw` is null) and (`hourly_rate_krw` is null)) or ((`kind` = _utf8mb4'STORED_VALUE') and (`credit_krw` is not null) and (`hourly_rate_krw` is not null) and (`credit_krw` > 0) and (`hourly_rate_krw` > 0) and (`included_minutes` is null) and (`period_days` is null)))),
  CONSTRAINT `ck_products_2` CHECK (((`valid_days` is null) or (`valid_days` > 0))),
  CONSTRAINT `ck_products_period_validity` CHECK (((`kind` <> _utf8mb4'PERIOD') or (`valid_days` is null))),
  CONSTRAINT `ck_products_positive_price` CHECK ((`price_krw` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='시간권/기간권/정액권 상품';

CREATE TABLE `ai_threads` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `last_used_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ai_threads_1` (`employee_id`,`created_at`),
  CONSTRAINT `fk_ai_threads_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='별도 AI 대화. 팀 채팅과 데이터·권한 분리';

CREATE TABLE `announcements` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `author_employee_id` bigint unsigned NOT NULL,
  `title` varchar(200) NOT NULL,
  `body` text NOT NULL,
  `target` enum('ALL','SELECTED_BRANCHES') NOT NULL,
  `status` enum('DRAFT','PUBLISHED','ARCHIVED') NOT NULL DEFAULT 'DRAFT',
  `is_pinned` tinyint(1) NOT NULL DEFAULT '0',
  `published_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_announcements_1` (`status`,`is_pinned`,`published_at`),
  KEY `fk_announcements_1` (`author_employee_id`),
  CONSTRAINT `fk_announcements_1` FOREIGN KEY (`author_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='내부 공지. 전체/지점 공개범위';

CREATE TABLE `branches` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(32) NOT NULL,
  `name` varchar(120) NOT NULL,
  `address` varchar(300) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `status` enum('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `created_by` bigint unsigned DEFAULT NULL,
  `updated_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branches_1` (`code`),
  KEY `ix_branches_1` (`status`),
  KEY `fk_branches_created_by` (`created_by`),
  KEY `fk_branches_updated_by` (`updated_by`),
  CONSTRAINT `fk_branches_created_by` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_branches_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='스터디카페 지점';

CREATE TABLE `conversations` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `kind` enum('GLOBAL_CHANNEL','BRANCH_CHANNEL','DM') NOT NULL,
  `branch_id` bigint unsigned DEFAULT NULL,
  `name` varchar(120) DEFAULT NULL,
  `dm_employee_low_id` bigint unsigned DEFAULT NULL,
  `dm_employee_high_id` bigint unsigned DEFAULT NULL,
  `created_by` bigint unsigned DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conversations_1` (`dm_employee_low_id`,`dm_employee_high_id`),
  KEY `ix_conversations_1` (`branch_id`,`kind`),
  KEY `fk_conversations_3` (`dm_employee_high_id`),
  KEY `fk_conversations_4` (`created_by`),
  CONSTRAINT `fk_conversations_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversations_2` FOREIGN KEY (`dm_employee_low_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversations_3` FOREIGN KEY (`dm_employee_high_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversations_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_conversations_1` CHECK ((((`kind` = _utf8mb4'DM') and (`branch_id` is null) and (`dm_employee_low_id` is not null) and (`dm_employee_high_id` is not null) and (`dm_employee_low_id` < `dm_employee_high_id`)) or ((`kind` = _utf8mb4'GLOBAL_CHANNEL') and (`branch_id` is null) and (`name` is not null) and (`dm_employee_low_id` is null) and (`dm_employee_high_id` is null)) or ((`kind` = _utf8mb4'BRANCH_CHANNEL') and (`branch_id` is not null) and (`name` is not null) and (`dm_employee_low_id` is null) and (`dm_employee_high_id` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='전체/지점 채널 또는 1대1 DM';

CREATE TABLE `customer_status_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` bigint unsigned NOT NULL,
  `previous_status` varchar(30) NOT NULL,
  `new_status` varchar(30) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `actor_employee_id` bigint unsigned NOT NULL,
  `changed_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_customer_status_events_1` (`customer_id`,`changed_at`),
  KEY `fk_customer_status_events_2` (`actor_employee_id`),
  CONSTRAINT `fk_customer_status_events_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_status_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객 상태/제한 변경 이력';

CREATE TABLE `employee_invitations` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `invitee_email` varchar(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci NOT NULL,
  `initial_role` enum('MANAGER','STAFF') NOT NULL,
  `preset_id` bigint unsigned DEFAULT NULL,
  `created_by` bigint unsigned NOT NULL,
  `accepted_employee_id` bigint unsigned DEFAULT NULL,
  `approved_by` bigint unsigned DEFAULT NULL,
  `status` enum('PENDING','ACCEPTED','APPROVED','REJECTED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'PENDING',
  `active_invitation_slot` tinyint GENERATED ALWAYS AS ((case when (`status` in (_utf8mb4'PENDING',_utf8mb4'ACCEPTED')) then 1 else NULL end)) STORED,
  `token_hash` binary(32) DEFAULT NULL,
  `revision` int unsigned NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `expires_at` datetime(6) NOT NULL,
  `accepted_at` datetime(6) DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `invitee_name` varchar(100) NOT NULL,
  `rejected_by` bigint unsigned DEFAULT NULL,
  `rejected_at` datetime(6) DEFAULT NULL,
  `rejection_reason` varchar(500) DEFAULT NULL,
  `cancelled_by` bigint unsigned DEFAULT NULL,
  `cancelled_at` datetime(6) DEFAULT NULL,
  `updated_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_employee_invitations_1` (`branch_id`,`invitee_email`,`active_invitation_slot`),
  UNIQUE KEY `uk_invitations_token_hash` (`token_hash`),
  KEY `ix_employee_invitations_1` (`invitee_email`,`status`),
  KEY `ix_employee_invitations_2` (`status`,`expires_at`),
  KEY `fk_employee_invitations_2` (`preset_id`),
  KEY `fk_employee_invitations_3` (`created_by`),
  KEY `fk_invitations_accepted_by` (`accepted_employee_id`),
  KEY `fk_invitations_approved_by` (`approved_by`),
  KEY `fk_invitations_rejected_by` (`rejected_by`),
  KEY `fk_invitations_cancelled_by` (`cancelled_by`),
  KEY `fk_invitations_updated_by` (`updated_by`),
  KEY `ix_invitations_branch_status` (`branch_id`,`status`,`id`),
  CONSTRAINT `fk_employee_invitations_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_employee_invitations_2` FOREIGN KEY (`preset_id`) REFERENCES `permission_presets` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_employee_invitations_3` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_invitations_accepted_by` FOREIGN KEY (`accepted_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_invitations_approved_by` FOREIGN KEY (`approved_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_invitations_cancelled_by` FOREIGN KEY (`cancelled_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_invitations_rejected_by` FOREIGN KEY (`rejected_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_invitations_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_employee_invitations_1` CHECK ((`revision` >= 0)),
  CONSTRAINT `ck_invitations_acceptance` CHECK ((((`status` in (_utf8mb4'ACCEPTED',_utf8mb4'APPROVED',_utf8mb4'REJECTED')) and (`accepted_employee_id` is not null) and (`accepted_at` is not null)) or ((`status` in (_utf8mb4'PENDING',_utf8mb4'CANCELLED',_utf8mb4'EXPIRED')) and (`accepted_employee_id` is null) and (`accepted_at` is null)))),
  CONSTRAINT `ck_invitations_approval` CHECK ((((`status` = _utf8mb4'APPROVED') and (`approved_by` is not null) and (`approved_at` is not null)) or ((`status` <> _utf8mb4'APPROVED') and (`approved_by` is null) and (`approved_at` is null)))),
  CONSTRAINT `ck_invitations_cancellation` CHECK ((((`status` = _utf8mb4'CANCELLED') and (`cancelled_by` is not null) and (`cancelled_at` is not null)) or ((`status` <> _utf8mb4'CANCELLED') and (`cancelled_by` is null) and (`cancelled_at` is null)))),
  CONSTRAINT `ck_invitations_name` CHECK ((char_length(trim(`invitee_name`)) > 0)),
  CONSTRAINT `ck_invitations_rejection` CHECK ((((`status` = _utf8mb4'REJECTED') and (`rejected_by` is not null) and (`rejected_at` is not null) and (`rejection_reason` is not null) and (char_length(trim(`rejection_reason`)) > 0)) or ((`status` <> _utf8mb4'REJECTED') and (`rejected_by` is null) and (`rejected_at` is null) and (`rejection_reason` is null)))),
  CONSTRAINT `ck_invitations_token_disposal` CHECK (((`status` = _utf8mb4'PENDING') or (`token_hash` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='초대 해시/24시간/발송 revision/최종 승인';

CREATE TABLE `facilities` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `type_id` bigint unsigned NOT NULL,
  `name` varchar(120) NOT NULL,
  `location` varchar(200) DEFAULT NULL,
  `description` text,
  `status` enum('NORMAL','CHECK_NEEDED','BROKEN','OUT_OF_SERVICE') NOT NULL DEFAULT 'NORMAL',
  `inspection_interval_days` smallint unsigned NOT NULL,
  `last_inspected_on` date DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_facilities_1` (`branch_id`,`status`),
  KEY `ix_facilities_2` (`branch_id`,`last_inspected_on`),
  KEY `fk_facilities_2` (`type_id`),
  CONSTRAINT `fk_facilities_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_facilities_2` FOREIGN KEY (`type_id`) REFERENCES `facility_types` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_facilities_1` CHECK ((`inspection_interval_days` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 시설: 상태/점검 주기/마지막 완료일';

CREATE TABLE `facility_works` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `facility_id` bigint unsigned NOT NULL,
  `kind` enum('INSPECTION','CLEANING','REPAIR') NOT NULL,
  `status` enum('OPEN','IN_PROGRESS','DONE','CANCELLED') NOT NULL DEFAULT 'OPEN',
  `problem` varchar(500) DEFAULT NULL,
  `result_note` text,
  `assigned_employee_id` bigint unsigned DEFAULT NULL,
  `completed_by` bigint unsigned DEFAULT NULL,
  `opened_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `completed_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_facility_works_1` (`facility_id`,`opened_at`),
  KEY `ix_facility_works_2` (`status`,`opened_at`),
  KEY `fk_facility_works_2` (`assigned_employee_id`),
  KEY `fk_facility_works_3` (`completed_by`),
  CONSTRAINT `fk_facility_works_1` FOREIGN KEY (`facility_id`) REFERENCES `facilities` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_facility_works_2` FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_facility_works_3` FOREIGN KEY (`completed_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='점검·청소·수리 이력. 작업완료 != 시설 정상';

CREATE TABLE `inquiries` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `subject` varchar(200) NOT NULL,
  `body` text NOT NULL,
  `source` enum('IN_PERSON','PHONE','OTHER') NOT NULL,
  `status` enum('RECEIVED','IN_PROGRESS','ANSWERED') NOT NULL DEFAULT 'RECEIVED',
  `assigned_employee_id` bigint unsigned DEFAULT NULL,
  `created_by` bigint unsigned NOT NULL,
  `answered_by` bigint unsigned DEFAULT NULL,
  `answer_body` text,
  `answered_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_inquiries_1` (`branch_id`,`status`,`created_at`),
  KEY `ix_inquiries_2` (`customer_id`,`created_at`),
  KEY `fk_inquiries_3` (`assigned_employee_id`),
  KEY `fk_inquiries_4` (`created_by`),
  KEY `fk_inquiries_5` (`answered_by`),
  CONSTRAINT `fk_inquiries_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiries_2` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiries_3` FOREIGN KEY (`assigned_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_inquiries_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiries_5` FOREIGN KEY (`answered_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객 전화/현장 문의 백오피스 대리 접수';

CREATE TABLE `inquiry_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `inquiry_id` bigint unsigned NOT NULL,
  `actor_employee_id` bigint unsigned NOT NULL,
  `old_status` varchar(30) DEFAULT NULL,
  `new_status` varchar(30) NOT NULL,
  `message` text,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_inquiry_events_1` (`inquiry_id`,`created_at`),
  KEY `fk_inquiry_events_2` (`actor_employee_id`),
  CONSTRAINT `fk_inquiry_events_1` FOREIGN KEY (`inquiry_id`) REFERENCES `inquiries` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_inquiry_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='문의 답변·상태 변경 이력';

CREATE TABLE `invitation_permission_grants` (
  `invitation_id` bigint unsigned NOT NULL,
  `permission_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`invitation_id`,`permission_id`),
  KEY `fk_invitation_grants_permission` (`permission_id`),
  CONSTRAINT `fk_invitation_grants_invitation` FOREIGN KEY (`invitation_id`) REFERENCES `employee_invitations` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_invitation_grants_permission` FOREIGN KEY (`permission_id`) REFERENCES `permission_definitions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `invitation_send_jobs` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `invitation_id` bigint unsigned NOT NULL,
  `revision` int unsigned NOT NULL,
  `status` enum('PENDING','PROCESSING','SENT','FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
  `attempts` int unsigned NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `last_error_code` varchar(80) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `locked_until` datetime(6) DEFAULT NULL,
  `worker_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invitation_send_jobs_1` (`invitation_id`,`revision`),
  KEY `ix_invitation_send_jobs_1` (`status`,`next_attempt_at`),
  KEY `ix_send_jobs_lease` (`status`,`locked_until`,`id`),
  CONSTRAINT `fk_invitation_send_jobs_1` FOREIGN KEY (`invitation_id`) REFERENCES `employee_invitations` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='메일 발송 outbox; 토큰 원문/재생성 가능한 시크릿 저장 금지';

CREATE TABLE `leave_grants` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `origin_branch_id` bigint unsigned NOT NULL,
  `grant_year` smallint unsigned NOT NULL,
  `valid_from` date NOT NULL,
  `valid_through` date NOT NULL,
  `granted_days` decimal(7,1) NOT NULL,
  `remaining_days` decimal(7,1) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `granted_by` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_leave_grants_1` (`employee_id`,`grant_year`,`valid_through`,`id`),
  KEY `fk_leave_grants_2` (`origin_branch_id`),
  KEY `fk_leave_grants_3` (`granted_by`),
  CONSTRAINT `fk_leave_grants_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_grants_2` FOREIGN KEY (`origin_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_grants_3` FOREIGN KEY (`granted_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_leave_grants_1` CHECK ((`valid_through` >= `valid_from`)),
  CONSTRAINT `ck_leave_grants_2` CHECK ((`granted_days` > 0)),
  CONSTRAINT `ck_leave_grants_3` CHECK ((`remaining_days` >= 0)),
  CONSTRAINT `ck_leave_grants_4` CHECK ((((`granted_days` * 2) % 1) = 0)),
  CONSTRAINT `ck_leave_grants_5` CHECK ((((`remaining_days` * 2) % 1) = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원·연도 공통 휴가 부여, 원부여 지점 유지';

CREATE TABLE `lockers` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `locker_no` varchar(40) NOT NULL,
  `rate_per_4weeks_krw` bigint unsigned NOT NULL,
  `status` enum('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lockers_1` (`branch_id`,`locker_no`),
  CONSTRAINT `fk_lockers_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_lockers_1` CHECK ((`rate_per_4weeks_krw` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 사물함';

CREATE TABLE `payments` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned DEFAULT NULL,
  `product_name_snapshot` varchar(120) DEFAULT NULL,
  `purpose` enum('PASS','ROOM','LOCKER') NOT NULL,
  `method` enum('CASH','CARD','TRANSFER','OTHER') NOT NULL,
  `amount_krw` bigint unsigned NOT NULL,
  `status` enum('PENDING','APPROVED','FAILED','REFUNDED') NOT NULL DEFAULT 'PENDING',
  `idempotency_key` varchar(120) NOT NULL,
  `issued_pass_quantity` smallint unsigned NOT NULL DEFAULT '0',
  `approved_at` datetime(6) DEFAULT NULL,
  `failed_at` datetime(6) DEFAULT NULL,
  `created_by` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payments_1` (`idempotency_key`),
  UNIQUE KEY `uk_payments_2` (`id`,`customer_id`,`branch_id`),
  UNIQUE KEY `uk_payments_3` (`id`,`customer_id`,`branch_id`,`product_id`),
  KEY `ix_payments_1` (`branch_id`,`status`,`approved_at`),
  KEY `ix_payments_2` (`customer_id`,`created_at`),
  KEY `ix_payments_3` (`product_id`,`approved_at`),
  KEY `fk_payments_4` (`created_by`),
  CONSTRAINT `fk_payments_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_3` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_payments_1` CHECK ((((`purpose` = _utf8mb4'PASS') and (`product_id` is not null)) or ((`purpose` in (_utf8mb4'ROOM',_utf8mb4'LOCKER')) and (`product_id` is null)))),
  CONSTRAINT `ck_payments_2` CHECK ((`amount_krw` > 0)),
  CONSTRAINT `ck_payments_issued_pass_quantity` CHECK (
    (purpose = 'PASS' AND ((status IN ('APPROVED','REFUNDED') AND issued_pass_quantity = 1)
      OR (status IN ('PENDING','FAILED') AND issued_pass_quantity = 0)))
    OR (purpose <> 'PASS' AND issued_pass_quantity = 0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='이용권·스터디룸·사물함 내부 Mock 승인/실패, 구매 지점의 원거래';

CREATE TABLE `permission_preset_grants` (
  `preset_id` bigint unsigned NOT NULL,
  `permission_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`preset_id`,`permission_id`),
  KEY `fk_permission_preset_grants_2` (`permission_id`),
  CONSTRAINT `fk_permission_preset_grants_1` FOREIGN KEY (`preset_id`) REFERENCES `permission_presets` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_permission_preset_grants_2` FOREIGN KEY (`permission_id`) REFERENCES `permission_definitions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='프리셋 권한 원본 (복사시 기존 직원에 영향 없음)';

CREATE TABLE `product_branches` (
  `product_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`product_id`,`branch_id`),
  KEY `ix_product_branches_reverse` (`branch_id`,`product_id`),
  CONSTRAINT `fk_product_branches_branch` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_product_branches_product` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `product_revisions` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `product_id` bigint unsigned DEFAULT NULL,
  `original_product_id` bigint unsigned NOT NULL,
  `product_version` bigint unsigned NOT NULL,
  `event_type` enum('CREATED','UPDATED','DELETED','BASELINE') NOT NULL,
  `changed_by` bigint unsigned DEFAULT NULL,
  `changed_at` datetime(6) NOT NULL,
  `request_id` varchar(128) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `snapshot_json` json NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_revisions_version` (`original_product_id`,`product_version`),
  UNIQUE KEY `uk_product_revisions_id_original` (`id`,`original_product_id`),
  KEY `ix_product_revisions_history` (`original_product_id`,`changed_at`,`id`),
  KEY `fk_product_revisions_live` (`product_id`),
  KEY `fk_product_revisions_actor` (`changed_by`),
  CONSTRAINT `fk_product_revisions_actor` FOREIGN KEY (`changed_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_product_revisions_live` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `refunds` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `payment_id` bigint unsigned NOT NULL,
  `processing_branch_id` bigint unsigned NOT NULL,
  `processed_by` bigint unsigned NOT NULL,
  `amount_krw` bigint unsigned NOT NULL,
  `reason` varchar(500) NOT NULL,
  `idempotency_key` varchar(120) NOT NULL,
  `refunded_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refunds_1` (`payment_id`),
  UNIQUE KEY `uk_refunds_2` (`idempotency_key`),
  KEY `ix_refunds_1` (`refunded_at`),
  KEY `ix_refunds_2` (`processing_branch_id`,`refunded_at`),
  KEY `fk_refunds_3` (`processed_by`),
  CONSTRAINT `fk_refunds_1` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_refunds_2` FOREIGN KEY (`processing_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_refunds_3` FOREIGN KEY (`processed_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_refunds_1` CHECK ((`amount_krw` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='원거래 1회 전액 환불, 처리지점/원결제지점 분리';

CREATE TABLE `rooms` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `room_no` varchar(40) NOT NULL,
  `name` varchar(120) NOT NULL,
  `capacity` smallint unsigned NOT NULL,
  `rate_per_30min_krw` bigint unsigned NOT NULL,
  `status` enum('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rooms_1` (`branch_id`,`room_no`),
  UNIQUE KEY `uk_rooms_2` (`id`,`branch_id`),
  CONSTRAINT `fk_rooms_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_rooms_1` CHECK ((`capacity` > 0)),
  CONSTRAINT `ck_rooms_2` CHECK ((`rate_per_30min_krw` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='스터디룸/정원/30분당 요금';

CREATE TABLE `seats` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `zone` varchar(80) NOT NULL,
  `seat_no` varchar(30) NOT NULL,
  `kind` enum('GENERAL','MONITOR','OTHER') NOT NULL DEFAULT 'GENERAL',
  `status` enum('AVAILABLE','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seats_1` (`branch_id`,`seat_no`),
  UNIQUE KEY `uk_seats_2` (`id`,`branch_id`),
  KEY `ix_seats_1` (`branch_id`,`zone`,`status`),
  CONSTRAINT `fk_seats_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 좌석: OCCUPIED는 활성 seat_session으로 계산';

CREATE TABLE `workspace_event_outbox` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned DEFAULT NULL,
  `target_employee_id` bigint unsigned DEFAULT NULL,
  `entity_type` varchar(80) NOT NULL,
  `entity_id` bigint unsigned DEFAULT NULL,
  `event_type` enum('INVALIDATE','AUTHORIZATION_CHANGED') NOT NULL,
  `occurred_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `published_at` datetime(6) DEFAULT NULL,
  `attempts` int unsigned NOT NULL DEFAULT '0',
  `next_attempt_at` datetime(6) DEFAULT NULL,
  `locked_until` datetime(6) DEFAULT NULL,
  `worker_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_workspace_events_delivery` (`published_at`,`next_attempt_at`,`id`),
  KEY `ix_workspace_events_target` (`target_employee_id`,`id`),
  KEY `fk_workspace_events_branch` (`branch_id`),
  CONSTRAINT `fk_workspace_events_branch` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_workspace_events_employee` FOREIGN KEY (`target_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ai_requests` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `thread_id` bigint unsigned NOT NULL,
  `engine_request_ref` varchar(150) DEFAULT NULL,
  `engine_contract_version` varchar(80) DEFAULT NULL,
  `status` enum('RECEIVED','CLARIFICATION','SUCCEEDED','EMPTY','DENIED','UNSUPPORTED','FAILED','TIMED_OUT','CANCELLED') NOT NULL DEFAULT 'RECEIVED',
  `requested_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `finished_at` datetime(6) DEFAULT NULL,
  `error_code` varchar(80) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_ai_requests_1` (`thread_id`,`requested_at`),
  KEY `ix_ai_requests_2` (`status`,`requested_at`),
  CONSTRAINT `fk_ai_requests_1` FOREIGN KEY (`thread_id`) REFERENCES `ai_threads` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='DomainLens 소비 호출 상태/계약 버전, 결과 캐시 비저장';

CREATE TABLE `announcement_branches` (
  `announcement_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`announcement_id`,`branch_id`),
  KEY `ix_announcement_branches_1` (`branch_id`,`announcement_id`),
  CONSTRAINT `fk_announcement_branches_1` FOREIGN KEY (`announcement_id`) REFERENCES `announcements` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_announcement_branches_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 대상 공지 배포';

CREATE TABLE `audit_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `actor_employee_id` bigint unsigned DEFAULT NULL,
  `branch_id` bigint unsigned DEFAULT NULL,
  `entity_type` varchar(80) NOT NULL,
  `entity_id` bigint unsigned DEFAULT NULL,
  `action` varchar(80) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `before_json` json DEFAULT NULL,
  `after_json` json DEFAULT NULL,
  `occurred_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `request_id` varchar(128) DEFAULT NULL,
  `result` enum('SUCCESS','DENIED','FAILED') NOT NULL DEFAULT 'SUCCESS',
  PRIMARY KEY (`id`),
  KEY `ix_audit_events_1` (`entity_type`,`entity_id`,`occurred_at`),
  KEY `ix_audit_events_2` (`actor_employee_id`,`occurred_at`),
  KEY `ix_audit_events_3` (`branch_id`,`occurred_at`),
  KEY `ix_audit_request` (`request_id`),
  CONSTRAINT `fk_audit_events_1` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_audit_events_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='핵심 변경 감사. JSON은 민감정보 제거 후 저장';

CREATE TABLE `branch_memberships` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `role` enum('MANAGER','STAFF') NOT NULL DEFAULT 'STAFF',
  `status` enum('PENDING','ACTIVE','REVOKED') NOT NULL DEFAULT 'PENDING',
  `approved_by` bigint unsigned DEFAULT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  `created_by` bigint unsigned DEFAULT NULL,
  `updated_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branch_memberships_1` (`employee_id`,`branch_id`),
  UNIQUE KEY `uk_branch_memberships_2` (`branch_id`,`employee_id`),
  KEY `ix_branch_memberships_1` (`branch_id`,`status`,`role`),
  KEY `fk_memberships_approved_by` (`approved_by`),
  KEY `fk_memberships_created_by` (`created_by`),
  KEY `fk_memberships_updated_by` (`updated_by`),
  CONSTRAINT `fk_branch_memberships_1` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_branch_memberships_2` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_memberships_approved_by` FOREIGN KEY (`approved_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_memberships_created_by` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_memberships_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원-지점 배정과 관리자 최종 승인 상태';

CREATE TABLE `branch_weekly_hours` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `weekday` tinyint unsigned NOT NULL,
  `is_closed` tinyint(1) NOT NULL DEFAULT '0',
  `opens_minute` smallint unsigned DEFAULT NULL,
  `closes_minute` smallint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_branch_weekly_hours_1` (`branch_id`,`weekday`),
  CONSTRAINT `fk_branch_weekly_hours_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_branch_weekly_hours_1` CHECK ((`weekday` between 1 and 7)),
  CONSTRAINT `ck_branch_weekly_hours_2` CHECK ((((`is_closed` = 1) and (`opens_minute` is null) and (`closes_minute` is null)) or ((`is_closed` = 0) and (`opens_minute` is not null) and (`closes_minute` is not null) and (`opens_minute` < 1440) and (`closes_minute` > `opens_minute`) and (`closes_minute` <= 2880))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='지점 기본 영업시간. 분 단위, 종료 1440 초과는 다음날';

CREATE TABLE `conversation_members` (
  `conversation_id` bigint unsigned NOT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `joined_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `left_at` datetime(6) DEFAULT NULL,
  `last_read_message_id` bigint unsigned DEFAULT NULL,
  `last_read_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`conversation_id`,`employee_id`),
  KEY `ix_conversation_members_1` (`employee_id`,`conversation_id`),
  CONSTRAINT `fk_conversation_members_1` FOREIGN KEY (`conversation_id`) REFERENCES `conversations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_conversation_members_2` FOREIGN KEY (`employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='채팅 참여자/읽음 위치';

CREATE TABLE `customer_service_interactions` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `reason` varchar(500) NOT NULL,
  `verified_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_customer_service_interactions_1` (`customer_id`,`branch_id`,`verified_at`),
  KEY `ix_customer_service_interactions_2` (`employee_id`,`verified_at`),
  KEY `fk_customer_service_interactions_2` (`branch_id`,`employee_id`),
  CONSTRAINT `fk_customer_service_interactions_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_service_interactions_2` FOREIGN KEY (`branch_id`, `employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='응대 중인 특정 고객의 전 지점 이용 조회 근거와 접근 감사';

CREATE TABLE `leave_allocations` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `leave_request_id` bigint unsigned NOT NULL,
  `leave_grant_id` bigint unsigned NOT NULL,
  `allocated_days` decimal(7,1) NOT NULL,
  `restored_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leave_allocations_1` (`leave_request_id`,`leave_grant_id`),
  KEY `fk_leave_allocations_2` (`leave_grant_id`),
  CONSTRAINT `fk_leave_allocations_1` FOREIGN KEY (`leave_request_id`) REFERENCES `leave_requests` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_allocations_2` FOREIGN KEY (`leave_grant_id`) REFERENCES `leave_grants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_leave_allocations_1` CHECK ((`allocated_days` > 0)),
  CONSTRAINT `ck_leave_allocations_2` CHECK ((((`allocated_days` * 2) % 1) = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='승인 시 소비한 각 부여 배분과 취소 복원 마커';

CREATE TABLE `leave_balance_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `leave_grant_id` bigint unsigned NOT NULL,
  `leave_request_id` bigint unsigned DEFAULT NULL,
  `event_type` enum('GRANT','ADJUST','USE','RESTORE') NOT NULL,
  `delta_days` decimal(7,1) NOT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `actor_employee_id` bigint unsigned NOT NULL,
  `event_key` varchar(120) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_leave_balance_events_1` (`event_key`),
  KEY `ix_leave_balance_events_1` (`leave_grant_id`,`created_at`),
  KEY `ix_leave_balance_events_2` (`leave_request_id`,`event_type`),
  KEY `fk_leave_balance_events_3` (`actor_employee_id`),
  CONSTRAINT `fk_leave_balance_events_1` FOREIGN KEY (`leave_grant_id`) REFERENCES `leave_grants` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_balance_events_2` FOREIGN KEY (`leave_request_id`) REFERENCES `leave_requests` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leave_balance_events_3` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_leave_balance_events_1` CHECK ((`delta_days` <> 0)),
  CONSTRAINT `ck_leave_balance_events_2` CHECK ((((`delta_days` * 2) % 1) = 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='휴가 부여/보정/소비/복원 불변 원장';

CREATE TABLE `locker_assignments` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `locker_id` bigint unsigned NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `start_date` date NOT NULL,
  `end_date` date NOT NULL,
  `returned_at` datetime(6) DEFAULT NULL,
  `status` enum('PAYMENT_PENDING','PAYMENT_FAILED','ACTIVE','RETURNED','CANCELLED') NOT NULL DEFAULT 'PAYMENT_PENDING',
  `assigned_by` bigint unsigned NOT NULL,
  `last_changed_by` bigint unsigned DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_locker_assignments_1` (`locker_id`,`start_date`,`end_date`,`status`),
  KEY `ix_locker_assignments_2` (`customer_id`,`status`),
  KEY `fk_locker_assignments_3` (`assigned_by`),
  KEY `fk_locker_assignments_4` (`last_changed_by`),
  CONSTRAINT `fk_locker_assignments_1` FOREIGN KEY (`locker_id`) REFERENCES `lockers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignments_2` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignments_3` FOREIGN KEY (`assigned_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignments_4` FOREIGN KEY (`last_changed_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_locker_assignments_1` CHECK ((`end_date` > `start_date`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사물함 배정/유료 최초 결제·연장, 날짜 구간 [start,end)';

CREATE TABLE `membership_permission_grants` (
  `membership_id` bigint unsigned NOT NULL,
  `permission_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`membership_id`,`permission_id`),
  KEY `fk_membership_permission_grants_2` (`permission_id`),
  CONSTRAINT `fk_membership_permission_grants_1` FOREIGN KEY (`membership_id`) REFERENCES `branch_memberships` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_membership_permission_grants_2` FOREIGN KEY (`permission_id`) REFERENCES `permission_definitions` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원 지점별 실효 권한의 스냅샷';

CREATE TABLE `passes` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` bigint unsigned NOT NULL,
  `product_id` bigint unsigned NOT NULL,
  `purchase_payment_id` bigint unsigned NOT NULL,
  `purchase_branch_id` bigint unsigned NOT NULL,
  `purchase_product_revision_id` bigint unsigned NOT NULL,
  `kind` enum('TIME','PERIOD','STORED_VALUE') NOT NULL,
  `snapshot_price_krw` bigint unsigned NOT NULL,
  `snapshot_product_name` varchar(120) NOT NULL,
  `snapshot_category_code` varchar(40) NOT NULL,
  `snapshot_category_name` varchar(100) NOT NULL,
  `snapshot_minutes` int unsigned DEFAULT NULL,
  `snapshot_period_days` int unsigned DEFAULT NULL,
  `snapshot_credit_krw` bigint unsigned DEFAULT NULL,
  `snapshot_hourly_rate_krw` bigint unsigned DEFAULT NULL,
  `snapshot_valid_days` int unsigned DEFAULT NULL,
  `remaining_minutes` int unsigned DEFAULT NULL,
  `remaining_credit_krw` bigint unsigned DEFAULT NULL,
  `status` enum('ACTIVE','EXHAUSTED','EXPIRED','REFUNDED','NOT_STARTED') NOT NULL DEFAULT 'ACTIVE',
  `started_at` datetime(6) DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_passes_1` (`purchase_payment_id`),
  KEY `ix_passes_1` (`customer_id`,`status`,`expires_at`),
  KEY `ix_passes_2` (`purchase_branch_id`,`created_at`),
  KEY `ix_passes_revision_product` (`purchase_product_revision_id`,`product_id`),
  KEY `ix_passes_customer_status_created` (`customer_id`,`status`,`created_at`),
  KEY `fk_passes_2` (`product_id`),
  KEY `fk_passes_3` (`purchase_payment_id`,`customer_id`,`purchase_branch_id`,`product_id`),
  CONSTRAINT `fk_passes_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_2` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_3` FOREIGN KEY (`purchase_payment_id`, `customer_id`, `purchase_branch_id`, `product_id`) REFERENCES `payments` (`id`, `customer_id`, `branch_id`, `product_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_4` FOREIGN KEY (`purchase_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_passes_purchase_revision_product` FOREIGN KEY (`purchase_product_revision_id`,`product_id`) REFERENCES `product_revisions` (`id`,`original_product_id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_passes_1` CHECK ((((`kind` = _utf8mb4'TIME') and (`snapshot_minutes` is not null) and (`snapshot_minutes` > 0) and (`remaining_minutes` is not null) and (`snapshot_credit_krw` is null) and (`remaining_credit_krw` is null)) or ((`kind` = _utf8mb4'PERIOD') and (`snapshot_period_days` is not null) and (`snapshot_period_days` > 0) and (`remaining_minutes` is null) and (`remaining_credit_krw` is null)) or ((`kind` = _utf8mb4'STORED_VALUE') and (`snapshot_credit_krw` is not null) and (`snapshot_hourly_rate_krw` is not null) and (`snapshot_credit_krw` > 0) and (`snapshot_hourly_rate_krw` > 0) and (`remaining_credit_krw` is not null) and (`remaining_minutes` is null)))),
  CONSTRAINT `ck_passes_period_activation` CHECK (
    (kind = 'PERIOD' AND (
      (status = 'NOT_STARTED' AND started_at IS NULL AND expires_at IS NULL)
      OR (status = 'REFUNDED' AND ((started_at IS NULL AND expires_at IS NULL)
         OR (started_at IS NOT NULL AND expires_at IS NOT NULL AND expires_at > started_at)))
      OR (status IN ('ACTIVE','EXHAUSTED','EXPIRED')
         AND started_at IS NOT NULL AND expires_at IS NOT NULL AND expires_at > started_at)
    ))
    OR (kind IN ('TIME','STORED_VALUE') AND status <> 'NOT_STARTED' AND started_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='승인 결제 발급권, 구매 당시 상품 조건 스냅샷';

CREATE TABLE `room_reservations` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `room_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `customer_id` bigint unsigned NOT NULL,
  `payment_id` bigint unsigned DEFAULT NULL,
  `start_at` datetime(6) NOT NULL,
  `end_at` datetime(6) NOT NULL,
  `rate_per_30min_snapshot_krw` bigint unsigned NOT NULL,
  `slot_count` int unsigned NOT NULL,
  `amount_krw` bigint unsigned NOT NULL,
  `status` enum('PAYMENT_PENDING','CONFIRMED','PAYMENT_FAILED','IN_USE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PAYMENT_PENDING',
  `cancelled_at` datetime(6) DEFAULT NULL,
  `cancelled_by` bigint unsigned DEFAULT NULL,
  `cancel_reason` varchar(500) DEFAULT NULL,
  `request_key` varchar(120) NOT NULL,
  `created_by` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_reservations_1` (`request_key`),
  UNIQUE KEY `uk_room_reservations_3` (`id`,`room_id`),
  UNIQUE KEY `uk_room_reservations_2` (`payment_id`),
  KEY `ix_room_reservations_1` (`room_id`,`start_at`,`end_at`,`status`),
  KEY `ix_room_reservations_2` (`branch_id`,`start_at`,`status`),
  KEY `ix_room_reservations_3` (`customer_id`,`start_at`),
  KEY `fk_room_reservations_1` (`room_id`,`branch_id`),
  KEY `fk_room_reservations_3` (`payment_id`,`customer_id`,`branch_id`),
  KEY `fk_room_reservations_4` (`created_by`),
  KEY `fk_room_reservations_5` (`cancelled_by`),
  CONSTRAINT `fk_room_reservations_1` FOREIGN KEY (`room_id`, `branch_id`) REFERENCES `rooms` (`id`, `branch_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_2` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_3` FOREIGN KEY (`payment_id`, `customer_id`, `branch_id`) REFERENCES `payments` (`id`, `customer_id`, `branch_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_4` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_reservations_5` FOREIGN KEY (`cancelled_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_room_reservations_1` CHECK ((`end_at` > `start_at`)),
  CONSTRAINT `ck_room_reservations_10` CHECK ((`amount_krw` = (`rate_per_30min_snapshot_krw` * `slot_count`))),
  CONSTRAINT `ck_room_reservations_11` CHECK ((((`status` = _utf8mb4'CANCELLED') and (`cancelled_at` is not null) and (`cancel_reason` is not null)) or ((`status` <> _utf8mb4'CANCELLED') and (`cancelled_at` is null) and (`cancelled_by` is null) and (`cancel_reason` is null)))),
  CONSTRAINT `ck_room_reservations_12` CHECK (((`status` not in (_utf8mb4'CONFIRMED',_utf8mb4'IN_USE',_utf8mb4'COMPLETED')) or (`payment_id` is not null))),
  CONSTRAINT `ck_room_reservations_2` CHECK (((second(`start_at`) = 0) and (microsecond(`start_at`) = 0))),
  CONSTRAINT `ck_room_reservations_3` CHECK ((minute(`start_at`) in (0,30))),
  CONSTRAINT `ck_room_reservations_4` CHECK (((second(`end_at`) = 0) and (microsecond(`end_at`) = 0))),
  CONSTRAINT `ck_room_reservations_5` CHECK ((minute(`end_at`) in (0,30))),
  CONSTRAINT `ck_room_reservations_6` CHECK (((timestampdiff(SECOND,`start_at`,`end_at`) % 1800) = 0)),
  CONSTRAINT `ck_room_reservations_7` CHECK ((timestampdiff(SECOND,`start_at`,`end_at`) = (`slot_count` * 1800))),
  CONSTRAINT `ck_room_reservations_8` CHECK ((`slot_count` > 0)),
  CONSTRAINT `ck_room_reservations_9` CHECK ((`rate_per_30min_snapshot_krw` > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='룸 30분 단위 정수 요금 선결제, start 포함/end 제외';

CREATE TABLE `room_usages` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `reservation_id` bigint unsigned NOT NULL,
  `started_at` datetime(6) NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  `started_by` bigint unsigned DEFAULT NULL,
  `ended_by` bigint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_usages_1` (`reservation_id`),
  KEY `fk_room_usages_2` (`started_by`),
  KEY `fk_room_usages_3` (`ended_by`),
  CONSTRAINT `fk_room_usages_1` FOREIGN KEY (`reservation_id`) REFERENCES `room_reservations` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_room_usages_2` FOREIGN KEY (`started_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_room_usages_3` FOREIGN KEY (`ended_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_room_usages_1` CHECK (((`ended_at` is null) or (`ended_at` >= `started_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='예약된 룸 실제 사용 기록';

CREATE TABLE `room_weekly_hours` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `room_id` bigint unsigned NOT NULL,
  `weekday` tinyint unsigned NOT NULL,
  `is_closed` tinyint(1) NOT NULL DEFAULT '0',
  `opens_minute` smallint unsigned DEFAULT NULL,
  `closes_minute` smallint unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_weekly_hours_1` (`room_id`,`weekday`),
  CONSTRAINT `fk_room_weekly_hours_1` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`) ON DELETE CASCADE,
  CONSTRAINT `ck_room_weekly_hours_1` CHECK ((`weekday` between 1 and 7)),
  CONSTRAINT `ck_room_weekly_hours_2` CHECK ((((`is_closed` = 1) and (`opens_minute` is null) and (`closes_minute` is null)) or ((`is_closed` = 0) and (`opens_minute` is not null) and (`closes_minute` is not null) and (`opens_minute` < 1440) and (`closes_minute` > `opens_minute`) and (`closes_minute` <= 2880))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='월~일 룸 시간; 익일 종료 지원/날짜별 휴무 미지원';

CREATE TABLE `seat_sessions` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `customer_id` bigint unsigned NOT NULL,
  `pass_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `seat_id` bigint unsigned NOT NULL,
  `status` enum('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE',
  `active_seat_id` bigint unsigned GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'ACTIVE') then `seat_id` else NULL end)) STORED,
  `active_customer_id` bigint unsigned GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'ACTIVE') then `customer_id` else NULL end)) STORED,
  `checked_in_at` datetime(6) NOT NULL,
  `effective_checked_in_at` datetime(6) NOT NULL,
  `checked_out_at` datetime(6) DEFAULT NULL,
  `effective_checked_out_at` datetime(6) DEFAULT NULL,
  `billed_minutes` int unsigned NOT NULL DEFAULT '0',
  `billed_krw` bigint unsigned NOT NULL DEFAULT '0',
  `overage_minutes` int unsigned NOT NULL DEFAULT '0',
  `overage_krw` bigint unsigned NOT NULL DEFAULT '0',
  `exception_actor_id` bigint unsigned DEFAULT NULL,
  `exception_reason` varchar(500) DEFAULT NULL,
  `start_request_key` varchar(120) NOT NULL,
  `end_request_key` varchar(120) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_sessions_3` (`start_request_key`),
  UNIQUE KEY `uk_seat_sessions_1` (`active_seat_id`),
  UNIQUE KEY `uk_seat_sessions_2` (`active_customer_id`),
  UNIQUE KEY `uk_seat_sessions_4` (`end_request_key`),
  KEY `ix_seat_sessions_1` (`branch_id`,`status`,`checked_in_at`),
  KEY `ix_seat_sessions_2` (`pass_id`,`checked_in_at`),
  KEY `fk_seat_sessions_1` (`customer_id`),
  KEY `fk_seat_sessions_4` (`seat_id`,`branch_id`),
  KEY `fk_seat_sessions_5` (`exception_actor_id`),
  CONSTRAINT `fk_seat_sessions_1` FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_2` FOREIGN KEY (`pass_id`) REFERENCES `passes` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_3` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_4` FOREIGN KEY (`seat_id`, `branch_id`) REFERENCES `seats` (`id`, `branch_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_sessions_5` FOREIGN KEY (`exception_actor_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_seat_sessions_1` CHECK ((((`status` = _utf8mb4'ACTIVE') and (`checked_out_at` is null) and (`effective_checked_out_at` is null)) or ((`status` = _utf8mb4'CLOSED') and (`checked_out_at` is not null) and (`effective_checked_out_at` is not null)))),
  CONSTRAINT `ck_seat_sessions_2` CHECK (((`effective_checked_out_at` is null) or (`effective_checked_out_at` >= `effective_checked_in_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='일반 좌석 현장 이용 및 실측/정정 시간. 활성 고객/좌석 유일';

CREATE TABLE `shifts` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `starts_at` datetime(6) NOT NULL,
  `ends_at` datetime(6) NOT NULL,
  `planned_break_minutes` smallint unsigned NOT NULL DEFAULT '0',
  `status` enum('SCHEDULED','CANCELLED') NOT NULL DEFAULT 'SCHEDULED',
  `created_by` bigint unsigned NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_shifts_1` (`employee_id`,`starts_at`,`ends_at`),
  KEY `ix_shifts_2` (`branch_id`,`starts_at`,`status`),
  KEY `fk_shifts_1` (`branch_id`,`employee_id`),
  KEY `fk_shifts_2` (`created_by`),
  CONSTRAINT `fk_shifts_1` FOREIGN KEY (`branch_id`, `employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shifts_2` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_shifts_1` CHECK ((`ends_at` > `starts_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='계획 근무; 지점 간 시간 충돌은 잠금으로 검증';

CREATE TABLE `work_tasks` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `branch_id` bigint unsigned NOT NULL,
  `title` varchar(200) NOT NULL,
  `body` text,
  `created_by` bigint unsigned NOT NULL,
  `assignee_employee_id` bigint unsigned NOT NULL,
  `category` varchar(60) NOT NULL,
  `priority` enum('LOW','NORMAL','HIGH','URGENT') NOT NULL DEFAULT 'NORMAL',
  `status` enum('TODO','IN_PROGRESS','DONE') NOT NULL DEFAULT 'TODO',
  `due_at` datetime(6) DEFAULT NULL,
  `completed_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_work_tasks_1` (`branch_id`,`status`,`due_at`),
  KEY `ix_work_tasks_2` (`assignee_employee_id`,`status`),
  KEY `fk_work_tasks_2` (`created_by`),
  KEY `fk_work_tasks_3` (`branch_id`,`assignee_employee_id`),
  CONSTRAINT `fk_work_tasks_1` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_work_tasks_2` FOREIGN KEY (`created_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_work_tasks_3` FOREIGN KEY (`branch_id`, `assignee_employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업무와 담당자·지점, 충돌 감지 version';

CREATE TABLE `attendance_records` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `employee_id` bigint unsigned NOT NULL,
  `branch_id` bigint unsigned NOT NULL,
  `shift_id` bigint unsigned DEFAULT NULL,
  `clock_in_at` datetime(6) NOT NULL,
  `clock_out_at` datetime(6) DEFAULT NULL,
  `status` enum('WORKING','LEFT') NOT NULL DEFAULT 'WORKING',
  `active_employee_id` bigint unsigned GENERATED ALWAYS AS ((case when (`status` = _utf8mb4'WORKING') then `employee_id` else NULL end)) STORED,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attendance_records_1` (`active_employee_id`),
  KEY `ix_attendance_records_1` (`employee_id`,`clock_in_at`),
  KEY `ix_attendance_records_2` (`branch_id`,`clock_in_at`),
  KEY `fk_attendance_records_1` (`branch_id`,`employee_id`),
  KEY `fk_attendance_records_2` (`shift_id`),
  CONSTRAINT `fk_attendance_records_1` FOREIGN KEY (`branch_id`, `employee_id`) REFERENCES `branch_memberships` (`branch_id`, `employee_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_records_2` FOREIGN KEY (`shift_id`) REFERENCES `shifts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_attendance_records_1` CHECK ((((`status` = _utf8mb4'WORKING') and (`clock_out_at` is null)) or ((`status` = _utf8mb4'LEFT') and (`clock_out_at` is not null)))),
  CONSTRAINT `ck_attendance_records_2` CHECK (((`clock_out_at` is null) or (`clock_out_at` >= `clock_in_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='실제 출퇴근은 계획 근무와 독립';

CREATE TABLE `locker_assignment_charges` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `assignment_id` bigint unsigned NOT NULL,
  `payment_id` bigint unsigned NOT NULL,
  `charge_kind` enum('INITIAL','EXTENSION') NOT NULL,
  `initial_assignment_slot` bigint unsigned GENERATED ALWAYS AS ((case when ((`charge_kind` = _utf8mb4'INITIAL') and (`status` in (_utf8mb4'PENDING',_utf8mb4'APPLIED'))) then `assignment_id` else NULL end)) STORED,
  `start_date` date NOT NULL,
  `end_date` date NOT NULL,
  `charged_4week_terms` int unsigned NOT NULL,
  `rate_per_4weeks_snapshot_krw` bigint unsigned NOT NULL,
  `amount_krw` bigint unsigned NOT NULL,
  `status` enum('PENDING','APPLIED','FAILED') NOT NULL DEFAULT 'PENDING',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `applied_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_locker_assignment_charges_1` (`payment_id`),
  UNIQUE KEY `uk_locker_assignment_charges_2` (`initial_assignment_slot`),
  KEY `ix_locker_assignment_charges_1` (`assignment_id`,`created_at`),
  KEY `ix_locker_assignment_charges_2` (`status`,`created_at`),
  CONSTRAINT `fk_locker_assignment_charges_1` FOREIGN KEY (`assignment_id`) REFERENCES `locker_assignments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_locker_assignment_charges_2` FOREIGN KEY (`payment_id`) REFERENCES `payments` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_locker_assignment_charges_1` CHECK ((`end_date` > `start_date`)),
  CONSTRAINT `ck_locker_assignment_charges_2` CHECK (((to_days(`end_date`) - to_days(`start_date`)) = (`charged_4week_terms` * 28))),
  CONSTRAINT `ck_locker_assignment_charges_3` CHECK ((`charged_4week_terms` > 0)),
  CONSTRAINT `ck_locker_assignment_charges_4` CHECK ((`rate_per_4weeks_snapshot_krw` > 0)),
  CONSTRAINT `ck_locker_assignment_charges_5` CHECK ((`amount_krw` = (`charged_4week_terms` * `rate_per_4weeks_snapshot_krw`))),
  CONSTRAINT `ck_locker_assignment_charges_6` CHECK ((((`status` = _utf8mb4'APPLIED') and (`applied_at` is not null)) or ((`status` in (_utf8mb4'PENDING',_utf8mb4'FAILED')) and (`applied_at` is null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='사물함 최초 결제/유료 연장: 4주(28일) 정액 요금과 원거래 보존';

CREATE TABLE `messages` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint unsigned NOT NULL,
  `sender_employee_id` bigint unsigned NOT NULL,
  `parent_message_id` bigint unsigned DEFAULT NULL,
  `related_task_id` bigint unsigned DEFAULT NULL,
  `kind` enum('TEXT','TASK_CARD') NOT NULL DEFAULT 'TEXT',
  `body` text,
  `client_message_key` varchar(100) NOT NULL,
  `sent_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_messages_1` (`conversation_id`,`sender_employee_id`,`client_message_key`),
  UNIQUE KEY `uk_messages_2` (`id`,`conversation_id`),
  KEY `ix_messages_1` (`conversation_id`,`id`),
  KEY `ix_messages_2` (`parent_message_id`,`id`),
  KEY `fk_messages_2` (`parent_message_id`,`conversation_id`),
  KEY `fk_messages_3` (`related_task_id`),
  CONSTRAINT `fk_messages_1` FOREIGN KEY (`conversation_id`, `sender_employee_id`) REFERENCES `conversation_members` (`conversation_id`, `employee_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_messages_2` FOREIGN KEY (`parent_message_id`, `conversation_id`) REFERENCES `messages` (`id`, `conversation_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_messages_3` FOREIGN KEY (`related_task_id`) REFERENCES `work_tasks` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_messages_1` CHECK ((((`kind` = _utf8mb4'TASK_CARD') and (`related_task_id` is not null)) or ((`kind` = _utf8mb4'TEXT') and (`body` is not null))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='순서/재시도 키/스레드/원본 업무 참조';

CREATE TABLE `room_reservation_slots` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `reservation_id` bigint unsigned NOT NULL,
  `room_id` bigint unsigned NOT NULL,
  `slot_start_at` datetime(6) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_reservation_slots_1` (`room_id`,`slot_start_at`),
  KEY `ix_room_reservation_slots_1` (`reservation_id`,`slot_start_at`),
  KEY `fk_room_reservation_slots_1` (`reservation_id`,`room_id`),
  CONSTRAINT `fk_room_reservation_slots_1` FOREIGN KEY (`reservation_id`, `room_id`) REFERENCES `room_reservations` (`id`, `room_id`) ON DELETE CASCADE,
  CONSTRAINT `ck_room_reservation_slots_1` CHECK (((second(`slot_start_at`) = 0) and (microsecond(`slot_start_at`) = 0))),
  CONSTRAINT `ck_room_reservation_slots_2` CHECK ((minute(`slot_start_at`) in (0,30)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='확정 예약의 30분 점유 슬롯. 취소 시 슬롯 삭제, 예약/감사는 유지';

CREATE TABLE `seat_session_corrections` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `seat_session_id` bigint unsigned NOT NULL,
  `actor_employee_id` bigint unsigned NOT NULL,
  `before_start_at` datetime(6) NOT NULL,
  `before_end_at` datetime(6) NOT NULL,
  `after_start_at` datetime(6) NOT NULL,
  `after_end_at` datetime(6) NOT NULL,
  `before_billed_minutes` int unsigned NOT NULL,
  `before_billed_krw` bigint unsigned NOT NULL,
  `after_billed_minutes` int unsigned NOT NULL,
  `after_billed_krw` bigint unsigned NOT NULL,
  `reason` varchar(500) NOT NULL,
  `request_key` varchar(120) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_session_corrections_1` (`request_key`),
  KEY `ix_seat_session_corrections_1` (`seat_session_id`,`created_at`),
  KEY `fk_seat_session_corrections_2` (`actor_employee_id`),
  CONSTRAINT `fk_seat_session_corrections_1` FOREIGN KEY (`seat_session_id`) REFERENCES `seat_sessions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_corrections_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_seat_session_corrections_1` CHECK ((`after_end_at` >= `after_start_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='시간 원본/정정값 및 사용량 차이의 변경 이력';

CREATE TABLE `seat_session_moves` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `seat_session_id` bigint unsigned NOT NULL,
  `from_seat_id` bigint unsigned NOT NULL,
  `to_seat_id` bigint unsigned NOT NULL,
  `actor_employee_id` bigint unsigned DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `request_key` varchar(120) NOT NULL,
  `moved_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seat_session_moves_1` (`request_key`),
  KEY `ix_seat_session_moves_1` (`seat_session_id`,`moved_at`),
  KEY `fk_seat_session_moves_2` (`from_seat_id`),
  KEY `fk_seat_session_moves_3` (`to_seat_id`),
  KEY `fk_seat_session_moves_4` (`actor_employee_id`),
  CONSTRAINT `fk_seat_session_moves_1` FOREIGN KEY (`seat_session_id`) REFERENCES `seat_sessions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_moves_2` FOREIGN KEY (`from_seat_id`) REFERENCES `seats` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_moves_3` FOREIGN KEY (`to_seat_id`) REFERENCES `seats` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_seat_session_moves_4` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_seat_session_moves_1` CHECK ((`from_seat_id` <> `to_seat_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='같은 지점 내 좌석 이동 이력';

CREATE TABLE `shift_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `shift_id` bigint unsigned NOT NULL,
  `actor_employee_id` bigint unsigned NOT NULL,
  `event_type` enum('CREATED','CHANGED','CANCELLED','SWAPPED') NOT NULL,
  `before_json` json DEFAULT NULL,
  `after_json` json DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_shift_events_1` (`shift_id`,`created_at`),
  KEY `fk_shift_events_2` (`actor_employee_id`),
  CONSTRAINT `fk_shift_events_1` FOREIGN KEY (`shift_id`) REFERENCES `shifts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='근무 계획 변경의 원본/후 이력';

CREATE TABLE `shift_swaps` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `original_shift_id` bigint unsigned NOT NULL,
  `requested_by` bigint unsigned NOT NULL,
  `replacement_employee_id` bigint unsigned NOT NULL,
  `proposed_starts_at` datetime(6) NOT NULL,
  `proposed_ends_at` datetime(6) NOT NULL,
  `reason` varchar(500) NOT NULL,
  `status` enum('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
  `decided_by` bigint unsigned DEFAULT NULL,
  `decided_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_shift_swaps_1` (`original_shift_id`,`status`),
  KEY `ix_shift_swaps_2` (`replacement_employee_id`,`proposed_starts_at`),
  KEY `fk_shift_swaps_2` (`requested_by`),
  KEY `fk_shift_swaps_4` (`decided_by`),
  CONSTRAINT `fk_shift_swaps_1` FOREIGN KEY (`original_shift_id`) REFERENCES `shifts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_swaps_2` FOREIGN KEY (`requested_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_swaps_3` FOREIGN KEY (`replacement_employee_id`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_shift_swaps_4` FOREIGN KEY (`decided_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_shift_swaps_1` CHECK ((`proposed_ends_at` > `proposed_starts_at`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='기존 근무 인원/시간 대체 신청, 승인 시 원 근무 업데이트';

CREATE TABLE `task_checklist_items` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `task_id` bigint unsigned NOT NULL,
  `body` varchar(500) NOT NULL,
  `sort_order` int unsigned NOT NULL DEFAULT '0',
  `is_done` tinyint(1) NOT NULL DEFAULT '0',
  `updated_by` bigint unsigned DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_task_checklist_items_1` (`task_id`,`sort_order`),
  KEY `fk_task_checklist_items_2` (`updated_by`),
  CONSTRAINT `fk_task_checklist_items_1` FOREIGN KEY (`task_id`) REFERENCES `work_tasks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_task_checklist_items_2` FOREIGN KEY (`updated_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업무 체크리스트';

CREATE TABLE `task_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `task_id` bigint unsigned NOT NULL,
  `actor_employee_id` bigint unsigned DEFAULT NULL,
  `event_type` varchar(50) NOT NULL,
  `before_json` json DEFAULT NULL,
  `after_json` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_task_events_1` (`task_id`,`created_at`),
  KEY `fk_task_events_2` (`actor_employee_id`),
  CONSTRAINT `fk_task_events_1` FOREIGN KEY (`task_id`) REFERENCES `work_tasks` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_task_events_2` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='업무 변경 이력';

CREATE TABLE `attendance_breaks` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `attendance_id` bigint unsigned NOT NULL,
  `started_at` datetime(6) NOT NULL,
  `ended_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_attendance_breaks_1` (`attendance_id`,`started_at`),
  CONSTRAINT `fk_attendance_breaks_1` FOREIGN KEY (`attendance_id`) REFERENCES `attendance_records` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_attendance_breaks_1` CHECK (((`ended_at` is null) or (`ended_at` >= `started_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='출근 중 휴게 구간';

CREATE TABLE `attendance_corrections` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `attendance_id` bigint unsigned NOT NULL,
  `requested_by` bigint unsigned NOT NULL,
  `correction_kind` enum('CLOCK','BREAK') NOT NULL DEFAULT 'CLOCK',
  `break_id` bigint unsigned DEFAULT NULL,
  `before_clock_in_at` datetime(6) DEFAULT NULL,
  `before_clock_out_at` datetime(6) DEFAULT NULL,
  `after_clock_in_at` datetime(6) DEFAULT NULL,
  `after_clock_out_at` datetime(6) DEFAULT NULL,
  `before_break_start_at` datetime(6) DEFAULT NULL,
  `before_break_end_at` datetime(6) DEFAULT NULL,
  `after_break_start_at` datetime(6) DEFAULT NULL,
  `after_break_end_at` datetime(6) DEFAULT NULL,
  `reason` varchar(500) NOT NULL,
  `status` enum('REQUESTED','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'REQUESTED',
  `decided_by` bigint unsigned DEFAULT NULL,
  `decided_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `version` bigint unsigned NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `ix_attendance_corrections_1` (`attendance_id`,`created_at`),
  KEY `ix_attendance_corrections_2` (`status`,`created_at`),
  KEY `fk_attendance_corrections_2` (`break_id`),
  KEY `fk_attendance_corrections_3` (`requested_by`),
  KEY `fk_attendance_corrections_4` (`decided_by`),
  CONSTRAINT `fk_attendance_corrections_1` FOREIGN KEY (`attendance_id`) REFERENCES `attendance_records` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_corrections_2` FOREIGN KEY (`break_id`) REFERENCES `attendance_breaks` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_corrections_3` FOREIGN KEY (`requested_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_attendance_corrections_4` FOREIGN KEY (`decided_by`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_attendance_corrections_1` CHECK ((((`correction_kind` = _utf8mb4'CLOCK') and (`after_clock_in_at` is not null) and (`break_id` is null)) or ((`correction_kind` = _utf8mb4'BREAK') and (`break_id` is not null) and (`after_break_start_at` is not null)))),
  CONSTRAINT `ck_attendance_corrections_2` CHECK (((`after_clock_out_at` is null) or (`after_clock_out_at` >= `after_clock_in_at`))),
  CONSTRAINT `ck_attendance_corrections_3` CHECK (((`after_break_end_at` is null) or (`after_break_end_at` >= `after_break_start_at`)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='직원 신청, 관리자 승인 시 실제 근태 반영';

CREATE TABLE `pass_balance_events` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `pass_id` bigint unsigned NOT NULL,
  `seat_session_id` bigint unsigned DEFAULT NULL,
  `correction_id` bigint unsigned DEFAULT NULL,
  `kind` enum('USE','ADJUST','RESTORE') NOT NULL,
  `delta_minutes` bigint NOT NULL DEFAULT '0',
  `delta_krw` bigint NOT NULL DEFAULT '0',
  `actor_employee_id` bigint unsigned DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `event_key` varchar(120) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pass_balance_events_1` (`event_key`),
  KEY `ix_pass_balance_events_1` (`pass_id`,`created_at`),
  KEY `ix_pass_balance_events_2` (`seat_session_id`,`kind`),
  KEY `fk_pass_balance_events_3` (`correction_id`),
  KEY `fk_pass_balance_events_4` (`actor_employee_id`),
  CONSTRAINT `fk_pass_balance_events_1` FOREIGN KEY (`pass_id`) REFERENCES `passes` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_pass_balance_events_2` FOREIGN KEY (`seat_session_id`) REFERENCES `seat_sessions` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_pass_balance_events_3` FOREIGN KEY (`correction_id`) REFERENCES `seat_session_corrections` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_pass_balance_events_4` FOREIGN KEY (`actor_employee_id`) REFERENCES `employees` (`id`) ON DELETE SET NULL,
  CONSTRAINT `ck_pass_balance_events_1` CHECK (((`seat_session_id` is null) or (`correction_id` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='발급권 차감·보정의 불변 원장, 세션/정정 단일 참조';

INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (1,'members:write','회원 정보 변경',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (2,'operations:write','운영 설정 및 상품 변경',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (3,'payments:read','결제 내역 조회',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (4,'payments:write','결제 처리',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (5,'sales:read','매출 조회',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (6,'facilities:write','시설 관리',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (7,'notices:write','공지 관리',0);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (8,'people:manage','직원 및 권한 관리',1);
INSERT INTO `permission_definitions` (`id`,`code`,`description`,`is_admin_only`) VALUES (9,'ai:read','AI 분석 조회',0);
INSERT INTO `permission_presets` (`id`,`name`,`role`,`is_system`,`created_by`,`created_at`,`updated_at`,`version`,`updated_by`) VALUES (1,'일반 직원 기본','STAFF',1,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0,NULL);
INSERT INTO `permission_presets` (`id`,`name`,`role`,`is_system`,`created_by`,`created_at`,`updated_at`,`version`,`updated_by`) VALUES (2,'지점 관리자 기본','MANAGER',1,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0,NULL);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,1);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,2);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,3);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,4);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,5);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,6);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,7);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,8);
INSERT INTO `permission_preset_grants` (`preset_id`,`permission_id`) VALUES (2,9);
INSERT INTO `administration_guard` (`id`,`updated_at`) VALUES (1,UTC_TIMESTAMP(6));
