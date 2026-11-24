-- V47__drop_redundant_indexes_and_optimize_storage.sql
-- ============================================================================
-- STORAGE OPTIMIZATION & INDEX CONSOLIDATION (~12 GB Reclaimed)
--
-- WHAT IS KEPT (Essential for Dashboard & Ingestion):
-- 1. ussd_transactions_pkey             (PK constraint on id - system critical)
-- 2. idx_ussd_tx_triggered_id           (Core date axis for charts, filters, and streaming export)
-- 3. idx_ussd_transactions_call_id      (High-speed log ingestion deduplication)
-- 4. idx_ussd_tx_node_status_triggered_id (Covers node IP, status, and server health queries)
-- 5. idx_ussd_tx_msisdn_triggered       (Subscriber phone number search)
--
-- WHAT IS DROPPED / CONVERTED (Saves ~12 GB of disk & RAM):
-- 1. idx_ussd_tx_triggered_asc          (~3.0 GB) -> Duplicate of triggered_id (bidirectional B-Tree)
-- 2. idx_ussd_tx_node_triggered_id      (~4.2 GB) -> Subsumed by node_status_triggered_id
-- 3. idx_ussd_tx_status_triggered_id    (~3.4 GB) -> Replaced with partial index for FAILED only (~250 MB)
-- 4. idx_trgm_lower_product_name        (~633 MB) -> Dropped (frontend uses exact dropdown selection)
-- 5. idx_trgm_lower_short_code          (~567 MB) -> Dropped (frontend uses exact dropdown selection)
-- 6. idx_trgm_lower_error_code          (~533 MB) -> Dropped (frontend uses exact dropdown selection)
-- ============================================================================

-- ── 1. Drop 100% duplicate / redundant composite B-Tree indexes ─────────────
DROP INDEX IF EXISTS idx_ussd_tx_triggered_asc;
DROP INDEX IF EXISTS idx_ussd_tx_node_triggered_id;

-- ── 2. Replace 3.4 GB full status index with lean partial index for FAILED ─
-- Indexing SUCCESS (90%+ of traffic) wastes ~3 GB; dashboard error analysis only filters FAILED.
CREATE INDEX IF NOT EXISTS idx_ussd_tx_failed_triggered_id 
    ON ussd_transactions(triggered_at DESC, id DESC) 
    WHERE status = 'FAILED';

DROP INDEX IF EXISTS idx_ussd_tx_status_triggered_id;

-- ── 3. Drop unneeded GIN trigram indexes (~1.7 GB total) ────────────────────
-- Dashboard UI uses SearchableSelect dropdowns with exact values rather than full fuzzy text searches.
DROP INDEX IF EXISTS idx_trgm_lower_product_name;
DROP INDEX IF EXISTS idx_trgm_lower_short_code;
DROP INDEX IF EXISTS idx_trgm_lower_error_code;
