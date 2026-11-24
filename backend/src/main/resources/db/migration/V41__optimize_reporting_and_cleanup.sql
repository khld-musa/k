-- V41__optimize_reporting_and_cleanup.sql
-- ============================================================================
-- SAFE MIGRATION: Only DROP INDEX statements (instant DDL, no table scan).
-- This eliminates ~20 dead/duplicate/bloated indexes that waste storage,
-- slow down every INSERT, and contributed to the 47GB table bloat.
-- ============================================================================

-- ── 1. Drop 4 bloated GIN trigram indexes on unbounded TEXT columns (V38) ────
-- These indexed multi-kilobyte XML/HTTP bodies generating tens of GB of bloat.
-- V41 LOWER() functional GIN indexes on short_code/product_name/error_code replace them.
DROP INDEX IF EXISTS idx_trgm_error_message;
DROP INDEX IF EXISTS idx_trgm_tibco_response;
DROP INDEX IF EXISTS idx_trgm_error_reason;
DROP INDEX IF EXISTS idx_trgm_subscriber_reply;

-- ── 2. Drop 4 non-LOWER() GIN trigrams (V38) — superseded by LOWER() versions ──
-- Queries use LOWER(col) LIKE, so raw-column GIN indexes are bypassed.
DROP INDEX IF EXISTS idx_trgm_short_code;
DROP INDEX IF EXISTS idx_trgm_product_name;
DROP INDEX IF EXISTS idx_trgm_error_code;
DROP INDEX IF EXISTS idx_trgm_msisdn;

-- ── 3. Drop single-column indexes subsumed by composites ────────────────────
-- idx_ussd_transactions_triggered_at → subsumed by idx_ussd_tx_triggered_id (V40)
DROP INDEX IF EXISTS idx_ussd_transactions_triggered_at;
-- idx_ussd_transactions_status → subsumed by idx_ussd_tx_status_triggered (V36)
DROP INDEX IF EXISTS idx_ussd_transactions_status;
-- idx_ussd_transactions_node_ip → subsumed by idx_ussd_tx_node_status_triggered_id (V41/V42)
DROP INDEX IF EXISTS idx_ussd_transactions_node_ip;
-- idx_ussd_transactions_msisdn → never used standalone (always inside date-range queries)
DROP INDEX IF EXISTS idx_ussd_transactions_msisdn;
-- idx_ussd_tx_error_code (V29) → subsumed by idx_ussd_tx_errcode_triggered (V36)
DROP INDEX IF EXISTS idx_ussd_tx_error_code;
-- idx_ussd_tx_short_code (V16) → subsumed by idx_ussd_tx_shortcode_triggered (V36)
DROP INDEX IF EXISTS idx_ussd_tx_short_code;

-- ── 4. Drop duplicate composite indexes ─────────────────────────────────────
-- idx_ussd_tx_triggered_status (V16) → duplicate of V36 version, both subsumed by triggered_id
DROP INDEX IF EXISTS idx_ussd_tx_triggered_status;
-- idx_ussd_tx_triggered_node (V16) → subsumed by idx_ussd_tx_node_status_triggered_id
DROP INDEX IF EXISTS idx_ussd_tx_triggered_node;
-- idx_ussd_tx_triggered_product (V16) → product_name filtered via LOWER() LIKE; B-tree useless
DROP INDEX IF EXISTS idx_ussd_tx_triggered_product;
-- idx_ussd_tx_node_triggered (V36) → subsumed by idx_ussd_tx_node_status_triggered_id
DROP INDEX IF EXISTS idx_ussd_tx_node_triggered;
-- idx_ussd_tx_search_composite (V29) → 5-col composite never matches query patterns
DROP INDEX IF EXISTS idx_ussd_tx_search_composite;

-- ── 5. Drop dead indexes on columns no query ever filters ───────────────────
DROP INDEX IF EXISTS idx_ussd_tx_service_type;   -- no query uses service_type
DROP INDEX IF EXISTS idx_ussd_tx_lang;           -- no query uses lang
DROP INDEX IF EXISTS idx_ussd_tx_cell_id;        -- no query uses cell_id
DROP INDEX IF EXISTS idx_ussd_tx_vlr;            -- no query uses vlr
DROP INDEX IF EXISTS idx_ussd_tx_latency;        -- AVG() scans entire range; index useless
DROP INDEX IF EXISTS idx_ussd_tx_channel;        -- no query uses channel
DROP INDEX IF EXISTS idx_ussd_tx_remote_url;     -- no query filters remote_url
DROP INDEX IF EXISTS idx_ussd_tx_response_time;  -- no query filters response_time
DROP INDEX IF EXISTS idx_ussd_tx_error_reason;   -- only in OR chain with LOWER() LIKE; useless
DROP INDEX IF EXISTS idx_ussd_tx_product_name_btree; -- DISTINCT uses date filter; GIN covers LIKE

