#!/usr/bin/env bash
#
# deploy-local.sh — Local Deployment for Fedora Workstation 44
# ══════════════════════════════════════════════════════════════════════
# Installs and deploys Kraken on the LOCAL machine with internet access.
# No Docker, no offline bundle, no tarball — just dnf install + build + run.
#
# Usage:
#   sudo bash scripts/local-deploy/deploy-local.sh [SERVER_IP]
#
# If SERVER_IP is not provided, defaults to the machine's primary IP.
#
set -euo pipefail

# ─── Colors ───
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }
step()  { echo -e "\n${BOLD}${CYAN}═══ $* ═══${NC}"; }

# ─── Validate root ───
if [[ $EUID -ne 0 ]]; then
    err "This script must be run as root. Use: sudo bash $0 [SERVER_IP]"
fi

# ─── Validate OS ───
if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    if [[ "${ID:-}" != "fedora" ]]; then
        warn "This installer is optimized for Fedora. Detected: ${PRETTY_NAME:-unknown} — proceeding anyway..."
    else
        info "Detected: ${PRETTY_NAME:-Fedora}"
    fi
else
    warn "Cannot detect OS version — proceeding anyway"
fi

# ─── Detect or use provided SERVER_IP ───
if [[ $# -ge 1 ]]; then
    SERVER_IP="$1"
else
    SERVER_IP=$(hostname -I | awk '{print $1}')
    if [[ -z "${SERVER_IP}" ]]; then
        SERVER_IP="127.0.0.1"
    fi
    warn "No IP provided, auto-detected: ${SERVER_IP}"
fi

# ─── Find project root (go up from scripts/local-deploy/) ───
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

info "Server IP:    ${SERVER_IP}"
info "Project root: ${PROJECT_ROOT}"
echo ""

# ─── Verify project structure ───
[[ -f "${PROJECT_ROOT}/backend/pom.xml" ]]       || err "Missing: backend/pom.xml"
[[ -f "${PROJECT_ROOT}/frontend/package.json" ]]  || err "Missing: frontend/package.json"

echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}  KRAKEN LOCAL INSTALLER — Fedora Workstation 44   ${NC}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "  This will install/configure:"
echo -e "    • PostgreSQL 17       (port 5432)"
echo -e "    • Redis 7+            (port 6379)"
echo -e "    • Nginx               (port 80)"
echo -e "    • OpenJDK (Existing)  (JDK)"
echo -e "    • Node.js 20 LTS"
echo -e "    • Kraken Backend      (port 8080)"
echo -e "    • Kraken APN Gateway  (port 8081)"
echo -e "    • Kraken Frontend     (port 3000)"
echo ""

if [[ "${KRAKEN_AUTO_CONFIRM:-}" != "yes" ]]; then
    if [[ -t 0 ]]; then
        echo -ne "${YELLOW}Proceed with installation? [Y/n]: ${NC}"
        read -r CONFIRM
        CONFIRM="${CONFIRM:-y}"
        if [[ "${CONFIRM}" != "y" && "${CONFIRM}" != "Y" ]]; then
            info "Installation cancelled."
            exit 0
        fi
    else
        info "Non-interactive terminal detected — proceeding with installation..."
    fi
fi
echo ""

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 0: Stop any running Kraken services          ║
# ╚═══════════════════════════════════════════════════╝
info "Stopping any existing Kraken services before rebuild..."
systemctl stop kraken-backend kraken-apn kraken-frontend 2>/dev/null || true
systemctl disable kraken-backend kraken-apn kraken-frontend 2>/dev/null || true
# Clear stale root-owned JAR so Maven (running as user) can clean properly
rm -f "${PROJECT_ROOT}/backend/target/kraken-1.0.0-SNAPSHOT.jar" "${PROJECT_ROOT}/kraken-apn/target/kraken-apn-*.jar" 2>/dev/null || true
ok "Pre-existing services stopped"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 1: Install system packages via dnf          ║
# ╚═══════════════════════════════════════════════════╝
step "Step 1/8: Installing system packages"

# Add Node.js 20 module if needed
if ! command -v node &>/dev/null; then
    info "Enabling Node.js 20 module..."
    dnf module enable -y nodejs:20 >/dev/null 2>&1 || true
fi

info "Updating package lists..."
dnf check-update -q || true

info "Installing packages (this may take a few minutes)..."
dnf install -y \
    postgresql-server \
    postgresql \
    postgresql-contrib \
    redis \
    nginx \
    nodejs \
    curl \
    wget

JAVA_BIN=$(command -v java)
NODE_BIN=$(command -v node)

ok "Java:       $(java -version 2>&1 | head -1)"
ok "Node.js:    $(node --version)"
ok "PostgreSQL: $(postgres -V 2>/dev/null || echo 'installed')"
ok "Redis:      $(redis-server --version | head -1)"
ok "Nginx:      $(nginx -v 2>&1)"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 2: Configure PostgreSQL                     ║
# ╚═══════════════════════════════════════════════════╝
step "Step 2/8: Configuring PostgreSQL"

# Initialize PostgreSQL if needed
if [[ ! -d /var/lib/pgsql/data/base ]]; then
    info "Initializing PostgreSQL database..."
    postgresql-setup --initdb
fi

# Start PostgreSQL
systemctl unmask postgresql 2>/dev/null || true
systemctl enable postgresql
systemctl start postgresql

# Wait for PG to be ready
for i in {1..10}; do
    sudo -u postgres pg_isready &>/dev/null && break
    sleep 2
done

PG_PORT=5432
PSQL="sudo -u postgres psql -p ${PG_PORT}"

# Create DB and user
$PSQL -tc "SELECT 1 FROM pg_roles WHERE rolname='kraken'" | grep -q 1 || \
    $PSQL -c "CREATE USER kraken WITH PASSWORD 'wqDV2fO/IpJZoL0JdFkjsNvG/QhKOMwl';"

$PSQL -tc "SELECT 1 FROM pg_database WHERE datname='kraken_db'" | grep -q 1 || \
    $PSQL -c "CREATE DATABASE kraken_db OWNER kraken;"

$PSQL -d kraken_db -c "GRANT ALL PRIVILEGES ON DATABASE kraken_db TO kraken;" 2>/dev/null || true
$PSQL -d kraken_db -c "GRANT ALL ON SCHEMA public TO kraken;" 2>/dev/null || true
$PSQL -d kraken_db -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO kraken;" 2>/dev/null || true
$PSQL -d kraken_db -c "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO kraken;" 2>/dev/null || true

# Configure pg_hba.conf
PG_HBA=$($PSQL -t -c "SHOW hba_file;" | xargs)
if [[ -n "${PG_HBA}" ]] && ! grep -q "kraken" "${PG_HBA}" 2>/dev/null; then
    cp "${PG_HBA}" "${PG_HBA}.bak.$(date +%Y%m%d%H%M%S)"
    if grep -q '^local\|^host' "${PG_HBA}" 2>/dev/null; then
        sed -i '0,/^\(local\|host\)/s//# Kraken application\nlocal   all       kraken                md5\nhost    all       kraken  127.0.0.1\/32  md5\nhost    all       kraken  ::1\/128       md5\n\n\1/' "${PG_HBA}"
    else
        printf '\n# Kraken application\nlocal   all       kraken                md5\nhost    all       kraken  127.0.0.1/32  md5\nhost    all       kraken  ::1/128       md5\n' >> "${PG_HBA}"
    fi
    systemctl restart postgresql
fi

ok "PostgreSQL configured (port ${PG_PORT})"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 3: Configure Redis                          ║
# ╚═══════════════════════════════════════════════════╝
step "Step 3/8: Configuring Redis/Valkey"

REDIS_PASSWORD='ItlfcC7mVCKg/V2FfnFmEogc68LfuN1m'

# Detect the service and config — Fedora 44 ships Valkey (Redis-compatible)
REDIS_SERVICE=""
REDIS_CONF=""
if systemctl list-unit-files valkey.service &>/dev/null; then
    REDIS_SERVICE="valkey"
    for f in /etc/valkey/valkey.conf /etc/valkey.conf; do
        [[ -f "$f" ]] && { REDIS_CONF="$f"; break; }
    done
elif systemctl list-unit-files redis.service &>/dev/null; then
    REDIS_SERVICE="redis"
    for f in /etc/redis/redis.conf /etc/redis.conf; do
        [[ -f "$f" ]] && { REDIS_CONF="$f"; break; }
    done
fi

if [[ -n "${REDIS_CONF}" ]]; then
    cp "${REDIS_CONF}" "${REDIS_CONF}.bak.$(date +%Y%m%d%H%M%S)"
    if grep -q '^# requirepass' "${REDIS_CONF}"; then
        sed -i 's/^# requirepass .*/requirepass ItlfcC7mVCKg\/V2FfnFmEogc68LfuN1m/' "${REDIS_CONF}"
    elif grep -q '^requirepass' "${REDIS_CONF}"; then
        sed -i 's/^requirepass .*/requirepass ItlfcC7mVCKg\/V2FfnFmEogc68LfuN1m/' "${REDIS_CONF}"
    else
        echo "requirepass ${REDIS_PASSWORD}" >> "${REDIS_CONF}"
    fi
    sed -i 's/^bind .*/bind 127.0.0.1 ::1/' "${REDIS_CONF}"

    systemctl restart "${REDIS_SERVICE}"
    systemctl enable  "${REDIS_SERVICE}"

    # Test with both redis-cli and valkey-cli
    REDIS_CLI=$(command -v valkey-cli 2>/dev/null || command -v redis-cli 2>/dev/null || echo "")
    if [[ -n "${REDIS_CLI}" ]] && ${REDIS_CLI} -a "${REDIS_PASSWORD}" ping 2>/dev/null | grep -q PONG; then
        ok "${REDIS_SERVICE} configured and running"
    else
        warn "${REDIS_SERVICE} installed but ping check failed (may still be starting)"
    fi
else
    # No config file — create a minimal one for Valkey
    if [[ "${REDIS_SERVICE}" == "valkey" ]]; then
        mkdir -p /etc/valkey
        REDIS_CONF="/etc/valkey/valkey.conf"
        cat > "${REDIS_CONF}" <<VALKEYCFG
bind 127.0.0.1 ::1
requirepass ${REDIS_PASSWORD}
VALKEYCFG
        systemctl enable  valkey 2>/dev/null || true
        systemctl restart valkey 2>/dev/null || true
        REDIS_CLI=$(command -v valkey-cli 2>/dev/null || command -v redis-cli 2>/dev/null || echo "")
        if [[ -n "${REDIS_CLI}" ]] && ${REDIS_CLI} -a "${REDIS_PASSWORD}" ping 2>/dev/null | grep -q PONG; then
            ok "Valkey configured and running"
        else
            warn "Valkey may still be starting"
        fi
    elif [[ -n "${REDIS_SERVICE}" ]]; then
        systemctl enable  "${REDIS_SERVICE}" 2>/dev/null || true
        systemctl restart "${REDIS_SERVICE}" 2>/dev/null || true
        ok "${REDIS_SERVICE} started (using defaults)"
    else
        warn "Neither Redis nor Valkey service found"
    fi
fi

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 4: Build Backend                            ║
# ╚═══════════════════════════════════════════════════╝
step "Step 4/8: Building Backend"

cd "${PROJECT_ROOT}/backend"
chmod +x mvnw

# Run as the calling user (not root) for Maven
CALLING_USER="${SUDO_USER:-$(logname 2>/dev/null || echo root)}"
info "Building as user: ${CALLING_USER}"
chown -R "${CALLING_USER}:${CALLING_USER}" "${PROJECT_ROOT}/backend" 2>/dev/null || true
sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/backend' && ./mvnw clean package -DskipTests"

JAR_FILE=$(ls target/kraken-*.jar 2>/dev/null | grep -v original | head -1)
[[ -z "${JAR_FILE}" ]] && err "Backend JAR build failed"

mkdir -p /opt/kraken/backend/target /opt/kraken/backend/config /var/log/ussd/exports /mediation
cp "${JAR_FILE}" /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar
ok "Backend JAR built and deployed"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 4b: Build Kraken APN Gateway (Port 8081)    ║
# ╚═══════════════════════════════════════════════════╝
step "Step 4b/8: Building Kraken APN Gateway"

cd "${PROJECT_ROOT}/kraken-apn"
chmod +x mvnw
chown -R "${CALLING_USER}:${CALLING_USER}" "${PROJECT_ROOT}/kraken-apn" 2>/dev/null || true
sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/kraken-apn' && ./mvnw clean package -DskipTests"

APN_JAR=$(ls target/kraken-apn-*.jar 2>/dev/null | grep -v original | head -1)
[[ -z "${APN_JAR}" ]] && err "APN Gateway JAR build failed"

mkdir -p /opt/kraken/apn/target /opt/kraken/apn/config
cp "${APN_JAR}" /opt/kraken/apn/target/kraken-apn.jar
ok "APN Gateway JAR built and deployed"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 5: Build Frontend                           ║
# ╚═══════════════════════════════════════════════════╝
step "Step 5/8: Building Frontend"

cd "${PROJECT_ROOT}/frontend"
chown -R "${CALLING_USER}:${CALLING_USER}" "${PROJECT_ROOT}/frontend" 2>/dev/null || true

sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/frontend' && npm ci"
sudo -u "${CALLING_USER}" bash -c "cd '${PROJECT_ROOT}/frontend' && NEXT_PUBLIC_API_URL='/api/v1' npm run build"

STANDALONE_DIR="${PROJECT_ROOT}/frontend/.next/standalone"
[[ ! -d "${STANDALONE_DIR}" ]] && err "Frontend standalone build failed. Is 'output: standalone' in next.config.ts?"

# Wipe the previous deployment's output first. Without this, `cp -r` nests the new
# .next/static directory inside the pre-existing one (since the destination already
# exists), leaving the server referencing chunk paths that don't actually exist on
# disk and causing ChunkLoadError / MIME-type-mismatch errors in the browser.
rm -rf /opt/kraken/frontend
mkdir -p /opt/kraken/frontend
cp -r "${STANDALONE_DIR}/." /opt/kraken/frontend/
cp -r "${PROJECT_ROOT}/frontend/public" /opt/kraken/frontend/public 2>/dev/null || true
mkdir -p /opt/kraken/frontend/.next
cp -r "${PROJECT_ROOT}/frontend/.next/static" /opt/kraken/frontend/.next/ 2>/dev/null || true

ok "Frontend built and deployed"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 6: Create system users & services           ║
# ╚═══════════════════════════════════════════════════╝
step "Step 6/8: Creating system users & services"

# Backend user
id kraken-app &>/dev/null || useradd --system --shell /usr/sbin/nologin --create-home kraken-app
mkdir -p /home/kraken-app/.ssh
chown -R kraken-app:kraken-app /home/kraken-app
chown -R kraken-app:kraken-app /var/log/ussd
chown -R kraken-app:kraken-app /opt/kraken/backend
chown -R kraken-app:kraken-app /mediation 2>/dev/null || true
chmod -R 775 /mediation 2>/dev/null || true

# Frontend user
id nextjs-app &>/dev/null || useradd --system --shell /usr/sbin/nologin nextjs-app
chown -R nextjs-app:nextjs-app /opt/kraken/frontend

# Backend env file
cat > /opt/kraken/backend/config/ussd-gateway.env << EOF
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://localhost:${PG_PORT}/kraken_db
DB_USER=kraken
DB_PASSWORD=wqDV2fO/IpJZoL0JdFkjsNvG/QhKOMwl
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=ItlfcC7mVCKg/V2FfnFmEogc68LfuN1m
JWT_SECRET=a8030653e0513f0b4655e4c44e22cad46458d761d77f0b45119866ed026b5940
ENCRYPTION_SECRET=5670da72c391bff9256bb4e255ffba74ce67ea93583f8272a187d59ab2975652
ADMIN_INITIAL_PASSWORD=Admin@Kraken2026!
FRONTEND_URL=http://${SERVER_IP}
USSD_LOG_BASE_PATH=/mediation
SSH_STRICT_HOST_KEY_CHECKING=accept-new
SSH_KNOWN_HOSTS_FILE=/home/kraken-app/.ssh/known_hosts
EOF
chmod 600 /opt/kraken/backend/config/ussd-gateway.env
chown kraken-app:kraken-app /opt/kraken/backend/config/ussd-gateway.env

# Backend systemd service
cat > /etc/systemd/system/kraken-backend.service << EOF
[Unit]
Description=Kraken Backend (Spring Boot)
After=network.target postgresql.service redis.service
Requires=postgresql.service redis.service
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
User=kraken-app
Group=kraken-app
EnvironmentFile=/opt/kraken/backend/config/ussd-gateway.env
WorkingDirectory=/opt/kraken/backend

ExecStart=${JAVA_BIN} \\
  -XX:MaxRAMPercentage=75.0 \\
  -Djava.security.egd=file:/dev/./urandom \\
  -jar /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar

StandardOutput=append:/var/log/ussd/backend-stdout.log
StandardError=append:/var/log/ussd/backend-stderr.log

TimeoutStartSec=600
TimeoutStopSec=180

Restart=on-failure
RestartSec=10

NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/var/log/ussd /mediation /home/kraken-app/.ssh
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# Frontend systemd service
cat > /etc/systemd/system/kraken-frontend.service << EOF
[Unit]
Description=Kraken Frontend (Next.js)
After=network.target kraken-backend.service
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=nextjs-app
Group=nextjs-app

Environment=NODE_ENV=production
Environment=PORT=3000
Environment=HOSTNAME=0.0.0.0
Environment=NEXT_PUBLIC_API_URL=/api/v1

WorkingDirectory=/opt/kraken/frontend
ExecStart=${NODE_BIN} server.js

StandardOutput=append:/var/log/ussd/frontend-stdout.log
StandardError=append:/var/log/ussd/frontend-stderr.log

Restart=on-failure
RestartSec=5

NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/log/ussd
ReadOnlyPaths=/opt/kraken/frontend
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# APN env file
cat > /opt/kraken/apn/config/kraken-apn.env << EOF
SPRING_PROFILES_ACTIVE=prod
PORT=8081
DB_URL=jdbc:postgresql://localhost:${PG_PORT}/kraken_db
DB_USER=kraken
DB_PASSWORD=wqDV2fO/IpJZoL0JdFkjsNvG/QhKOMwl
APN_API_URL=http://10.55.245.100/api/dmc/send-apn
APN_API_KEY=AIzaSyDaGmWKa4JsXZ-HjGw7ISLn_3namBGewQe
APN_CONNECT_TIMEOUT_MS=2000
APN_READ_TIMEOUT_MS=9000
APN_MAX_CONNECTIONS=50
APN_RATE_LIMIT_TPS=18
APN_RATE_LIMIT_TIMEOUT_MS=100
APN_MAX_CONCURRENT_CALLS=25
EOF
chmod 600 /opt/kraken/apn/config/kraken-apn.env
chown kraken-app:kraken-app /opt/kraken/apn/config/kraken-apn.env

# APN systemd service
cat > /etc/systemd/system/kraken-apn.service << EOF
[Unit]
Description=Kraken APN Gateway (Spring Boot)
After=network.target postgresql.service
Requires=postgresql.service
StartLimitIntervalSec=60
StartLimitBurst=5

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

NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/log/ussd
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable kraken-backend kraken-apn kraken-frontend
ok "System users and services created"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 7: Configure Nginx                          ║
# ╚═══════════════════════════════════════════════════╝
step "Step 7/8: Configuring Nginx"

cat > /etc/nginx/conf.d/kraken.conf << NGINX
limit_req_zone \$binary_remote_addr zone=api_limit:10m rate=30r/s;

server {
    listen 80 default_server;
    server_name _;

    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "origin-when-cross-origin" always;

    client_max_body_size 50M;

    # Reports and Excel streaming export — disable buffering and extend timeout for large datasets
    location /api/v1/reports/ {
        proxy_pass http://127.0.0.1:8080/api/v1/reports/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_http_version 1.1;
        proxy_buffering off;
        chunked_transfer_encoding on;
        proxy_connect_timeout 10s;
        proxy_send_timeout 600s;
        proxy_read_timeout 600s;
    }

    # Direct real-time USSD APN traffic — bypass general API rate limiting
    location /api/v1/ussd/ {
        proxy_pass http://127.0.0.1:8081/api/v1/ussd/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_connect_timeout 5s;
        proxy_send_timeout 35s;
        proxy_read_timeout 35s;
    }

    # Live trace SSE stream — must not be buffered or events arrive late
    location /api/v1/logs/live-trace/stream {
        proxy_pass http://127.0.0.1:8080/api/v1/logs/live-trace/stream;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_set_header Connection "";
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_cache off;
        gzip off;
        chunked_transfer_encoding on;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location /api/ {
        limit_req zone=api_limit burst=20 nodelay;
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 120s;
        proxy_read_timeout 120s;
    }

    location /actuator/ {
        proxy_pass http://127.0.0.1:8080/actuator/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        allow 127.0.0.1;
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
    }

    location /swagger-ui/ {
        proxy_pass http://127.0.0.1:8080/swagger-ui/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }

    location /v3/api-docs {
        proxy_pass http://127.0.0.1:8080/v3/api-docs;
        proxy_set_header Host \$host;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
NGINX



# No symlinks needed in Fedora, configs are directly loaded from conf.d

if command -v setsebool &>/dev/null; then
    info "Configuring SELinux to allow Nginx network connections..."
    setsebool -P httpd_can_network_connect 1 2>/dev/null || true
fi

nginx -t 2>&1 || err "Nginx config test failed"
systemctl restart nginx
systemctl enable nginx
ok "Nginx configured"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 8: Start services & verify                  ║
# ╚═══════════════════════════════════════════════════╝
step "Step 8/8: Starting all services"

# Log rotation
cat > /etc/logrotate.d/kraken << 'LOGROTATE'
/var/log/ussd/*.log {
    daily
    missingok
    rotate 14
    compress
    delaycompress
    notifempty
    copytruncate
}
LOGROTATE

info "Starting backend..."
systemctl start kraken-backend

info "Waiting for backend to become healthy (up to 90s)..."
BACKEND_OK=false
for i in {1..18}; do
    if curl -sf http://localhost:8080/actuator/health &>/dev/null; then
        BACKEND_OK=true
        break
    fi
    echo -n "."
    sleep 5
done
echo ""

if $BACKEND_OK; then
    ok "Backend is healthy"
else
    warn "Backend not yet healthy after 90s — may still be running migrations"
fi

info "Starting APN Gateway..."
systemctl start kraken-apn

info "Starting frontend..."
systemctl start kraken-frontend
sleep 5

# ─── Final status ───
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}              SERVICE STATUS REPORT               ${NC}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

check_service() {
    local name="$1" service="$2"
    if systemctl is-active --quiet "${service}" 2>/dev/null; then
        echo -e "  ${GREEN}●${NC} ${name}: ${GREEN}active${NC}"
    else
        echo -e "  ${RED}●${NC} ${name}: ${RED}inactive${NC}"
    fi
}

check_service "PostgreSQL " "postgresql"
# Check whichever Redis-compatible service is present
if systemctl list-unit-files valkey.service &>/dev/null 2>&1; then
    check_service "Valkey     " "valkey"
else
    check_service "Redis      " "redis"
fi
check_service "Backend    " "kraken-backend"
check_service "APN Gateway" "kraken-apn"
check_service "Frontend   " "kraken-frontend"
check_service "Nginx      " "nginx"

echo ""
FRONTEND_OK=false
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null || echo "000")
if [[ "${HTTP_CODE}" == "200" || "${HTTP_CODE}" == "307" || "${HTTP_CODE}" == "302" ]]; then
    FRONTEND_OK=true
    echo -e "  ${GREEN}✓${NC} Frontend: PASSED (HTTP ${HTTP_CODE})"
else
    echo -e "  ${RED}✗${NC} Frontend: FAILED (HTTP ${HTTP_CODE})"
fi

if $BACKEND_OK; then
    echo -e "  ${GREEN}✓${NC} Backend:  PASSED"
else
    echo -e "  ${RED}✗${NC} Backend:  FAILED (may still be starting)"
fi

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

if $BACKEND_OK && $FRONTEND_OK; then
    echo ""
    echo -e "${GREEN}${BOLD}  ✅ DEPLOYMENT SUCCESSFUL!${NC}"
    echo ""
    echo -e "  Access the application:"
    echo -e "    ${CYAN}http://${SERVER_IP}${NC}              — Frontend"
    echo -e "    ${CYAN}http://${SERVER_IP}/api/v1${NC}       — Backend API"
    echo -e "    ${CYAN}http://${SERVER_IP}/swagger-ui/${NC}  — API Docs"
    echo ""
    echo -e "  Default login:  admin / Admin@Kraken2026!"
else
    echo ""
    echo -e "${YELLOW}${BOLD}  ⚠️  Some services may still be starting.${NC}"
    echo -e "  Check logs:"
    echo -e "    sudo journalctl -u kraken-backend -f"
    echo -e "    sudo journalctl -u kraken-frontend -f"
fi

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
info "Useful commands:"
info "  sudo systemctl status kraken-backend"
info "  sudo systemctl status kraken-frontend"
info "  sudo systemctl restart kraken-backend"
info "  sudo systemctl restart kraken-frontend"
info "  sudo tail -f /var/log/ussd/backend-stdout.log"
info "  sudo tail -f /var/log/ussd/frontend-stdout.log"

