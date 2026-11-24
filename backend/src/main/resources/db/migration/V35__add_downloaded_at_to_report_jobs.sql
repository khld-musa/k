-- V35__add_downloaded_at_to_report_jobs.sql
-- Add downloaded_at timestamp to track when a user has retrieved the export file

ALTER TABLE report_jobs ADD COLUMN IF NOT EXISTS downloaded_at TIMESTAMP;
