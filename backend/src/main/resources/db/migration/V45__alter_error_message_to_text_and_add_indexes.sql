-- V45__alter_error_message_to_text_and_add_indexes.sql
-- Fix column overflow crash where error_message was VARCHAR(500)
ALTER TABLE ussd_transactions ALTER COLUMN error_message TYPE TEXT;

-- Restore composite index for node-level report queries without status filter
CREATE INDEX IF NOT EXISTS idx_ussd_tx_node_triggered_id 
    ON ussd_transactions(node_ip, triggered_at DESC, id DESC);

-- Ensure MSISDN search with date ordering uses index
CREATE INDEX IF NOT EXISTS idx_ussd_tx_msisdn_triggered 
    ON ussd_transactions(msisdn, triggered_at DESC);
