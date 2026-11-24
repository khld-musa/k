-- V29__add_report_search_indexes.sql
-- Composite index for the enhanced report search query that filters on
-- triggered_at + multiple optional columns. This covering index lets
-- PostgreSQL answer the most common report queries with a single index scan.

CREATE INDEX IF NOT EXISTS idx_ussd_tx_error_code ON ussd_transactions(error_code);

-- Composite index: the main search query filters on date range first, then
-- optional columns. This supports the most selective filter patterns.
CREATE INDEX IF NOT EXISTS idx_ussd_tx_search_composite
    ON ussd_transactions(triggered_at, msisdn, short_code, status, error_code);
