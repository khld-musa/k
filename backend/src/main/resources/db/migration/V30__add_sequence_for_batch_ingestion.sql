-- V30__add_sequence_for_batch_ingestion.sql
-- Switch ussd_transactions.id from BIGSERIAL (IDENTITY) to an explicit SEQUENCE so Hibernate can
-- pre-allocate IDs in blocks of 50 and batch INSERTs — critical for high-speed log ingestion.
-- The existing BIGSERIAL already created a sequence called ussd_transactions_id_seq; we just need
-- to ensure it exists and adjust the increment for Hibernate's allocationSize = 50.

-- Ensure the sequence exists (BIGSERIAL created it automatically, but be defensive)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE relkind = 'S' AND relname = 'ussd_transactions_id_seq') THEN
        CREATE SEQUENCE ussd_transactions_id_seq OWNED BY ussd_transactions.id;
        -- Set current value from the max existing id
        PERFORM setval('ussd_transactions_id_seq', COALESCE((SELECT MAX(id) FROM ussd_transactions), 1));
    END IF;
END $$;

-- Bump the increment to 50 to match Hibernate's allocationSize (avoids a DB round-trip per INSERT).
ALTER SEQUENCE ussd_transactions_id_seq INCREMENT BY 50;

-- Remove the IDENTITY / BIGSERIAL default and bind the column to use the sequence explicitly.
-- (Older PostgreSQL versions may already have this default from BIGSERIAL; this ensures consistency.)
ALTER TABLE ussd_transactions ALTER COLUMN id SET DEFAULT nextval('ussd_transactions_id_seq');
