#!/usr/bin/env bash
# ==============================================================================
# run-v46-pre-deploy.sh — Pre-Deployment Storage Optimization & V46 Execution
# ==============================================================================
# Run this script on the production server BEFORE or DURING deployment.
# It runs the V46 storage optimization (drops redundant indexes, tunes autovacuum),
# marks V46 in flyway_schema_history so the app boots in seconds without timeout,
# and runs VACUUM ANALYZE to reclaim disk space immediately.
#
# Usage:
#   sudo bash scripts/offline-deploy/run-v46-pre-deploy.sh
# ==============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -ne 0 ]] && err "This script must be run as root: sudo bash $0"

DB_NAME="kraken_db"
DB_USER="kraken"

run_psql() {
    if command -v su &>/dev/null && id -u postgres &>/dev/null; then
        su - postgres -c "psql -d ${DB_NAME} -c \"$1\""
    else
        psql -U "${DB_USER}" -d "${DB_NAME}" -c "$1"
    fi
}

echo -e "\n${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${CYAN}   PRE-DEPLOYMENT STORAGE OPTIMIZATION & V46 PRE-MIGRATION     ${NC}"
echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════════${NC}\n"

info "Current disk usage on PostgreSQL partition:"
df -h /var/lib/postgresql 2>/dev/null || df -h /

info "Storage breakdown BEFORE optimization:"
run_psql "
SELECT 
    pg_size_pretty(pg_database_size(current_database())) AS database_total,
    pg_size_pretty(pg_total_relation_size('ussd_transactions')) AS ussd_tx_total,
    pg_size_pretty(pg_relation_size('ussd_transactions')) AS table_only,
    pg_size_pretty(pg_indexes_size('ussd_transactions')) AS indexes_only,
    n_dead_tup AS dead_rows_bloat,
    n_live_tup AS live_rows
FROM pg_stat_user_tables WHERE relname = 'ussd_transactions';
" || true

info "Step 1: Dropping redundant composite indexes (instant DDL)..."
run_psql "DROP INDEX IF EXISTS idx_ussd_tx_status_triggered;"
run_psql "DROP INDEX IF EXISTS idx_ussd_tx_errcode_triggered;"
run_psql "DROP INDEX IF EXISTS idx_ussd_tx_shortcode_triggered;"
run_psql "DROP INDEX IF EXISTS idx_ussd_tx_triggered_asc;"
run_psql "DROP INDEX IF EXISTS idx_ussd_tx_node_triggered_id;"
run_psql "CREATE INDEX IF NOT EXISTS idx_ussd_tx_failed_triggered_id ON ussd_transactions(triggered_at DESC, id DESC) WHERE status = 'FAILED';"
run_psql "DROP INDEX IF EXISTS idx_ussd_tx_status_triggered_id;"
run_psql "DROP INDEX IF EXISTS idx_trgm_lower_product_name;"
run_psql "DROP INDEX IF EXISTS idx_trgm_lower_short_code;"
run_psql "DROP INDEX IF EXISTS idx_trgm_lower_error_code;"
ok "Redundant indexes dropped and partial error index verified"

info "Step 2: Tuning autovacuum on ussd_transactions and ussd_stats_hourly..."
run_psql "
ALTER TABLE ussd_transactions SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_vacuum_threshold = 5000,
    autovacuum_analyze_scale_factor = 0.02,
    autovacuum_analyze_threshold = 5000,
    autovacuum_vacuum_cost_delay = 10
);
"
run_psql "
ALTER TABLE ussd_stats_hourly SET (
    autovacuum_vacuum_scale_factor = 0.05,
    autovacuum_vacuum_threshold = 100
);
"
ok "Autovacuum parameters tuned (continuous background vacuum enabled)"

info "Step 3: Registering V46 in flyway_schema_history..."
# Clean up any failed or existing V46 record to ensure consistent state
run_psql "DELETE FROM flyway_schema_history WHERE version = '46';"

run_psql "
INSERT INTO flyway_schema_history (
    installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
) VALUES (
    (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
    '46',
    'optimize storage and autovacuum',
    'SQL',
    'V46__optimize_storage_and_autovacuum.sql',
    NULL,
    '${DB_USER}',
    1,
    true
);
"
ok "V46 successfully recorded in Flyway history (app will skip V46 on startup)"

info "Step 4: Running online VACUUM ANALYZE to reclaim dead space..."
run_psql "VACUUM ANALYZE ussd_transactions;"
run_psql "VACUUM ANALYZE ussd_stats_hourly;"
ok "VACUUM ANALYZE completed"

echo -e "\n${BOLD}${GREEN}═══════════════════════════════════════════════════════════════${NC}"
info "Storage breakdown AFTER optimization:"
run_psql "
SELECT 
    pg_size_pretty(pg_database_size(current_database())) AS database_total,
    pg_size_pretty(pg_total_relation_size('ussd_transactions')) AS ussd_tx_total,
    pg_size_pretty(pg_relation_size('ussd_transactions')) AS table_only,
    pg_size_pretty(pg_indexes_size('ussd_transactions')) AS indexes_only,
    n_dead_tup AS dead_rows_bloat,
    n_live_tup AS live_rows
FROM pg_stat_user_tables WHERE relname = 'ussd_transactions';
" || true

info "Remaining indexes on ussd_transactions:"
run_psql "
SELECT indexname, pg_size_pretty(pg_relation_size(quote_ident(indexname)::text)) as size
FROM pg_indexes WHERE tablename = 'ussd_transactions'
ORDER BY pg_relation_size(quote_ident(indexname)::text) DESC;
" || true

echo -e "\n[INFO] Disk space after cleanup:"
df -h /var/lib/postgresql 2>/dev/null || df -h /

echo -e "\n${BOLD}${GREEN}✔ V46 Pre-migration completed successfully!${NC}"
echo -e "${CYAN}You can now safely restart or deploy the backend without any migration timeouts.${NC}\n"
