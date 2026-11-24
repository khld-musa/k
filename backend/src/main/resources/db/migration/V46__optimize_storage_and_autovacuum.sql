-- V46__optimize_storage_and_autovacuum.sql
-- ============================================================================
-- Fast DDL-only migration (<100ms) to prevent deployment timeouts:
-- 1. Drops redundant composite indexes that duplicate existing keyset/trigram indexes.
-- 2. Tunes per-table autovacuum for high-throughput ussd_transactions and rollups.
-- NO table scans or row rewrites: safe for instant Flyway execution on 50M+ rows.
-- ============================================================================

-- ── 1. Drop duplicate composite indexes (instant DDL) ───────────────────────

-- idx_ussd_tx_status_triggered (V36) — subsumed by idx_ussd_tx_status_triggered_id (V40)
DROP INDEX IF EXISTS idx_ussd_tx_status_triggered;

-- idx_ussd_tx_errcode_triggered (V36) — superseded by GIN trgm on LOWER(error_code)
DROP INDEX IF EXISTS idx_ussd_tx_errcode_triggered;

-- idx_ussd_tx_shortcode_triggered (V36) — superseded by GIN trgm on LOWER(short_code)
DROP INDEX IF EXISTS idx_ussd_tx_shortcode_triggered;

-- ── 2. Tune autovacuum for ussd_transactions ────────────────────────────────
-- Lower scale_factor to 1% so PostgreSQL continuously vacuums dead tuples
-- rather than waiting for 20% table bloat (which causes 47GB+ disk exhaustion).
ALTER TABLE ussd_transactions SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_vacuum_threshold = 5000,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_analyze_threshold = 5000,
    autovacuum_vacuum_cost_delay = 10
);

-- ── 3. Tune autovacuum for the hourly stats rollup table ────────────────────
ALTER TABLE ussd_stats_hourly SET (
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_vacuum_threshold = 100
);
