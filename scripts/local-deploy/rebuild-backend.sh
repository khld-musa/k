#!/usr/bin/env bash
# rebuild-backend.sh — Rebuild & redeploy backend only
# Usage: sudo bash scripts/local-deploy/rebuild-backend.sh
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -ne 0 ]] && err "Run as root: sudo bash $0"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CALLING_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"

TARGET="${1:-backend}"
[[ "${TARGET}" =~ ^(backend|apn|both|all)$ ]] || \
    err "Invalid target '${TARGET}'. Use: backend | apn | both | all"

if [[ "${TARGET}" == "apn" ]]; then
    exec bash "${SCRIPT_DIR}/rebuild-apn.sh"
fi

[[ -f "${PROJECT_ROOT}/backend/pom.xml" ]] || err "Missing backend/pom.xml"

info "Building backend as ${CALLING_USER}..."
chown -R "${CALLING_USER}:${CALLING_USER}" "${PROJECT_ROOT}/backend" 2>/dev/null || true
sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/backend' && ./mvnw clean package -DskipTests"

JAR_FILE=$(ls "${PROJECT_ROOT}/backend/target"/kraken-*.jar 2>/dev/null | grep -v original | head -1)
[[ -z "${JAR_FILE}" ]] && err "Backend JAR build failed"

info "Deploying to /opt/kraken/backend..."
systemctl stop kraken-backend 2>/dev/null || true
mkdir -p /opt/kraken/backend/target /var/log/ussd/exports /mediation
cp "${JAR_FILE}" /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar
chown kraken-app:kraken-app /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar
chown -R kraken-app:kraken-app /mediation 2>/dev/null || true
chmod -R 777 /mediation 2>/dev/null || true
if command -v setfacl &>/dev/null; then
    setfacl -R -d -m u:kraken-app:rwx /mediation 2>/dev/null || true
    setfacl -R -m u:kraken-app:rwx /mediation 2>/dev/null || true
fi
chown -R kraken-app:kraken-app /var/log/ussd 2>/dev/null || true

# Ensure /mediation in ReadWritePaths for systemd isolation if service file exists
SERVICE_FILE="/etc/systemd/system/kraken-backend.service"
if [[ -f "${SERVICE_FILE}" ]] && grep -q "ReadWritePaths=" "${SERVICE_FILE}"; then
    if ! grep -q "/mediation" "${SERVICE_FILE}"; then
        info "Adding /mediation to ReadWritePaths in ${SERVICE_FILE}..."
        sed -i 's|ReadWritePaths=\(.*\)|ReadWritePaths=\1 /mediation|' "${SERVICE_FILE}"
        systemctl daemon-reload
    fi
fi

# Ensure USSD_LOG_BASE_PATH=/mediation in env file
ENV_FILE="/opt/kraken/backend/config/ussd-gateway.env"
if [[ -f "${ENV_FILE}" ]]; then
    if grep -q "USSD_LOG_BASE_PATH=" "${ENV_FILE}"; then
        sed -i 's|USSD_LOG_BASE_PATH=.*|USSD_LOG_BASE_PATH=/mediation|' "${ENV_FILE}"
    else
        echo "USSD_LOG_BASE_PATH=/mediation" >> "${ENV_FILE}"
    fi
fi

info "Starting backend (waiting up to 180s for migrations & health check)..."
systemctl start kraken-backend

BACKEND_OK=false
for i in {1..36}; do
    if curl -sf http://localhost:8080/actuator/health &>/dev/null; then
        BACKEND_OK=true
        break
    fi
    echo -n "."
    sleep 5
done
echo ""

if $BACKEND_OK; then
    ok "Backend rebuilt and healthy ✅"
else
    journalctl -u kraken-backend -n 30 --no-pager 2>/dev/null || true
    err "Backend not healthy after 180s. Check logs above: sudo journalctl -u kraken-backend -n 50"
fi

if [[ "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    echo ""
    info "Now rebuilding and redeploying Kraken APN Gateway..."
    bash "${SCRIPT_DIR}/rebuild-apn.sh"
fi

