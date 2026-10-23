-- Foundation contract 0.1.2. V1 checksum stays unchanged.
-- Existing products/invitations: stop Flyway at target=2 and review the mapping
-- in docs/db/foundation-upgrade.md before enabling V3. No inferred branch scope.
CREATE TABLE foundation_legacy_review (
  entity_type ENUM('PRODUCT','INVITATION') NOT NULL,
  entity_id BIGINT UNSIGNED NOT NULL,
  confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (entity_type,entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO foundation_legacy_review(entity_type,entity_id) SELECT 'PRODUCT',id FROM products;
INSERT INTO foundation_legacy_review(entity_type,entity_id) SELECT 'INVITATION',id FROM employee_invitations;

ALTER TABLE branches
  ADD created_by BIGINT UNSIGNED NULL, ADD updated_by BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_branches_created_by FOREIGN KEY(created_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_branches_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT;
ALTER TABLE employees
  ADD created_by BIGINT UNSIGNED NULL, ADD updated_by BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_employees_created_by FOREIGN KEY(created_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_employees_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT;
ALTER TABLE permission_presets
  DROP FOREIGN KEY fk_permission_presets_1,
  ADD updated_by BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_permission_presets_created_by FOREIGN KEY(created_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_permission_presets_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT;
ALTER TABLE branch_memberships
  DROP FOREIGN KEY fk_branch_memberships_3,
  ADD created_by BIGINT UNSIGNED NULL, ADD updated_by BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_memberships_approved_by FOREIGN KEY(approved_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_memberships_created_by FOREIGN KEY(created_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_memberships_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT;
ALTER TABLE employee_invitations
  DROP FOREIGN KEY fk_employee_invitations_4, DROP FOREIGN KEY fk_employee_invitations_5,
  ADD invitee_name VARCHAR(100) NULL,
  ADD rejected_by BIGINT UNSIGNED NULL, ADD rejected_at DATETIME(6) NULL,
  ADD rejection_reason VARCHAR(500) NULL,
  ADD cancelled_by BIGINT UNSIGNED NULL, ADD cancelled_at DATETIME(6) NULL,
  ADD updated_by BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_invitations_accepted_by FOREIGN KEY(accepted_employee_id) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_invitations_approved_by FOREIGN KEY(approved_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_invitations_rejected_by FOREIGN KEY(rejected_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_invitations_cancelled_by FOREIGN KEY(cancelled_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_invitations_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD KEY ix_invitations_branch_status(branch_id,status,id);
-- Only a verifiable accepted account supplies a historical name; no placeholder.
UPDATE employee_invitations i JOIN employees e ON e.id=i.accepted_employee_id
  SET i.invitee_name=e.name, i.updated_at=i.updated_at;
CREATE TABLE invitation_permission_grants (
  invitation_id BIGINT UNSIGNED NOT NULL, permission_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(invitation_id,permission_id),
  CONSTRAINT fk_invitation_grants_invitation FOREIGN KEY(invitation_id) REFERENCES employee_invitations(id) ON DELETE RESTRICT,
  CONSTRAINT fk_invitation_grants_permission FOREIGN KEY(permission_id) REFERENCES permission_definitions(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE invitation_send_jobs
  ADD locked_until DATETIME(6) NULL, ADD worker_id VARCHAR(100) NULL,
  ADD KEY ix_send_jobs_lease(status,locked_until,id);

CREATE TABLE product_categories (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  code VARCHAR(40) NOT NULL, name VARCHAR(100) NOT NULL,
  kind ENUM('TIME','PERIOD','STORED_VALUE') NOT NULL,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  created_by BIGINT UNSIGNED NULL, updated_by BIGINT UNSIGNED NULL,
  version BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY(id), UNIQUE KEY uk_product_categories_code(code), KEY ix_categories_kind_status(kind,status,id),
  CONSTRAINT fk_categories_created_by FOREIGN KEY(created_by) REFERENCES employees(id) ON DELETE RESTRICT,
  CONSTRAINT fk_categories_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE products
  ADD category_id BIGINT UNSIGNED NULL,
  ADD created_by BIGINT UNSIGNED NULL, ADD updated_by BIGINT UNSIGNED NULL,
  ADD KEY ix_products_category(category_id,id),
  ADD CONSTRAINT fk_products_category FOREIGN KEY(category_id) REFERENCES product_categories(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_products_created_by FOREIGN KEY(created_by) REFERENCES employees(id) ON DELETE RESTRICT,
  ADD CONSTRAINT fk_products_updated_by FOREIGN KEY(updated_by) REFERENCES employees(id) ON DELETE RESTRICT;
CREATE TABLE product_branches (
  product_id BIGINT UNSIGNED NOT NULL, branch_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY(product_id,branch_id), KEY ix_product_branches_reverse(branch_id,product_id),
  CONSTRAINT fk_product_branches_product FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE RESTRICT,
  CONSTRAINT fk_product_branches_branch FOREIGN KEY(branch_id) REFERENCES branches(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE product_revisions (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  product_id BIGINT UNSIGNED NULL, original_product_id BIGINT UNSIGNED NOT NULL,
  product_version BIGINT UNSIGNED NOT NULL,
  event_type ENUM('CREATED','UPDATED','DELETED','BASELINE') NOT NULL,
  changed_by BIGINT UNSIGNED NULL, changed_at DATETIME(6) NOT NULL,
  request_id VARCHAR(128) NOT NULL, reason VARCHAR(500) NULL, snapshot_json JSON NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_product_revisions_version(original_product_id,product_version),
  KEY ix_product_revisions_history(original_product_id,changed_at,id),
  CONSTRAINT fk_product_revisions_live FOREIGN KEY(product_id) REFERENCES products(id) ON DELETE SET NULL,
  CONSTRAINT fk_product_revisions_actor FOREIGN KEY(changed_by) REFERENCES employees(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE audit_events
  MODIFY entity_id BIGINT UNSIGNED NULL,
  ADD request_id VARCHAR(128) NULL,
  ADD result ENUM('SUCCESS','DENIED','FAILED') NOT NULL DEFAULT 'SUCCESS',
  ADD KEY ix_audit_request(request_id);
CREATE TABLE administration_guard (
  id TINYINT UNSIGNED NOT NULL, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(id), CONSTRAINT ck_administration_guard_singleton CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO administration_guard(id) VALUES(1);
CREATE TABLE idempotency_records (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, actor_employee_id BIGINT UNSIGNED NOT NULL,
  operation VARCHAR(120) NOT NULL, scope_key VARCHAR(160) NOT NULL,
  idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  request_hash BINARY(32) NOT NULL, state ENUM('PROCESSING','COMPLETED') NOT NULL,
  http_status SMALLINT UNSIGNED NULL, response_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), expires_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id), UNIQUE KEY uk_idempotency_request(actor_employee_id,operation,scope_key,idempotency_key),
  KEY ix_idempotency_expiry(expires_at,id),
  CONSTRAINT fk_idempotency_actor FOREIGN KEY(actor_employee_id) REFERENCES employees(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE workspace_event_outbox (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, branch_id BIGINT UNSIGNED NULL,
  target_employee_id BIGINT UNSIGNED NULL, entity_type VARCHAR(80) NOT NULL, entity_id BIGINT UNSIGNED NULL,
  event_type ENUM('INVALIDATE','AUTHORIZATION_CHANGED') NOT NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), published_at DATETIME(6) NULL,
  attempts INT UNSIGNED NOT NULL DEFAULT 0, next_attempt_at DATETIME(6) NULL,
  locked_until DATETIME(6) NULL, worker_id VARCHAR(100) NULL,
  PRIMARY KEY(id), KEY ix_workspace_events_delivery(published_at,next_attempt_at,id),
  KEY ix_workspace_events_target(target_employee_id,id),
  CONSTRAINT fk_workspace_events_branch FOREIGN KEY(branch_id) REFERENCES branches(id) ON DELETE RESTRICT,
  CONSTRAINT fk_workspace_events_employee FOREIGN KEY(target_employee_id) REFERENCES employees(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
