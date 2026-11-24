#!/usr/bin/env bash
#
# create-bundle.sh — Package everything for Ubuntu 22.04 (Jammy)
# ═══════════════════════════════════════════════════════════════
# Creates a single tarball ready to transfer to the production server.
#
# Usage:
#   ./scripts/offline-deploy/jammy/create-bundle.sh
#
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

PROJECT_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BUNDLE_DIR="${PROJECT_ROOT}/offline-bundle"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_FILE="${PROJECT_ROOT}/kraken-bundle-jammy.tar.gz"

# ─── Verify all required components ───
info "Verifying bundle components..."

[[ -f "${BUNDLE_DIR}/kraken-backend.jar" ]]                || err "Missing: kraken-backend.jar — run build-artifacts.sh first"
[[ -f "${BUNDLE_DIR}/kraken-apn.jar" ]]                    || err "Missing: kraken-apn.jar — run build-artifacts.sh first"
[[ -f "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" ]] || err "Missing: kraken-frontend-standalone.tar.gz — run build-artifacts.sh first"
[[ -d "${BUNDLE_DIR}/debs-jammy" ]]                        || err "Missing: debs-jammy/ — run jammy/download-packages.sh first"
[[ -f "${BUNDLE_DIR}/SERVER_IP.txt" ]]                     || err "Missing: SERVER_IP.txt — run build-artifacts.sh first"

# Verify we actually have a meaningful number of .deb files
DEB_COUNT=$(ls -1 "${BUNDLE_DIR}/debs-jammy/"*.deb 2>/dev/null | wc -l)
if [[ "${DEB_COUNT}" -lt 10 ]]; then
    err "Only ${DEB_COUNT} .deb files in debs-jammy/ — re-run download-packages.sh"
fi

ok "All components found (${DEB_COUNT} .deb packages)"

# ─── Copy the server install script into the bundle ───
cp "${SCRIPT_DIR}/install-on-server.sh" "${BUNDLE_DIR}/install-on-server.sh"
chmod +x "${BUNDLE_DIR}/install-on-server.sh"

# ─── Build the file list for tar ───
TAR_FILES=(
    kraken-backend.jar
    kraken-apn.jar
    kraken-frontend-standalone.tar.gz
    install-on-server.sh
    SERVER_IP.txt
    debs-jammy/
)

# Include binaries/ if present (fallback for when .deb install fails)
if [[ -d "${BUNDLE_DIR}/binaries" ]]; then
    TAR_FILES+=( binaries/ )
    ok "Including binary tarballs (fallback)"
fi

# ─── Create the final tarball ───
info "Creating bundle: ${OUTPUT_FILE}"
info "This may take a minute..."

cd "${PROJECT_ROOT}"
tar -czf "${OUTPUT_FILE}" -C "${BUNDLE_DIR}" "${TAR_FILES[@]}"

echo ""
ok "═══════════════════════════════════════════════════════════"
ok " Bundle created: ${OUTPUT_FILE}"
ok " Size: $(du -h "${OUTPUT_FILE}" | cut -f1)"
ok "═══════════════════════════════════════════════════════════"
echo ""
info "Next steps:"
info "  1. Copy ${OUTPUT_FILE} to the production server via SCP/USB"
info "  2. SSH into the server and run:"
info "     cd /tmp && tar -xzf kraken-bundle-jammy.tar.gz"
info "     sudo bash install-on-server.sh <SERVER_IP>"
