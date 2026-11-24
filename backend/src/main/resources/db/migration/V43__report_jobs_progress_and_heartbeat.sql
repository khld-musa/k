-- V43: progress tracking and heartbeat for background export jobs
ALTER TABLE report_jobs ADD COLUMN IF NOT EXISTS rows_written BIGINT;
ALTER TABLE report_jobs ADD COLUMN IF NOT EXISTS total_rows BIGINT;
ALTER TABLE report_jobs ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;
