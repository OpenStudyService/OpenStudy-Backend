-- V20 좌석 점유 샘플 뷰를 같은 결과로 다시 만든다. 열·데이터는 V20과 같고, CTE 식에 짧은 별칭만 붙인다.
-- 별칭이 없으면 MySQL이 식 전체를 열 이름으로 붙여, 다시 출력한 뷰 정의(SHOW CREATE VIEW)가 64자 이름 제한을 넘는다.
-- 그러면 전체 구축 SQL(docs/db/schema-full.sql)로 복원할 수 없다.
-- V20을 고치지 않고 새 버전으로 둔 이유: 별칭 없는 V20이 이미 적용된 DB가 있어 체크섬이 맞아야 한다(2026-11-16).

CREATE OR REPLACE VIEW `seat_occupancy_samples` AS
WITH RECURSIVE `sample_days` (`day_kst`) AS (
  SELECT DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 9 HOUR)) - INTERVAL 370 DAY AS `day_kst`
  UNION ALL
  SELECT `day_kst` + INTERVAL 1 DAY AS `day_kst` FROM `sample_days`
  WHERE `day_kst` < DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 9 HOUR))
),
`sample_hours` (`hour_kst`) AS (
  SELECT 9 AS `hour_kst` UNION ALL SELECT 12 AS `hour_kst` UNION ALL SELECT 15 AS `hour_kst`
  UNION ALL SELECT 18 AS `hour_kst` UNION ALL SELECT 21 AS `hour_kst`
),
`samples` AS (
  SELECT
    b.`id` AS `branch_id`,
    d.`day_kst`,
    h.`hour_kst`,
    DATE_SUB(TIMESTAMP(d.`day_kst`, MAKETIME(h.`hour_kst`, 0, 0)), INTERVAL 9 HOUR) AS `sampled_at`
  FROM `branches` b
  CROSS JOIN `sample_days` d
  CROSS JOIN `sample_hours` h
),
`counted` AS (
  SELECT
    s.`branch_id`,
    s.`day_kst`,
    s.`hour_kst`,
    s.`sampled_at`,
    (SELECT COUNT(*) FROM `seat_sessions` ss
      WHERE ss.`branch_id` = s.`branch_id`
        AND ss.`checked_in_at` <= s.`sampled_at`
        AND (ss.`checked_out_at` IS NULL OR ss.`checked_out_at` > s.`sampled_at`)) AS `occupied_seats`,
    (SELECT COUNT(*) FROM `seats` st
      WHERE st.`branch_id` = s.`branch_id` AND st.`status` = 'AVAILABLE') AS `available_seats`
  FROM `samples` s
  WHERE s.`sampled_at` <= UTC_TIMESTAMP()
)
SELECT
  CONCAT(c.`branch_id`, '-', DATE_FORMAT(c.`day_kst`, '%Y%m%d'), '-', LPAD(c.`hour_kst`, 2, '0')) AS `id`,
  c.`branch_id`,
  CAST(c.`sampled_at` AS DATETIME(6)) AS `sampled_at`,
  CAST(WEEKDAY(c.`day_kst`) + 1 AS SIGNED) AS `weekday_kst`,
  CAST(c.`hour_kst` AS SIGNED) AS `hour_kst`,
  c.`occupied_seats`,
  c.`available_seats`,
  CASE WHEN c.`available_seats` > 0
    THEN CAST(c.`occupied_seats` * 100 / c.`available_seats` AS DECIMAL(12, 6))
  END AS `occupancy_pct`
FROM `counted` c;
