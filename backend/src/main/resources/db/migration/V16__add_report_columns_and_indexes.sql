-- V16__add_report_columns_and_indexes.sql
-- Adds channel + error_message columns and composite indexes for report queries

ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS channel VARCHAR(50);
ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS error_message VARCHAR(500);

-- Composite indexes for report query performance
CREATE INDEX IF NOT EXISTS idx_ussd_tx_triggered_status ON ussd_transactions(triggered_at, status);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_triggered_node ON ussd_transactions(triggered_at, node_ip);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_triggered_product ON ussd_transactions(triggered_at, product_name);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_short_code ON ussd_transactions(short_code);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_service_type ON ussd_transactions(service_type);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_lang ON ussd_transactions(lang);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_cell_id ON ussd_transactions(cell_id);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_vlr ON ussd_transactions(vlr);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_latency ON ussd_transactions(latency_ms);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_channel ON ussd_transactions(channel);

