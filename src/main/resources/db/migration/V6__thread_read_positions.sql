-- Independent read positions for the main feed and each thread (Issue #155, FR-CHAT-06, ADR-M7-001 M7-D08·D09).
-- conversation_members.last_read_message_id now covers originals only. A reply is read up to the larger of the
-- member's baseline and the thread's own row. A thread has a row only once its reader moved past the baseline.
--
-- The one channel-wide position used to cover replies too, so it becomes the baseline: nothing that counted as read
-- turns unread on deployment (M7-D08, decided by the user on 2026-11-03). NULL stays NULL, everything unread, as it was.
-- A position may point at a reply; only its order matters, so it needs no conversion.

ALTER TABLE `conversation_members`
  ADD COLUMN `thread_read_baseline_message_id` bigint unsigned DEFAULT NULL AFTER `last_read_at`;

UPDATE `conversation_members` SET `thread_read_baseline_message_id` = `last_read_message_id`;

CREATE TABLE `conversation_thread_reads` (
  `conversation_id` bigint unsigned NOT NULL,
  `employee_id` bigint unsigned NOT NULL,
  `parent_message_id` bigint unsigned NOT NULL,
  `last_read_message_id` bigint unsigned NOT NULL,
  `last_read_at` datetime(6) NOT NULL,
  PRIMARY KEY (`conversation_id`,`employee_id`,`parent_message_id`),
  KEY `fk_conversation_thread_reads_2` (`parent_message_id`,`conversation_id`),
  KEY `fk_conversation_thread_reads_3` (`last_read_message_id`,`conversation_id`),
  CONSTRAINT `fk_conversation_thread_reads_1` FOREIGN KEY (`conversation_id`, `employee_id`) REFERENCES `conversation_members` (`conversation_id`, `employee_id`) ON DELETE CASCADE,
  CONSTRAINT `fk_conversation_thread_reads_2` FOREIGN KEY (`parent_message_id`, `conversation_id`) REFERENCES `messages` (`id`, `conversation_id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversation_thread_reads_3` FOREIGN KEY (`last_read_message_id`, `conversation_id`) REFERENCES `messages` (`id`, `conversation_id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='스레드별 직원 읽음 위치. 기준 위치를 넘어선 스레드만 행이 있다';
