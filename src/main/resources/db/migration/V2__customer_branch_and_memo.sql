-- M4 customer branch scope and service memo (Issue #55, ADR-M4-002 M4-D05/D06).
-- registered_branch_id stays NULL-able: V1 customers have no recorded registration branch.
-- Existing rows take the branch of their first payment; customers without payments stay NULL
-- (visible only to OWNER until a later decision assigns them). New registrations always set it (service rule).

ALTER TABLE `customers`
  ADD COLUMN `registered_branch_id` bigint unsigned DEFAULT NULL AFTER `email`,
  ADD COLUMN `memo` varchar(1000) DEFAULT NULL AFTER `status_reason`,
  ADD KEY `ix_customers_registered_branch` (`registered_branch_id`),
  ADD CONSTRAINT `fk_customers_registered_branch` FOREIGN KEY (`registered_branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT;

-- Backfill is not a business change: keep updated_at and version as they were.
UPDATE `customers` c
  JOIN (
    SELECT `customer_id`, `branch_id`,
           ROW_NUMBER() OVER (PARTITION BY `customer_id` ORDER BY `created_at`, `id`) AS `payment_order`
      FROM `payments`
  ) first_payment ON first_payment.`customer_id` = c.`id` AND first_payment.`payment_order` = 1
   SET c.`registered_branch_id` = first_payment.`branch_id`,
       c.`updated_at` = c.`updated_at`
 WHERE c.`registered_branch_id` IS NULL;
