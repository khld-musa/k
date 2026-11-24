-- V42__create_essential_indexes.sql
-- ============================================================================
-- Creates the minimal set of indexes needed by actual queries.
-- After V41 dropped ~20 dead/duplicate indexes, this creates only 8 that
-- are actively used by the application's report, export, and ingestion code.
-- ============================================================================

-- 1. Retention purge: ORDER BY triggered_at ASC, id ASC LIMIT N
CREATE INDEX IF NOT EXISTS idx_ussd_tx_triggered_asc
    ON ussd_transactions(triggered_at ASC, id ASC);

-- 2. Node + status filtered exports: WHERE node_ip = ? AND status = ? ORDER BY triggered_at DESC, id DESC
CREATE INDEX IF NOT EXISTS idx_ussd_tx_node_status_triggered_id
    ON ussd_transactions(node_ip, status, triggered_at DESC, id DESC);

-- 3. Functional GIN trigram indexes for LOWER(col) LIKE '%pattern%' searches
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_trgm_lower_short_code
            ON ussd_transactions USING GIN (LOWER(short_code) gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_lower_product_name
            ON ussd_transactions USING GIN (LOWER(product_name) gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_lower_error_code
            ON ussd_transactions USING GIN (LOWER(error_code) gin_trgm_ops);
    END IF;
END $$;

