#!/usr/bin/env bash
# rebuild-frontend.sh — Rebuild & redeploy frontend only
# Usage: sudo bash scripts/local-deploy/rebuild-frontend.sh
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -ne 0 ]] && err "Run as root: sudo bash $0"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CALLING_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"

[[ -f "${PROJECT_ROOT}/frontend/package.json" ]] || err "Missing frontend/package.json"

info "Building frontend as ${CALLING_USER}..."
chown -R "${CALLING_USER}:${CALLING_USER}" "${PROJECT_ROOT}/frontend" 2>/dev/null || true
sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/frontend' && npm ci && NEXT_PUBLIC_API_URL='/api/v1' npm run build"

STANDALONE_DIR="${PROJECT_ROOT}/frontend/.next/standalone"
[[ -d "${STANDALONE_DIR}" ]] || err "Standalone build not found. Check next.config.ts has output: 'standalone'"

info "Deploying to /opt/kraken/frontend..."
systemctl stop kraken-frontend 2>/dev/null || true
rm -rf /opt/kraken/frontend
mkdir -p /opt/kraken/frontend/.next
cp -r "${STANDALONE_DIR}/." /opt/kraken/frontend/
cp -r "${PROJECT_ROOT}/frontend/public" /opt/kraken/frontend/public 2>/dev/null || true
cp -r "${PROJECT_ROOT}/frontend/.next/static" /opt/kraken/frontend/.next/static 2>/dev/null || true
chown -R nextjs-app:nextjs-app /opt/kraken/frontend

info "Starting frontend..."
systemctl start kraken-frontend
sleep 3

if curl -sf -o /dev/null http://localhost:3000; then
    ok "Frontend rebuilt and running ✅"
else
    err "Frontend not responding on port 3000. Check: sudo journalctl -u kraken-frontend -n 30"
fi

