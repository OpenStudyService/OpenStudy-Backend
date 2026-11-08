-- Index for the M9 sales aggregations (Issue #89, NFR-PERF-04, ADR-M9-001).
-- Every approval and payment-date refund aggregation filters payments by `branch_id IN (...)` and an `approved_at`
-- range. ix_payments_1 (branch_id, status, approved_at) cannot range on approved_at because status is not in the
-- condition, so those queries read the whole table. This key lets them range on both columns; a payment-date refund
-- then reaches refunds through uk_refunds_1 instead of scanning refunds.
-- Measured before and after in docs/db/sales-aggregation-query-plans.md. No other index is changed.
ALTER TABLE `payments` ADD KEY `ix_payments_branch_approved` (`branch_id`, `approved_at`);
