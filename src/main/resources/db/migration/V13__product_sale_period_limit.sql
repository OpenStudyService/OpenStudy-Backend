-- Product sale period and limit (Issue #113, ADR-M13-001 M13-D02·D06, operations-extension-api.md "상품 판매 기간·한도").
-- All three are optional: an empty value does not restrict, so existing products keep selling as before.
-- The period is [sales_starts_at, sales_ends_at). The limit counts the product's approved (not refunded) payments over
-- every sale branch; it is checked by the sale under the product lock and is never stored as a counter.
-- The revision history keeps the three values in snapshot_json, so product_revisions needs no column.
ALTER TABLE `products`
  ADD COLUMN `sales_starts_at` datetime(6) DEFAULT NULL AFTER `sale_status`,
  ADD COLUMN `sales_ends_at` datetime(6) DEFAULT NULL AFTER `sales_starts_at`,
  ADD COLUMN `sales_limit` int unsigned DEFAULT NULL AFTER `sales_ends_at`,
  ADD CONSTRAINT `ck_products_sales_period` CHECK (((`sales_starts_at` is null) or (`sales_ends_at` is null) or (`sales_starts_at` < `sales_ends_at`))),
  ADD CONSTRAINT `ck_products_sales_limit` CHECK (((`sales_limit` is null) or (`sales_limit` between 1 and 1000000)));
