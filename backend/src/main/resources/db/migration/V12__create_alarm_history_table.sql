CREATE TABLE IF NOT EXISTS alarm_history (
    id BIGSERIAL PRIMARY KEY,
    alarm_config_id BIGINT NOT NULL,
    username VARCHAR(255),
    client_id VARCHAR(255) NOT NULL,
    current_balance DECIMAL(19, 6) NOT NULL,
    threshold DECIMAL(19, 6) NOT NULL,
    triggered_at TIMESTAMP NOT NULL,
    sms_message TEXT,
    email_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_alarm_history_config_id ON alarm_history(alarm_config_id);
CREATE INDEX IF NOT EXISTS idx_alarm_history_triggered_at ON alarm_history(triggered_at);
