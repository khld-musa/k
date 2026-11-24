-- V40__add_keyset_and_export_indexes.sql
-- Optimizes keyset cursor pagination for report exports, audit log lookups, and alarm history.

-- 1. Keyset pagination index on ussd_transactions (triggered_at DESC, id DESC)
-- Eliminates sort cost and enables direct B-tree index seek for streaming exports
CREATE INDEX IF NOT EXISTS idx_ussd_tx_triggered_id 
    ON ussd_transactions(triggered_at DESC, id DESC);

-- 2. Status + triggered_at + id for filtered error exports
CREATE INDEX IF NOT EXISTS idx_ussd_tx_status_triggered_id 
    ON ussd_transactions(status, triggered_at DESC, id DESC);

-- 3. Audit log index for dashboard aggregation (countByActionSince, countByActionBetween)
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_timestamp 
    ON audit_logs(action, timestamp DESC);

-- 4. Alarm history index for DISTINCT ON (alarm_config_id) ORDER BY triggered_at DESC
CREATE INDEX IF NOT EXISTS idx_alarm_history_config_triggered 
    ON alarm_history(alarm_config_id, triggered_at DESC);
