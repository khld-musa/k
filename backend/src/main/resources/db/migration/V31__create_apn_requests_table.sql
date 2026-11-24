-- V31__create_apn_requests_table.sql
CREATE TABLE IF NOT EXISTS apn_requests (
    id                  BIGSERIAL PRIMARY KEY,
    call_id             VARCHAR(100),
    msisdn              VARCHAR(30) NOT NULL,
    imsi                VARCHAR(30) NOT NULL,
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    response_message    VARCHAR(500),
    http_status         INTEGER,
    latency_ms          BIGINT,
    raw_request         TEXT,
    raw_response        TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_apn_requests_created ON apn_requests(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_apn_requests_msisdn ON apn_requests(msisdn);
CREATE INDEX IF NOT EXISTS idx_apn_requests_status ON apn_requests(status);
CREATE INDEX IF NOT EXISTS idx_apn_requests_created_status ON apn_requests(created_at DESC, status);
