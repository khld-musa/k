CREATE TABLE ussd_proxy_requests (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID NOT NULL DEFAULT gen_random_uuid(),
    source_ip       VARCHAR(45),
    request_body    TEXT,           -- Original JSON from USSD app
    response_body   TEXT,           -- Full response from external API
    response_message TEXT,          -- Extracted message field
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    http_status_code INTEGER,
    response_time_ms BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

-- Indexes optimized for reporting queries
CREATE INDEX idx_proxy_req_status ON ussd_proxy_requests(status);
CREATE INDEX idx_proxy_req_created ON ussd_proxy_requests(created_at DESC);
CREATE INDEX idx_proxy_req_created_status ON ussd_proxy_requests(created_at, status);
