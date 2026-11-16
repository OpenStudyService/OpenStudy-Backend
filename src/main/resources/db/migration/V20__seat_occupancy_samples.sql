-- 좌석 점유 샘플: AI 생성 경로가 요일·시간대 좌석 혼잡도를 일반 집계(QuerySpec)로 조회하는 원천.
-- 지점마다 Asia/Seoul 기준 최근 371일의 9·12·15·18·21시에 이용 중이던 좌석 수와 현재 가용 좌석 수를 한 행으로 만든다.
-- 등록 분석 POC-002(요일·시간대 혼잡도)와 같은 정의다: 입실 시각 <= 샘플 시각 < 퇴실 시각(미퇴실 포함)이면 이용 중.
-- 저장 테이블이 아니라 뷰이므로 입퇴실 기록이 바뀌면 바로 반영된다. 아직 오지 않은 샘플 시각은 넣지 않는다.

CREATE VIEW `seat_occupancy_samples` AS
WITH RECURSIVE `sample_days` (`day_kst`) AS (
  SELECT DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 9 HOUR)) - INTERVAL 370 DAY
  UNION ALL
  SELECT `day_kst` + INTERVAL 1 DAY FROM `sample_days`
  WHERE `day_kst` < DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 9 HOUR))
),
`sample_hours` (`hour_kst`) AS (
  SELECT 9 UNION ALL SELECT 12 UNION ALL SELECT 15 UNION ALL SELECT 18 UNION ALL SELECT 21
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
