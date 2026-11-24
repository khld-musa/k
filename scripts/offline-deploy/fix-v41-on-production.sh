#!/usr/bin/env bash
# ============================================================================
# fix-v41-on-production.sh
# ============================================================================
# Run this ONCE on the production server BEFORE deploying the new JAR.
# It repairs Flyway's failed V41 state and manually runs the corrected V41
# (drop-only) so the app can boot cleanly.
#
# Usage:
#   sudo bash fix-v41-on-production.sh
# ============================================================================
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -ne 0 ]] && err "Run as root: sudo bash $0"

DB_NAME="kraken_db"
DB_USER="kraken"

info "Checking current Flyway state..."
su - postgres -c "psql -d ${DB_NAME} -c \"SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;\"" || true

info "Step 1: Remove failed V41 entry from Flyway history (if it exists)..."
su - postgres -c "psql -d ${DB_NAME} -c \"DELETE FROM flyway_schema_history WHERE version = '41' AND success = false;\""
ok "Cleared failed V41 entry"

info "Step 2: Drop ALL dead/bloated indexes (V41 corrected — instant DDL)..."
su - postgres -c "psql -d ${DB_NAME}" <<'SQL'
-- Drop 4 bloated GIN trigram indexes on TEXT columns (V38)
DROP INDEX IF EXISTS idx_trgm_error_message;
DROP INDEX IF EXISTS idx_trgm_tibco_response;
DROP INDEX IF EXISTS idx_trgm_error_reason;
DROP INDEX IF EXISTS idx_trgm_subscriber_reply;

-- Drop 4 non-LOWER() GIN trigrams (V38) — superseded by LOWER() versions
DROP INDEX IF EXISTS idx_trgm_short_code;
DROP INDEX IF EXISTS idx_trgm_product_name;
DROP INDEX IF EXISTS idx_trgm_error_code;
DROP INDEX IF EXISTS idx_trgm_msisdn;

-- Drop single-column indexes subsumed by composites
DROP INDEX IF EXISTS idx_ussd_transactions_triggered_at;
DROP INDEX IF EXISTS idx_ussd_transactions_status;
DROP INDEX IF EXISTS idx_ussd_transactions_node_ip;
DROP INDEX IF EXISTS idx_ussd_transactions_msisdn;
DROP INDEX IF EXISTS idx_ussd_tx_error_code;
DROP INDEX IF EXISTS idx_ussd_tx_short_code;

-- Drop duplicate composite indexes
DROP INDEX IF EXISTS idx_ussd_tx_triggered_status;
DROP INDEX IF EXISTS idx_ussd_tx_triggered_node;
DROP INDEX IF EXISTS idx_ussd_tx_triggered_product;
DROP INDEX IF EXISTS idx_ussd_tx_node_triggered;
DROP INDEX IF EXISTS idx_ussd_tx_search_composite;

-- Drop dead indexes on columns no query ever uses
DROP INDEX IF EXISTS idx_ussd_tx_service_type;
DROP INDEX IF EXISTS idx_ussd_tx_lang;
DROP INDEX IF EXISTS idx_ussd_tx_cell_id;
DROP INDEX IF EXISTS idx_ussd_tx_vlr;
DROP INDEX IF EXISTS idx_ussd_tx_latency;
DROP INDEX IF EXISTS idx_ussd_tx_channel;
DROP INDEX IF EXISTS idx_ussd_tx_remote_url;
DROP INDEX IF EXISTS idx_ussd_tx_response_time;
DROP INDEX IF EXISTS idx_ussd_tx_error_reason;
DROP INDEX IF EXISTS idx_ussd_tx_product_name_btree;

-- Also drop any partial V41 indexes that may have been created before crash
DROP INDEX IF EXISTS idx_ussd_tx_triggered_asc;
DROP INDEX IF EXISTS idx_ussd_tx_triggered_node_status;
DROP INDEX IF EXISTS idx_ussd_tx_call_id;
DROP INDEX IF EXISTS idx_ussd_tx_node_status_triggered_id;
SQL
ok "All dead indexes dropped"

info "Step 3: Mark V41 as successfully applied in Flyway history..."
su - postgres -c "psql -d ${DB_NAME} -c \"
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
VALUES (
    (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
    '41',
    'optimize reporting and cleanup',
    'SQL',
    'V41__optimize_reporting_and_cleanup.sql',
    NULL,
    '${DB_USER}',
    0,
    true
);
\""
ok "V41 marked as applied"

info "Step 4: Create essential indexes CONCURRENTLY (non-blocking)..."

# These are the only new indexes V42 needs. Create them here with CONCURRENTLY
# so they don't lock the table or consume excessive memory.
su - postgres -c "psql -d ${DB_NAME}" <<'SQL'
-- Retention purge index
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ussd_tx_triggered_asc
    ON ussd_transactions(triggered_at ASC, id ASC);
SQL
ok "Created idx_ussd_tx_triggered_asc"

su - postgres -c "psql -d ${DB_NAME}" <<'SQL'
-- Node+status filtered exports
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ussd_tx_node_status_triggered_id
    ON ussd_transactions(node_ip, status, triggered_at DESC, id DESC);
SQL
ok "Created idx_ussd_tx_node_status_triggered_id"

# GIN indexes (if pg_trgm is available)
HAS_TRGM=$(su - postgres -c "psql -d ${DB_NAME} -tAc \"SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm';\"")
if [[ "${HAS_TRGM}" == "1" ]]; then
    su - postgres -c "psql -d ${DB_NAME} -c \"CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trgm_lower_short_code ON ussd_transactions USING GIN (LOWER(short_code) gin_trgm_ops);\""
    ok "Created idx_trgm_lower_short_code"

    su - postgres -c "psql -d ${DB_NAME} -c \"CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trgm_lower_product_name ON ussd_transactions USING GIN (LOWER(product_name) gin_trgm_ops);\""
    ok "Created idx_trgm_lower_product_name"

    su - postgres -c "psql -d ${DB_NAME} -c \"CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trgm_lower_error_code ON ussd_transactions USING GIN (LOWER(error_code) gin_trgm_ops);\""
    ok "Created idx_trgm_lower_error_code"
else
    info "pg_trgm not available — skipping GIN indexes (app will still work, just slower text search)"
fi

info "Step 5: Mark V42 as successfully applied in Flyway history..."
su - postgres -c "psql -d ${DB_NAME} -c \"
INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
VALUES (
    (SELECT COALESCE(MAX(installed_rank), 0) + 1 FROM flyway_schema_history),
    '42',
    'create essential indexes',
    'SQL',
    'V42__create_essential_indexes.sql',
    NULL,
    '${DB_USER}',
    0,
    true
);
\""
ok "V42 marked as applied"

info "Step 6: Verify remaining indexes..."
su - postgres -c "psql -d ${DB_NAME} -c \"
SELECT indexname, pg_size_pretty(pg_relation_size(indexname::regclass)) AS size
FROM pg_indexes
WHERE tablename = 'ussd_transactions'
ORDER BY pg_relation_size(indexname::regclass) DESC;
\""

info "Step 7: Check freed disk space..."
su - postgres -c "psql -d ${DB_NAME} -c \"
SELECT pg_size_pretty(pg_total_relation_size('ussd_transactions')) AS total_with_indexes,
       pg_size_pretty(pg_relation_size('ussd_transactions')) AS table_only,
       pg_size_pretty(pg_total_relation_size('ussd_transactions') - pg_relation_size('ussd_transactions')) AS all_indexes;
\""

echo ""
ok "Production database fixed! You can now deploy the new JAR with apply-update.sh"
echo ""
info "Remaining indexes on ussd_transactions (should be ~11):"
info "  - idx_ussd_transactions_call_id (UNIQUE) — ingestion"
info "  - idx_ussd_tx_status_triggered — error reports"
info "  - idx_ussd_tx_errcode_triggered — top error codes"
info "  - idx_ussd_tx_shortcode_triggered — top short codes"
info "  - idx_ussd_tx_triggered_id — keyset pagination"
info "  - idx_ussd_tx_status_triggered_id — error export"
info "  - idx_ussd_tx_triggered_asc — retention purge"
info "  - idx_ussd_tx_node_status_triggered_id — node+status"
info "  - idx_trgm_lower_short_code — text search"
info "  - idx_trgm_lower_product_name — text search"
info "  - idx_trgm_lower_error_code — text search"
