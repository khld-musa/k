-- V38__add_trgm_gin_indexes.sql
-- Enables pg_trgm and creates GIN trigram indexes for high-speed ILIKE text search.
-- Wrapped in a safe DO block so that if postgresql-contrib is not yet installed on the server,
-- boot is NOT blocked with a fatal Flyway exception.

DO $$
BEGIN
    -- Check or enable pg_trgm extension
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'pg_trgm extension is not yet available in PostgreSQL. Skipping GIN indexes until postgresql-contrib-15 is installed.';
        RETURN;
    END;

    -- If pg_trgm is present, create GIN trigram indexes
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_trgm_short_code
            ON ussd_transactions USING GIN (short_code gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_product_name
            ON ussd_transactions USING GIN (product_name gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_error_code
            ON ussd_transactions USING GIN (error_code gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_msisdn
            ON ussd_transactions USING GIN (msisdn gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_error_message
            ON ussd_transactions USING GIN (error_message gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_tibco_response
            ON ussd_transactions USING GIN (tibco_response gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_error_reason
            ON ussd_transactions USING GIN (error_reason gin_trgm_ops);

        CREATE INDEX IF NOT EXISTS idx_trgm_subscriber_reply
            ON ussd_transactions USING GIN (subscriber_reply gin_trgm_ops);
    END IF;
END $$;
