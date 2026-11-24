-- V33__add_tibco_response_and_error_reason.sql
-- Separate actual TIBCO HTTP response body and error reason

ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS tibco_response TEXT;
ALTER TABLE ussd_transactions ADD COLUMN IF NOT EXISTS error_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_ussd_tx_error_reason ON ussd_transactions(error_reason);
