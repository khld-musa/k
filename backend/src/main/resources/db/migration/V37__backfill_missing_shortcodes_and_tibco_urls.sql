-- V37__backfill_missing_shortcodes_and_tibco_urls.sql
-- Note: Shortcode and URL fallback logic is dynamically handled in TibcoErrorReportExcelService
-- and UssdLogLineParser. Batch backfill is not run synchronously on startup to prevent blocking boot on large production databases.
SELECT 1;

