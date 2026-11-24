-- V27__add_ftp_fetch_columns.sql
-- Adds plain-FTP credentials/remote path and fetch-progress tracking to ussd_servers,
-- used by LogFetchService to pull daily TIBCO log files from the remote nodes before ingestion.

ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS ftp_username VARCHAR(100);
ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS encrypted_ftp_password VARCHAR(500);
ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS ftp_remote_path VARCHAR(500) NOT NULL DEFAULT '/mediation';
ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS last_fetch_date DATE;
