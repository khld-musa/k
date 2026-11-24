-- V9__add_log_path_to_ussd_servers.sql
-- Add per-server log path configuration

ALTER TABLE ussd_servers ADD COLUMN log_path VARCHAR(500) DEFAULT '/home/khld/ussd/logs';

COMMENT ON COLUMN ussd_servers.log_path IS 'Base directory path for USSD log files on this server';
