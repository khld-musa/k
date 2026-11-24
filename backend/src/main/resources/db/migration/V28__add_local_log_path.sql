-- V28__add_local_log_path.sql
-- Adds a dedicated local-disk base path for USSD log files pulled in by LogFetchService and
-- read by LogIngestionService, separate from the existing `log_path` column (which remains the
-- *remote* directory used by the live SSH log search feature on the node itself).
-- Each server's files live under {local_log_path}/{server name}/ - see ServerLogPaths.

ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS local_log_path VARCHAR(500) NOT NULL DEFAULT '/var/log/ussd';
