-- V32__add_tibco_report_columns.sql
-- Add columns for detailed TIBCO USSD error reporting

ALTER TABLE ussd_transactions ALTER COLUMN error_message TYPE TEXT;
ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS remote_url VARCHAR(500);
ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS subscriber_reply TEXT;
ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS response_time TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_ussd_tx_remote_url ON ussd_transactions(remote_url);
CREATE INDEX IF NOT EXISTS idx_ussd_tx_response_time ON ussd_transactions(response_time);
