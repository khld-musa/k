-- V14__create_ussd_transactions_table.sql
-- Pre-analytics storage for high-speed log reporting

CREATE TABLE ussd_transactions (
    id BIGSERIAL PRIMARY KEY,
    call_id VARCHAR(50) NOT NULL,
    msisdn VARCHAR(20),
    transaction_id VARCHAR(100),
    status VARCHAR(20),
    latency_ms BIGINT,
    short_code VARCHAR(100),
    product_name VARCHAR(255),
    error_code VARCHAR(100),
    service_type VARCHAR(50),
    lang VARCHAR(10),
    node_ip VARCHAR(50),
    cell_id VARCHAR(50),
    vlr VARCHAR(50),
    triggered_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Ensure call_id is unique for UPSERT idempotency
CREATE UNIQUE INDEX idx_ussd_transactions_call_id ON ussd_transactions(call_id);

-- Performance indexes for reports
CREATE INDEX idx_ussd_transactions_triggered_at ON ussd_transactions(triggered_at);
CREATE INDEX idx_ussd_transactions_msisdn ON ussd_transactions(msisdn);
CREATE INDEX idx_ussd_transactions_status ON ussd_transactions(status);
CREATE INDEX idx_ussd_transactions_node_ip ON ussd_transactions(node_ip);

COMMENT ON TABLE ussd_transactions IS 'Historical USSD transaction analytics for fast reporting.';
