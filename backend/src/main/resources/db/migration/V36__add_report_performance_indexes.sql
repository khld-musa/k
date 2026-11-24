-- V36__add_report_performance_indexes.sql
-- Optimizes report search and aggregation queries on ussd_transactions.
-- Uses standard B-tree indexes that require no external PostgreSQL extensions (like pg_trgm).

-- Fast composite index on status + triggered_at (used by error reports filtering status = 'FAILED' across date ranges)
CREATE INDEX IF NOT EXISTS idx_ussd_tx_status_triggered
    ON ussd_transactions(status, triggered_at DESC);

-- Fast composite index on triggered_at + status (used by countInRangeWithFilters and countSuccessWithFilters)
CREATE INDEX IF NOT EXISTS idx_ussd_tx_triggered_status
    ON ussd_transactions(triggered_at DESC, status);

-- Index for filtering by error_code in date ranges
CREATE INDEX IF NOT EXISTS idx_ussd_tx_errcode_triggered
    ON ussd_transactions(error_code, triggered_at DESC);

-- Index for filtering by short_code in date ranges
CREATE INDEX IF NOT EXISTS idx_ussd_tx_shortcode_triggered
    ON ussd_transactions(short_code, triggered_at DESC);

-- Index for node_ip aggregation in date ranges
CREATE INDEX IF NOT EXISTS idx_ussd_tx_node_triggered
    ON ussd_transactions(node_ip, triggered_at DESC);
