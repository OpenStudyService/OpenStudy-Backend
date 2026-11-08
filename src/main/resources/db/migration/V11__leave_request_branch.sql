-- Leave request branch (Issue #108, ADR-M12-001 M12-D02, workforce-requests-api.md "스키마 영향").
-- A leave request names the branch where it is decided: the requester's active branch, whose MANAGER approves, rejects
-- and cancels the approval. The balance stays per employee and does not depend on it.
--
-- Existing rows: no API created leave requests before this version, so none is expected. The column is NOT NULL with a
-- foreign key and no default branch is guessed: a table that already holds rows gets 0 in the new column and the same
-- statement fails on the foreign key, leaving the table unchanged (one ALTER, applied atomically by InnoDB).
ALTER TABLE `leave_requests`
  ADD COLUMN `branch_id` bigint unsigned NOT NULL AFTER `employee_id`,
  ADD KEY `ix_leave_requests_branch` (`branch_id`, `starts_on`),
  ADD CONSTRAINT `fk_leave_requests_branch` FOREIGN KEY (`branch_id`) REFERENCES `branches` (`id`) ON DELETE RESTRICT;
