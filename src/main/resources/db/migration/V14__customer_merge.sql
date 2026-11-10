-- Customer merge (Issue #114, ADR-M13-001 M13-D03·D07, operations-extension-api.md "중복 고객 확인·병합").
-- An absorbed customer is never deleted: it becomes MERGED and points at the customer it was merged into. Payments,
-- passes, stays and reservations keep their customer reference; reads of the target include the absorbed customers.
-- merged_into_customer_id always names a customer that is not MERGED itself (a later merge moves earlier absorbed rows).
ALTER TABLE `customers`
  MODIFY COLUMN `status` enum('NORMAL','RESTRICTED','INACTIVE','MERGED') NOT NULL DEFAULT 'NORMAL',
  ADD COLUMN `merged_into_customer_id` bigint unsigned DEFAULT NULL AFTER `status_reason`,
  ADD KEY `ix_customers_merged_into` (`merged_into_customer_id`),
  ADD CONSTRAINT `fk_customers_merged_into` FOREIGN KEY (`merged_into_customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  ADD CONSTRAINT `ck_customers_merged` CHECK (((`status` = _utf8mb4'MERGED') = (`merged_into_customer_id` is not null)));
-- A customer merged into itself is refused by the service (422 CUSTOMER_MERGE_SELF); MySQL cannot check the AUTO_INCREMENT id.

-- One row per absorbed customer and merge. via_customer_id names the customer it had been merged into before, when a
-- later merge moved it to a new target. Rows are never updated or deleted (M13-D03: a merge is not undone).
CREATE TABLE `customer_merges` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `target_customer_id` bigint unsigned NOT NULL,
  `absorbed_customer_id` bigint unsigned NOT NULL,
  `via_customer_id` bigint unsigned DEFAULT NULL,
  `merged_by` bigint unsigned NOT NULL,
  `merged_at` datetime(6) NOT NULL,
  `reason` varchar(500) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_customer_merges_target` (`target_customer_id`, `merged_at`),
  KEY `ix_customer_merges_absorbed` (`absorbed_customer_id`, `merged_at`),
  KEY `fk_customer_merges_via` (`via_customer_id`),
  KEY `fk_customer_merges_by` (`merged_by`),
  CONSTRAINT `fk_customer_merges_target` FOREIGN KEY (`target_customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_merges_absorbed` FOREIGN KEY (`absorbed_customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_merges_via` FOREIGN KEY (`via_customer_id`) REFERENCES `customers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_customer_merges_by` FOREIGN KEY (`merged_by`) REFERENCES `employees` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `ck_customer_merges_1` CHECK ((`target_customer_id` <> `absorbed_customer_id`))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='고객 병합 이력, 되돌리지 않음';
