-- V22__create_balance_records_table.sql
-- Creates the balance_records table to store all balance API query results
-- for historical tracking and trend analysis.

CREATE TABLE balance_records (
    id BIGSERIAL PRIMARY KEY,
    alarm_config_id BIGINT NOT NULL,
    username VARCHAR(255),
    client_id VARCHAR(255) NOT NULL,
    balance DECIMAL(15, 2) NOT NULL,
    threshold DECIMAL(15, 2) NOT NULL,
    checked_at TIMESTAMP NOT NULL,
    is_below_threshold BOOLEAN NOT NULL DEFAULT FALSE,
    alert_triggered BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_balance_alarm_config FOREIGN KEY (alarm_config_id)
        REFERENCES alarm_configs(id) ON DELETE CASCADE
);

-- Indexes for efficient querying
CREATE INDEX idx_balance_client_id ON balance_records(client_id);
CREATE INDEX idx_balance_checked_at ON balance_records(checked_at);
CREATE INDEX idx_balance_alarm_config_id ON balance_records(alarm_config_id);
CREATE INDEX idx_balance_config_checked ON balance_records(alarm_config_id, checked_at DESC);

-- Comment for documentation
COMMENT ON TABLE balance_records IS 'Stores all balance API query results for historical tracking, unlike alarm_history which only records threshold breaches';
COMMENT ON COLUMN balance_records.alert_triggered IS 'True if this balance check triggered an alert notification';
COMMENT ON COLUMN balance_records.is_below_threshold IS 'True if balance was below threshold at check time (may not trigger alert due to cooldown)';

