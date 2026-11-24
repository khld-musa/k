-- V34__create_report_jobs_table.sql
-- Background queue for asynchronous report generation

CREATE TABLE IF NOT EXISTS report_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_type     VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    filters_json    TEXT,
    file_path       VARCHAR(500),
    file_name       VARCHAR(255),
    file_size       BIGINT,
    requested_by    VARCHAR(100),
    requested_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    error_message   TEXT,
    expires_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_report_jobs_status ON report_jobs(status);
CREATE INDEX IF NOT EXISTS idx_report_jobs_user ON report_jobs(requested_by, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_jobs_expires ON report_jobs(expires_at);
