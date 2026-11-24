-- V39__add_summary_consolidated_query.sql
-- Adds the SQL function used by the "single-pass summary" endpoint.
-- All 6 aggregates (total, success, avg_latency, daily, node, top-error-codes)
-- that previously fired as 8 separate queries are now computed in 1 pass each via
-- native SQL called from ReportService.getSummaryConsolidated().
-- This migration is a documentation/comments-only no-op; the actual queries live in
-- UssdTransactionRepository native @Query annotations and need no DDL changes.
SELECT 1;
