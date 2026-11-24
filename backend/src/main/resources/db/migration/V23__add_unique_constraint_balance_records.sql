-- V23__add_unique_constraint_balance_records.sql
-- Modifies the balance_records table to ensure only one record per alarm_config_id
-- This saves database space by updating existing records instead of creating new ones.

-- First, delete duplicate records, keeping only the most recent one for each alarm_config_id
DELETE FROM balance_records
WHERE id NOT IN (
    SELECT MAX(id)
    FROM balance_records
    GROUP BY alarm_config_id
);

-- Add unique constraint to prevent duplicate records per alarm config
ALTER TABLE balance_records
ADD CONSTRAINT uq_balance_alarm_config_id UNIQUE (alarm_config_id);

-- Update comment to reflect new behavior
COMMENT ON TABLE balance_records IS 'Stores the current balance status for each alarm config (one record per config, updated on each check)';

