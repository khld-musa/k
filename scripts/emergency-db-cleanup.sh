#!/usr/bin/env bash
# ==============================================================================
# emergency-db-cleanup.sh — Emergency Database Storage Cleanup & Space Reclaim
# ==============================================================================
# Use this script when /var/lib/postgresql/15/main is critically full.
# It safely deletes old records in batches, reclaims dead tuples, and tunes vacuum.
#
# Usage:
#   sudo bash scripts/emergency-db-cleanup.sh [DAYS_TO_KEEP]
#
# Example (keep last 14 days of data to free up maximum space immediately):
#   sudo bash scripts/emergency-db-cleanup.sh 14
# ==============================================================================
set -euo pipefail

DAYS="${1:-30}"
DB_NAME="kraken_db"

echo "================================================================="
echo "   KRAKEN EMERGENCY DATABASE STORAGE CLEANUP (Days: ${DAYS})"
echo "================================================================="

# Check disk space before
echo "[INFO] Current disk usage on PostgreSQL partition:"
df -h /var/lib/postgresql 2>/dev/null || df -h /

# Run queries via postgres user
run_psql() {
    if command -v su &>/dev/null && id -u postgres &>/dev/null; then
        su - postgres -c "psql -d ${DB_NAME} -c \"$1\""
    else
        psql -U kraken -d ${DB_NAME} -c "$1"
    fi
}

echo ""
echo "[1/5] Checking current table and database sizes..."
run_psql "
SELECT 
    pg_size_pretty(pg_database_size(current_database())) AS total_db_size,
    pg_size_pretty(pg_total_relation_size('ussd_transactions')) AS ussd_tx_total,
    pg_size_pretty(pg_relation_size('ussd_transactions')) AS table_only,
    pg_size_pretty(pg_indexes_size('ussd_transactions')) AS indexes_only,
    n_dead_tup AS dead_rows_bloat,
    n_live_tup AS live_rows
FROM pg_stat_user_tables WHERE relname = 'ussd_transactions';
"

echo ""
echo "[2/5] Purging ussd_transactions older than ${DAYS} days in batches..."
run_psql "
DO \$\$
DECLARE
    deleted INT := 0;
    total_deleted BIGINT := 0;
    cutoff TIMESTAMP := NOW() - INTERVAL '${DAYS} days';
BEGIN
    LOOP
        DELETE FROM ussd_transactions 
        WHERE id IN (
            SELECT id FROM ussd_transactions 
            WHERE triggered_at < cutoff 
            ORDER BY triggered_at ASC, id ASC 
            LIMIT 25000
        );
        GET DIAGNOSTICS deleted = ROW_COUNT;
        total_deleted := total_deleted + deleted;
        EXIT WHEN deleted = 0;
        COMMIT;
        RAISE NOTICE 'Deleted % rows (Total: %)', deleted, total_deleted;
    END LOOP;
    RAISE NOTICE 'Finished purging old transactions. Total deleted: %', total_deleted;
END \$\$;
"

echo ""
echo "[3/5] Cleaning up old rollup and expired report jobs..."
run_psql "
DELETE FROM ussd_stats_hourly WHERE hour_start < NOW() - INTERVAL '${DAYS} days';
DELETE FROM ussd_stats_hourly_coverage WHERE hour_start < NOW() - INTERVAL '${DAYS} days';
DELETE FROM report_jobs WHERE created_at < NOW() - INTERVAL '7 days';
"

echo ""
echo "[4/5] Running VACUUM ANALYZE on ussd_transactions to free dead space..."
run_psql "VACUUM ANALYZE ussd_transactions;"
run_psql "VACUUM ANALYZE ussd_stats_hourly;"

echo ""
echo "[5/5] Storage sizes AFTER cleanup:"
run_psql "
SELECT 
    pg_size_pretty(pg_database_size(current_database())) AS total_db_size,
    pg_size_pretty(pg_total_relation_size('ussd_transactions')) AS ussd_tx_total,
    pg_size_pretty(pg_relation_size('ussd_transactions')) AS table_only,
    pg_size_pretty(pg_indexes_size('ussd_transactions')) AS indexes_only,
    n_dead_tup AS dead_rows_bloat,
    n_live_tup AS live_rows
FROM pg_stat_user_tables WHERE relname = 'ussd_transactions';
"

echo ""
echo "[INFO] Disk usage after cleanup:"
df -h /var/lib/postgresql 2>/dev/null || df -h /
echo "================================================================="
echo "   EMERGENCY CLEANUP COMPLETED SUCCESSFULLY!"
echo "================================================================="
