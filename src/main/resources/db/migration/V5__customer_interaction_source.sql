-- Where a customer_service_interactions row came from (Issue #72, ADR-M5-001 M5-D07 and ADR-M6-001 M6-D06).
-- The table is the access trail of both the check-in exact lookup and the cross-branch summary, but only the lookup
-- shows the customer's identity, so only its rows may prove that an employee may name that customer (M7-D02).
--
-- Existing rows: all of them were written by the check-in exact lookup, the only writer before this version, so the
-- default is correct for them and nothing is rewritten.
ALTER TABLE `customer_service_interactions`
  ADD COLUMN `source` enum('CHECK_IN_LOOKUP','CROSS_BRANCH_SUMMARY') NOT NULL DEFAULT 'CHECK_IN_LOOKUP' AFTER `reason`;
