-- V26__reingest_ussd_transactions.sql
-- The TIBCO log parser previously missed VLR/Cell ID/error details (trailing whitespace), the status of
-- TIBCO exception responses, and stored the wrong channel. Clearing the sync marker makes the next ingestion
-- run re-read the log files still on disk (up to 90 days back); rows are upserted by call_id, so this
-- corrects existing records without creating duplicates.

UPDATE ussd_servers SET last_sync_date = NULL;
