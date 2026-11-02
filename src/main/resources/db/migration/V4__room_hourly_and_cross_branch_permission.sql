-- Hourly room reservations and the cross-branch customer summary permission (Issue #68, ADR-M6-001 M6-D01 and M6-D06).
-- V1 stored rooms as 30-minute slots with a per-30-minute rate. This version moves them to whole hours that start on
-- the hour, with the rate per hour, and adds the grantable permission customers:cross-branch-read.
--
-- Existing rows (a production database may already hold some even though no API creates them yet):
--   * rooms: the per-30-minute rate becomes the per-hour rate (x 2). updated_at and version are kept.
--   * room_reservations: the first statement adds the on-the-hour and whole-hour checks while every other part of the
--     table is still V3, so a row that is not hour-aligned (starts or ends at :30, or lasts 30 or 90 minutes) makes
--     this migration fail with error 3819 before anything is changed. Nothing is rounded or rewritten silently.
--     Aligned rows keep their amount: the rate snapshot doubles and slot_count (30-minute slots) halves into hours.
--   * room_reservation_slots: the half-hour slots of aligned reservations are removed. Each reservation keeps one slot
--     per hour, starting on the hour, so the unique key (room_id, slot_start_at) keeps blocking double booking.
-- A failed run can be repaired and re-run because the guard is the only change made before the data is converted.

-- 1. Guard: fails atomically if any reservation is not aligned to whole hours.
ALTER TABLE `room_reservations`
  ADD CONSTRAINT `ck_room_reservations_13` CHECK ((minute(`start_at`) = 0)),
  ADD CONSTRAINT `ck_room_reservations_14` CHECK ((minute(`end_at`) = 0)),
  ADD CONSTRAINT `ck_room_reservations_15` CHECK (((timestampdiff(SECOND,`start_at`,`end_at`) % 3600) = 0));

-- 2. rooms: rate per hour.
ALTER TABLE `rooms` DROP CHECK `ck_rooms_2`;
ALTER TABLE `rooms`
  CHANGE COLUMN `rate_per_30min_krw` `rate_per_hour_krw` bigint unsigned NOT NULL,
  COMMENT = '스터디룸/정원/시간당 요금';
UPDATE `rooms` SET `rate_per_hour_krw` = `rate_per_hour_krw` * 2, `updated_at` = `updated_at`;
ALTER TABLE `rooms` ADD CONSTRAINT `ck_rooms_2` CHECK ((`rate_per_hour_krw` > 0));

-- 3. room_reservations: hourly rate snapshot and hours.
ALTER TABLE `room_reservations`
  DROP CHECK `ck_room_reservations_3`,
  DROP CHECK `ck_room_reservations_5`,
  DROP CHECK `ck_room_reservations_6`,
  DROP CHECK `ck_room_reservations_7`,
  DROP CHECK `ck_room_reservations_8`,
  DROP CHECK `ck_room_reservations_9`,
  DROP CHECK `ck_room_reservations_10`;
ALTER TABLE `room_reservations`
  CHANGE COLUMN `rate_per_30min_snapshot_krw` `rate_per_hour_snapshot_krw` bigint unsigned NOT NULL,
  CHANGE COLUMN `slot_count` `hours` int unsigned NOT NULL,
  COMMENT = '룸 1시간 단위 정수 요금 선결제, start 포함/end 제외';
UPDATE `room_reservations`
   SET `rate_per_hour_snapshot_krw` = `rate_per_hour_snapshot_krw` * 2,
       `hours` = `hours` DIV 2,
       `updated_at` = `updated_at`;
ALTER TABLE `room_reservations`
  ADD CONSTRAINT `ck_room_reservations_7` CHECK ((timestampdiff(SECOND,`start_at`,`end_at`) = (`hours` * 3600))),
  ADD CONSTRAINT `ck_room_reservations_8` CHECK ((`hours` > 0)),
  ADD CONSTRAINT `ck_room_reservations_9` CHECK ((`rate_per_hour_snapshot_krw` > 0)),
  ADD CONSTRAINT `ck_room_reservations_10` CHECK ((`amount_krw` = (`rate_per_hour_snapshot_krw` * `hours`)));

-- 4. room_reservation_slots: one slot per hour, starting on the hour.
DELETE FROM `room_reservation_slots` WHERE minute(`slot_start_at`) = 30;
ALTER TABLE `room_reservation_slots` DROP CHECK `ck_room_reservation_slots_2`;
ALTER TABLE `room_reservation_slots`
  ADD CONSTRAINT `ck_room_reservation_slots_2` CHECK ((minute(`slot_start_at`) = 0)),
  COMMENT = '확정 예약의 1시간 점유 슬롯. 취소 시 슬롯 삭제, 예약/감사는 유지';

-- 5. Permission: grantable to MANAGER and STAFF, not in any default preset (the administrator grants it per employee).
INSERT INTO `permission_definitions` (`code`,`description`,`is_admin_only`) VALUES ('customers:cross-branch-read','타 지점 고객 최소 요약 조회',0);
