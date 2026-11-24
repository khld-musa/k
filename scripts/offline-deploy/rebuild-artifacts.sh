#!/usr/bin/env bash
#
# rebuild-artifacts.sh — Rebuild Backend, APN Gateway, and/or Frontend, then
#                        package them ready to push to the offline server.
#
# Run this on your DEVELOPMENT LAPTOP after fixing bugs / making changes.
#
# Usage:
#   ./scripts/offline-deploy/rebuild-artifacts.sh [backend|apn|frontend|both|all]
#
# Examples:
#   ./scripts/offline-deploy/rebuild-artifacts.sh backend    # rebuild Kraken ITDS backend JAR only
#   ./scripts/offline-deploy/rebuild-artifacts.sh apn        # rebuild Kraken APN Gateway JAR only
#   ./scripts/offline-deploy/rebuild-artifacts.sh frontend   # rebuild frontend only
#   ./scripts/offline-deploy/rebuild-artifacts.sh both       # rebuild backend, apn and frontend (default)
#   ./scripts/offline-deploy/rebuild-artifacts.sh all        # same as both
#
# What it produces in offline-bundle/:
#   kraken-backend.jar                  ← Kraken ITDS monolith (reports, admin, mediation)
#   kraken-apn.jar                      ← Dedicated standalone APN Gateway (port 8081)
#   kraken-frontend-standalone.tar.gz   ← Next.js frontend standalone
#
# Then push to the server and apply:
#   scp offline-bundle/kraken-backend.jar                 user@SERVER:/tmp/
#   scp offline-bundle/kraken-apn.jar                     user@SERVER:/tmp/
#   scp offline-bundle/kraken-frontend-standalone.tar.gz  user@SERVER:/tmp/
#   scp offline-bundle/apply-update.sh                    user@SERVER:/tmp/
#   ssh user@SERVER "sudo bash /tmp/apply-update.sh all"
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# ─── Setup ───
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUNDLE_DIR="${PROJECT_ROOT}/offline-bundle"
TARGET="${1:-both}"

[[ "${TARGET}" =~ ^(backend|apn|frontend|both|all)$ ]] || \
    err "Invalid target '${TARGET}'. Use: backend | apn | frontend | both | all"

mkdir -p "${BUNDLE_DIR}"

info "Project root : ${PROJECT_ROOT}"
info "Bundle dir   : ${BUNDLE_DIR}"
info "Target       : ${TARGET}"
echo ""

# ═══════════════════════════════════════
# BACKEND (Kraken ITDS)
# ═══════════════════════════════════════
if [[ "${TARGET}" == "backend" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    info "Building Kraken Backend JAR..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    cd "${PROJECT_ROOT}/backend"
    chmod +x mvnw
    ./mvnw clean package -DskipTests

    JAR_FILE=$(ls target/kraken-*.jar 2>/dev/null | grep -v original | head -1)
    [[ -z "${JAR_FILE}" ]] && err "Backend JAR not found after build"

    cp "${JAR_FILE}" "${BUNDLE_DIR}/kraken-backend.jar"
    ok "Backend JAR ready: $(du -h "${BUNDLE_DIR}/kraken-backend.jar" | cut -f1)"
    echo ""
fi

# ═══════════════════════════════════════
# APN GATEWAY (Standalone Real-time Service)
# ═══════════════════════════════════════
if [[ "${TARGET}" == "apn" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    info "Building Kraken APN Gateway JAR..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    cd "${PROJECT_ROOT}/kraken-apn"
    chmod +x mvnw
    ./mvnw clean package -DskipTests

    APN_JAR_FILE=$(ls target/kraken-apn-*.jar 2>/dev/null | grep -v original | head -1)
    [[ -z "${APN_JAR_FILE}" ]] && err "APN Gateway JAR not found after build"

    cp "${APN_JAR_FILE}" "${BUNDLE_DIR}/kraken-apn.jar"
    ok "APN Gateway JAR ready: $(du -h "${BUNDLE_DIR}/kraken-apn.jar" | cut -f1)"
    echo ""
fi

# ═══════════════════════════════════════
# FRONTEND
# ═══════════════════════════════════════
if [[ "${TARGET}" == "frontend" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    info "Building Frontend (Next.js standalone)..."
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    cd "${PROJECT_ROOT}/frontend"
    npm ci

    # Use relative URL so the same build works on any server IP
    export NEXT_PUBLIC_API_URL="/api/v1"
    info "NEXT_PUBLIC_API_URL = ${NEXT_PUBLIC_API_URL}"

    npm run build

    STANDALONE_DIR="${PROJECT_ROOT}/frontend/.next/standalone"
    [[ -d "${STANDALONE_DIR}" ]] || \
        err "Standalone output not found. Ensure next.config.ts has: output: 'standalone'"

    # Inject public/ and .next/static/ into standalone (required by Next.js)
    cp -r "${PROJECT_ROOT}/frontend/public"       "${STANDALONE_DIR}/public"       2>/dev/null || warn "No public/ found"
    mkdir -p "${STANDALONE_DIR}/.next"
    cp -r "${PROJECT_ROOT}/frontend/.next/static" "${STANDALONE_DIR}/.next/static" 2>/dev/null || warn "No .next/static/ found"

    # Package into tar.gz (same format install-on-server.sh expects)
    tar -czf "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" -C "${STANDALONE_DIR}" .

    ok "Frontend standalone ready: $(du -h "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" | cut -f1)"
    echo ""
fi

# Copy deployment and maintenance scripts into the bundle
cp "${PROJECT_ROOT}/scripts/offline-deploy/apply-update.sh" "${BUNDLE_DIR}/apply-update.sh"
cp "${PROJECT_ROOT}/scripts/offline-deploy/run-v46-pre-deploy.sh" "${BUNDLE_DIR}/run-v46-pre-deploy.sh"
cp "${PROJECT_ROOT}/scripts/emergency-db-cleanup.sh" "${BUNDLE_DIR}/emergency-db-cleanup.sh"
chmod +x "${BUNDLE_DIR}/apply-update.sh" "${BUNDLE_DIR}/run-v46-pre-deploy.sh" "${BUNDLE_DIR}/emergency-db-cleanup.sh"

# ═══════════════════════════════════════
# SUMMARY
# ═══════════════════════════════════════
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
ok  "Build complete! Artifacts in: ${BUNDLE_DIR}/"
ls -lh "${BUNDLE_DIR}/kraken-backend.jar"                2>/dev/null || true
ls -lh "${BUNDLE_DIR}/kraken-apn.jar"                    2>/dev/null || true
ls -lh "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" 2>/dev/null || true
echo ""
info "Next: copy to the production server and apply:"
echo ""
echo "  1. Copy bundle files to /tmp/ on server:"
if [[ "${TARGET}" == "backend" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo "     scp offline-bundle/kraken-backend.jar        user@SERVER:/tmp/"
fi
if [[ "${TARGET}" == "apn" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo "     scp offline-bundle/kraken-apn.jar            user@SERVER:/tmp/"
fi
if [[ "${TARGET}" == "frontend" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo "     scp offline-bundle/kraken-frontend-standalone.tar.gz user@SERVER:/tmp/"
fi
echo "     scp offline-bundle/run-v46-pre-deploy.sh     user@SERVER:/tmp/"
echo "     scp offline-bundle/apply-update.sh           user@SERVER:/tmp/"
echo ""
echo "  2. On the server, apply the update:"
echo "     ssh user@SERVER 'sudo bash /tmp/apply-update.sh ${TARGET}'"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
