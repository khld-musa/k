-- V15__add_last_sync_date_to_servers.sql
-- Track the last sync date to handle daily log rollover automatically

ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS last_sync_date DATE;

COMMENT ON COLUMN ussd_servers.last_sync_date IS 'The last day Kraken successfully synced logs for this server.';
