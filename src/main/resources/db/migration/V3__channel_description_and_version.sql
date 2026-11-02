-- Channel settings (Issue #76, FR-CHAT-09, ADR-M7-001 change history of 2026-11-01). V1 conversations has no column for
-- a channel's description or for the version that its name, description and member list share. Existing rows keep a
-- NULL description and start at version 0, the same as any other versioned table.

ALTER TABLE `conversations`
  ADD COLUMN `description` varchar(500) DEFAULT NULL AFTER `name`,
  ADD COLUMN `version` bigint unsigned NOT NULL DEFAULT 0 AFTER `created_at`;
