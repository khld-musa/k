-- V13__add_ssh_credentials_to_servers.sql
-- Enable automated log pulling with secure credentials

ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS ssh_username VARCHAR(100);
ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS encrypted_password VARCHAR(500);
ALTER TABLE ussd_servers ADD COLUMN IF NOT EXISTS last_read_offset BIGINT DEFAULT 0;

COMMENT ON COLUMN ussd_servers.last_read_offset IS 'The last byte position read from the current log file for incremental sync.';
