#!/usr/bin/env bash
# rebuild-apn.sh — Rebuild & redeploy Kraken APN Gateway only
# Usage: sudo bash scripts/local-deploy/rebuild-apn.sh
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -ne 0 ]] && err "Run as root: sudo bash $0"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
CALLING_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"

[[ -f "${PROJECT_ROOT}/kraken-apn/pom.xml" ]] || err "Missing kraken-apn/pom.xml"

info "Building Kraken APN Gateway as ${CALLING_USER}..."
chown -R "${CALLING_USER}:${CALLING_USER}" "${PROJECT_ROOT}/kraken-apn" 2>/dev/null || true
sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/kraken-apn' && ./mvnw clean package -DskipTests"

JAR_FILE=$(ls "${PROJECT_ROOT}/kraken-apn/target"/kraken-apn-*.jar 2>/dev/null | grep -v original | head -1)
[[ -z "${JAR_FILE}" ]] && err "APN Gateway JAR build failed"

info "Deploying to /opt/kraken/apn..."
systemctl stop kraken-apn 2>/dev/null || true
mkdir -p /opt/kraken/apn/target /opt/kraken/apn/config /var/log/ussd
cp "${JAR_FILE}" /opt/kraken/apn/target/kraken-apn.jar
chown -R kraken-app:kraken-app /opt/kraken/apn /var/log/ussd 2>/dev/null || true

# Setup kraken-apn.env if not present
APN_ENV="/opt/kraken/apn/config/kraken-apn.env"
if [[ ! -f "${APN_ENV}" ]]; then
    BACKEND_ENV="/opt/kraken/backend/config/ussd-gateway.env"
    DB_URL="jdbc:postgresql://localhost:5432/kraken_db"
    DB_USER="kraken"
    DB_PASSWORD="wqDV2fO/IpJZoL0JdFkjsNvG/QhKOMwl"

    if [[ -f "${BACKEND_ENV}" ]]; then
        grep -q "^DB_URL=" "${BACKEND_ENV}" && DB_URL=$(grep "^DB_URL=" "${BACKEND_ENV}" | cut -d= -f2-)
        grep -q "^DB_USER=" "${BACKEND_ENV}" && DB_USER=$(grep "^DB_USER=" "${BACKEND_ENV}" | cut -d= -f2-)
        grep -q "^DB_PASSWORD=" "${BACKEND_ENV}" && DB_PASSWORD=$(grep "^DB_PASSWORD=" "${BACKEND_ENV}" | cut -d= -f2-)
    fi

    cat > "${APN_ENV}" << EOF
SPRING_PROFILES_ACTIVE=prod
PORT=8081
DB_URL=${DB_URL}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
APN_API_URL=http://10.55.245.100/api/dmc/send-apn
APN_API_KEY=AIzaSyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe
APN_CONNECT_TIMEOUT_MS=2000
APN_READ_TIMEOUT_MS=9000
APN_MAX_CONNECTIONS=50
APN_RATE_LIMIT_TPS=18
APN_RATE_LIMIT_TIMEOUT_MS=100
APN_MAX_CONCURRENT_CALLS=25
EOF
    chmod 600 "${APN_ENV}"
    chown kraken-app:kraken-app "${APN_ENV}"
fi

# Ensure systemd service exists
SERVICE_FILE="/etc/systemd/system/kraken-apn.service"
if [[ ! -f "${SERVICE_FILE}" ]]; then
    JAVA_BIN=$(command -v java || echo "/usr/bin/java")
    cat > "${SERVICE_FILE}" << EOF
[Unit]
Description=Kraken APN Gateway (Spring Boot)
After=network.target postgresql.service
Requires=postgresql.service

[Service]
Type=simple
User=kraken-app
Group=kraken-app
EnvironmentFile=/opt/kraken/apn/config/kraken-apn.env
WorkingDirectory=/opt/kraken/apn

ExecStart=${JAVA_BIN} \\
  -Xmx512m -Xms256m \\
  -Djava.security.egd=file:/dev/./urandom \\
  -jar /opt/kraken/apn/target/kraken-apn.jar

StandardOutput=append:/var/log/ussd/apn-stdout.log
StandardError=append:/var/log/ussd/apn-stderr.log

Restart=always
RestartSec=5
StartLimitIntervalSec=60
StartLimitBurst=5

NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/log/ussd
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable kraken-apn
fi

info "Starting kraken-apn..."
systemctl start kraken-apn

APN_OK=false
for i in {1..20}; do
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health 2>/dev/null || echo "000")
    if [[ "${HTTP_STATUS}" == "200" ]]; then
        APN_OK=true
        break
    fi
    echo -n "."
    sleep 2
done
echo ""

if $APN_OK; then
    ok "Kraken APN Gateway rebuilt and healthy on port 8081 (HTTP 200) ✅"
else
    journalctl -u kraken-apn -n 25 --no-pager 2>/dev/null || true
    err "Kraken APN Gateway not responding. Check logs above: sudo journalctl -u kraken-apn -n 50"
fi
