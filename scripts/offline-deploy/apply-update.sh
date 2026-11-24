#!/usr/bin/env bash
#
# apply-update.sh — Apply rebuilt artifacts on the OFFLINE PRODUCTION SERVER
#
# Copy this script to the server once. After that, just scp the new artifact(s)
# and run this script to deploy them.
#
# Usage:
#   sudo bash apply-update.sh backend    # applies /tmp/kraken-backend.jar
#   sudo bash apply-update.sh apn        # applies /tmp/kraken-apn.jar
#   sudo bash apply-update.sh frontend   # applies /tmp/kraken-frontend-standalone.tar.gz
#   sudo bash apply-update.sh both       # applies backend, apn and frontend
#   sudo bash apply-update.sh all        # same as both
#
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

[[ $EUID -ne 0 ]] && err "Run as root: sudo bash $0 [backend|apn|frontend|both|all]"

TARGET="${1:-both}"
[[ "${TARGET}" =~ ^(backend|apn|frontend|both|all)$ ]] || \
    err "Invalid target '${TARGET}'. Use: backend | apn | frontend | both | all"

# Find Java binary
JAVA_BIN="/usr/local/bin/java"
if [[ ! -x "${JAVA_BIN}" ]]; then
    JAVA_BIN=$(command -v java || echo "/usr/bin/java")
fi

# ═══════════════════════════════════════
# 1. KRAKEN APN GATEWAY (PORT 8081)
# ═══════════════════════════════════════
if [[ "${TARGET}" == "apn" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    APN_JAR="/tmp/kraken-apn.jar"
    if [[ -f "${APN_JAR}" ]]; then
        info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        info "Deploying Kraken APN Gateway (Port 8081)..."
        info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

        mkdir -p /opt/kraken/apn/target /opt/kraken/apn/config /var/log/ussd
        id kraken-app &>/dev/null || useradd --system --shell /usr/sbin/nologin --create-home kraken-app

        # Setup kraken-apn.env if not present
        APN_ENV="/opt/kraken/apn/config/kraken-apn.env"
        if [[ ! -f "${APN_ENV}" ]]; then
            info "Creating initial ${APN_ENV}..."
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
# Kraken APN Gateway Environment
SPRING_PROFILES_ACTIVE=prod
PORT=8081
DB_URL=${DB_URL}
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
APN_API_URL=http://10.55.245.100/api/dmc/send-apn
APN_API_KEY=AIzaSyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe
APN_CONNECT_TIMEOUT_MS=3000
APN_READ_TIMEOUT_MS=30000
APN_MAX_CONNECTIONS=150
APN_RATE_LIMIT_TPS=60
APN_RATE_LIMIT_TIMEOUT_MS=200
APN_MAX_CONCURRENT_CALLS=150
EOF
            chmod 600 "${APN_ENV}"
            chown kraken-app:kraken-app "${APN_ENV}"
            ok "APN environment created"
        fi

        # Setup kraken-apn.service if not present
        APN_SERVICE="/etc/systemd/system/kraken-apn.service"
        if [[ ! -f "${APN_SERVICE}" ]]; then
            info "Creating ${APN_SERVICE}..."
            cat > "${APN_SERVICE}" << EOF
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
            ok "APN systemd service installed and enabled"
        fi

        info "Stopping kraken-apn..."
        systemctl stop kraken-apn 2>/dev/null || true

        info "Deploying new APN JAR..."
        cp "${APN_JAR}" /opt/kraken/apn/target/kraken-apn.jar
        chown kraken-app:kraken-app /opt/kraken/apn/target/kraken-apn.jar
        chown -R kraken-app:kraken-app /opt/kraken/apn /var/log/ussd 2>/dev/null || true
        rm -f "${APN_JAR}"

        info "Starting kraken-apn service..."
        systemctl start kraken-apn

        APN_OK=false
        for i in {1..20}; do
            HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/actuator/health 2>/dev/null || echo "000")
            if [[ "${HTTP_STATUS}" == "200" ]]; then
                APN_OK=true
                break
            fi
            if ! systemctl is-active --quiet kraken-apn && [[ $i -gt 3 ]]; then
                echo -e "\n[!] kraken-apn service stopped or failed unexpectedly."
                break
            fi
            echo -n "."
            sleep 2
        done
        echo ""

        if $APN_OK; then
            ok "Kraken APN Gateway updated and healthy on port 8081 (HTTP 200) ✅"
        else
            warn "Kraken APN Gateway check returned ${HTTP_STATUS}. Check logs: journalctl -u kraken-apn -n 25"
        fi

        # Verify Nginx routing for /api/v1/ussd/
        NGINX_CONF="/etc/nginx/sites-available/kraken"
        if [[ -f "${NGINX_CONF}" ]]; then
            NEED_NGINX_RELOAD=false
            if grep -q "proxy_pass http://127.0.0.1:8080/api/v1/ussd/;" "${NGINX_CONF}"; then
                info "Updating Nginx /api/v1/ussd/ route from port 8080 to 8081..."
                sed -i 's|proxy_pass http://127.0.0.1:8080/api/v1/ussd/;|proxy_pass http://127.0.0.1:8081/api/v1/ussd/;|' "${NGINX_CONF}"
                NEED_NGINX_RELOAD=true
            fi
            if grep -A 8 "location /api/v1/ussd/" "${NGINX_CONF}" | grep -q "proxy_read_timeout 15s;"; then
                info "Updating Nginx USSD timeout from 15s to 35s..."
                sed -i '/location \/api\/v1\/ussd\//,/}/ s/proxy_read_timeout 15s;/proxy_read_timeout 35s;/' "${NGINX_CONF}"
                sed -i '/location \/api\/v1\/ussd\//,/}/ s/proxy_send_timeout 15s;/proxy_send_timeout 35s;/' "${NGINX_CONF}"
                NEED_NGINX_RELOAD=true
            fi
            if $NEED_NGINX_RELOAD; then
                if nginx -t 2>/dev/null; then
                    systemctl reload nginx
                    ok "Nginx reloaded: /api/v1/ussd/ is now routed to Kraken APN (port 8081, 35s timeout) ✅"
                else
                    warn "Nginx config test failed after editing. Please verify ${NGINX_CONF}"
                fi
            fi
        fi
    elif [[ "${TARGET}" == "apn" ]]; then
        err "Missing: ${APN_JAR} — copy it first with scp"
    fi
fi

# ═══════════════════════════════════════
# 2. BACKEND (Kraken ITDS - PORT 8080)
# ═══════════════════════════════════════
if [[ "${TARGET}" == "backend" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    JAR="/tmp/kraken-backend.jar"
    if [[ -f "${JAR}" ]]; then
        info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        info "Deploying Kraken Backend (Port 8080)..."
        info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

        SERVICE_FILE="/etc/systemd/system/kraken-backend.service"
        if [[ -f "${SERVICE_FILE}" ]]; then
            NEED_RELOAD=false
            if grep -q "ReadWritePaths=" "${SERVICE_FILE}" && ! grep -q "/mediation" "${SERVICE_FILE}"; then
                info "Adding /mediation to ReadWritePaths in ${SERVICE_FILE}..."
                sed -i 's|ReadWritePaths=\(.*\)|ReadWritePaths=\1 /mediation|' "${SERVICE_FILE}"
                NEED_RELOAD=true
            fi
            if ! grep -q "TimeoutStartSec" "${SERVICE_FILE}"; then
                info "Adding TimeoutStartSec=600 to ${SERVICE_FILE}..."
                sed -i '/\[Service\]/a TimeoutStartSec=600\nTimeoutStopSec=180' "${SERVICE_FILE}"
                NEED_RELOAD=true
            fi
            if $NEED_RELOAD; then
                systemctl daemon-reload
            fi
        fi

        info "Stopping backend..."
        systemctl stop kraken-backend 2>/dev/null || true

        # Terminate any orphaned postgres sessions for kraken_db if backend is stopped
        if command -v su &>/dev/null && id -u postgres &>/dev/null; then
            su - postgres -c "psql -d kraken_db -c \"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND datname = 'kraken_db' AND application_name ILIKE '%PostgreSQL JDBC Driver%' AND state = 'idle in transaction';\"" 2>/dev/null || true
        fi

        # Run V46 pre-deployment storage optimization if available
        PRE_V46_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/run-v46-pre-deploy.sh"
        if [[ -f "${PRE_V46_SCRIPT}" ]]; then
            info "Running pre-deployment storage optimization (V46)..."
            bash "${PRE_V46_SCRIPT}" || warn "V46 pre-migration script returned non-zero; continuing deployment..."
        fi

        info "Deploying new Backend JAR..."
        mkdir -p /opt/kraken/backend/target /var/log/ussd/exports /mediation
        cp "${JAR}" /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar
        chown kraken-app:kraken-app /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar
        chown -R kraken-app:kraken-app /mediation 2>/dev/null || true
        chmod -R 777 /mediation 2>/dev/null || true
        if command -v setfacl &>/dev/null; then
            setfacl -R -d -m u:kraken-app:rwx /mediation 2>/dev/null || true
            setfacl -R -m u:kraken-app:rwx /mediation 2>/dev/null || true
        fi
        chown -R kraken-app:kraken-app /var/log/ussd 2>/dev/null || true
        rm -f "${JAR}"

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
            HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null || echo "000")
            if [[ "${HTTP_STATUS}" == "200" || "${HTTP_STATUS}" == "503" ]]; then
                BACKEND_OK=true
                break
            fi
            if ! systemctl is-active --quiet kraken-backend && [[ $i -gt 3 ]]; then
                echo -e "\n[!] Service stopped or failed unexpectedly."
                break
            fi
            echo -n "."
            sleep 5
        done
        echo ""
        if $BACKEND_OK; then
            ok "Backend updated and healthy (HTTP ${HTTP_STATUS}) ✅"
        else
            echo -e "\n${RED}═════════════════ BACKEND FAILURE LOGS ═════════════════${NC}"
            if [[ -f /var/log/ussd/backend-stdout.log ]]; then
                echo -e "${CYAN}--- /var/log/ussd/backend-stdout.log (last 30 lines): ---${NC}"
                tail -n 30 /var/log/ussd/backend-stdout.log 2>/dev/null || true
            fi
            if [[ -f /var/log/ussd/backend-stderr.log ]]; then
                echo -e "\n${CYAN}--- /var/log/ussd/backend-stderr.log (last 30 lines): ---${NC}"
                tail -n 30 /var/log/ussd/backend-stderr.log 2>/dev/null || true
            fi
            echo -e "\n${CYAN}--- journalctl -u kraken-backend (last 25 lines): ---${NC}"
            journalctl -u kraken-backend -n 25 --no-pager 2>/dev/null || true
            echo -e "${RED}════════════════════════════════════════════════════════${NC}\n"
            err "Backend not healthy. See detailed logs above."
        fi
    elif [[ "${TARGET}" == "backend" ]]; then
        err "Missing: ${JAR} — copy it first with scp"
    fi
fi

# ═══════════════════════════════════════
# 3. FRONTEND (PORT 3000)
# ═══════════════════════════════════════
if [[ "${TARGET}" == "frontend" || "${TARGET}" == "both" || "${TARGET}" == "all" ]]; then
    TAR="/tmp/kraken-frontend-standalone.tar.gz"
    if [[ -f "${TAR}" ]]; then
        info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        info "Deploying Kraken Frontend (Port 3000)..."
        info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

        info "Stopping frontend..."
        systemctl stop kraken-frontend 2>/dev/null || true

        info "Deploying new frontend..."
        rm -rf /opt/kraken/frontend
        mkdir -p /opt/kraken/frontend
        tar -xzf "${TAR}" -C /opt/kraken/frontend/
        chown -R nextjs-app:nextjs-app /opt/kraken/frontend
        rm -f "${TAR}"

        info "Starting frontend..."
        systemctl start kraken-frontend
        sleep 4

        if curl -sf -o /dev/null http://localhost:3000; then
            ok "Frontend updated and running ✅"
        else
            err "Frontend not responding. Check: sudo journalctl -u kraken-frontend -n 30"
        fi
    elif [[ "${TARGET}" == "frontend" ]]; then
        err "Missing: ${TAR} — copy it first with scp"
    fi
fi
