#!/usr/bin/env bash
#
# install-on-server.sh — Ubuntu 22.04 (Jammy) Offline Installer
# ═══════════════════════════════════════════════════════════════
# Installs the entire Kraken platform on an AIR-GAPPED Ubuntu 22.04 server.
# No internet connection required — all packages are pre-downloaded.
#
# This script is copied INTO the bundle tarball by create-bundle.sh
# and is meant to be run ON THE PRODUCTION SERVER.
#
# Prerequisites:
#   - Ubuntu 22.04 LTS (jammy)
#   - The bundle tarball has been extracted to the current directory
#   - Run as root (sudo)
#
# Usage:
#   sudo bash install-on-server.sh <SERVER_IP>
#
# Example:
#   cd /tmp && tar -xzf kraken-bundle-jammy.tar.gz
#   sudo bash install-on-server.sh 192.168.1.100
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
    err "This script must be run as root. Use: sudo bash $0 <SERVER_IP>"
fi

# ─── Validate Ubuntu 22.04 ───
if [[ -f /etc/os-release ]]; then
    . /etc/os-release
    if [[ "${VERSION_ID:-}" != "22.04" ]]; then
        err "This installer targets Ubuntu 22.04 only. Detected: ${PRETTY_NAME:-unknown}"
    fi
else
    warn "Cannot detect OS version — proceeding anyway"
fi

# ─── Validate server IP ───
if [[ $# -lt 1 ]]; then
    if [[ -f "SERVER_IP.txt" ]]; then
        SERVER_IP=$(cat SERVER_IP.txt)
        warn "No IP provided, using SERVER_IP.txt: ${SERVER_IP}"
    else
        err "Usage: sudo bash $0 <SERVER_IP>
Example: sudo bash $0 192.168.1.100"
    fi
else
    SERVER_IP="$1"
fi

BUNDLE_DIR="$(pwd)"

info "Server IP:  ${SERVER_IP}"
info "Bundle dir: ${BUNDLE_DIR}"
info "Target OS:  Ubuntu 22.04 (jammy)"
echo ""

# ─── Verify bundle contents ───
[[ -f "kraken-backend.jar" ]]                || err "Missing: kraken-backend.jar"
[[ -f "kraken-frontend-standalone.tar.gz" ]] || err "Missing: kraken-frontend-standalone.tar.gz"

HAS_DEBS=false
HAS_BINARIES=false
[[ -d "debs-jammy" ]] && HAS_DEBS=true
[[ -d "binaries" ]]   && HAS_BINARIES=true

if ! $HAS_DEBS && ! $HAS_BINARIES; then
    err "No packages found. Need either debs-jammy/ or binaries/ directory."
fi

ok "Bundle verified"

# ╔═══════════════════════════════════════════════════╗
# ║  PRE-FLIGHT SAFETY CHECKS                        ║
# ╚═══════════════════════════════════════════════════╝
step "Pre-flight checks"

# ── Disk space check (require at least 3 GB free on /) ──
AVAIL_KB=$(df --output=avail / | tail -1 | tr -d ' ')
AVAIL_GB=$((AVAIL_KB / 1024 / 1024))
if [[ "${AVAIL_KB}" -lt 3145728 ]]; then
    err "Insufficient disk space. Need at least 3 GB free on /. Available: ${AVAIL_GB} GB"
fi
ok "Disk space: ${AVAIL_GB} GB available"

# ── Port conflict check ──
PORTS_IN_USE=()
for PORT in 80 3000 5432 6379 8080; do
    if ss -tlnp 2>/dev/null | grep -q ":${PORT} "; then
        PROC=$(ss -tlnp 2>/dev/null | grep ":${PORT} " | awk '{print $NF}' | head -1)
        PORTS_IN_USE+=("${PORT} (${PROC})")
    fi
done

if [[ ${#PORTS_IN_USE[@]} -gt 0 ]]; then
    warn "The following ports are already in use:"
    for P in "${PORTS_IN_USE[@]}"; do
        warn "  - Port ${P}"
    done
    warn "These may be from a previous Kraken install (OK) or other software (risky)."
fi

# ── Detect existing services ──
EXISTING_SERVICES=()
set +e  # systemctl returns non-zero if service doesn't exist — don't exit
systemctl is-active --quiet postgresql 2>/dev/null  && EXISTING_SERVICES+=("PostgreSQL")
systemctl is-active --quiet redis-server 2>/dev/null && EXISTING_SERVICES+=("Redis")
systemctl is-active --quiet nginx 2>/dev/null        && EXISTING_SERVICES+=("Nginx")
set -e

if [[ ${#EXISTING_SERVICES[@]} -gt 0 ]]; then
    warn "Existing active services detected: ${EXISTING_SERVICES[*]}"
    warn "This installer will reconfigure them for Kraken."
fi

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}  KRAKEN OFFLINE INSTALLER — Ubuntu 22.04 Jammy  ${NC}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo -e "  This will install/configure:"
echo -e "    • PostgreSQL 15       (port 5432)"
echo -e "    • Redis 7+            (port 6379)"
echo -e "    • Nginx               (port 80)"
echo -e "    • Kraken Backend      (port 8080)"
echo -e "    • Kraken APN Gateway  (port 8081)"
echo -e "    • Kraken Frontend     (port 3000)"
echo ""

# ── Require explicit confirmation ──
if [[ "${KRAKEN_AUTO_CONFIRM:-}" != "yes" ]]; then
    echo -ne "${YELLOW}Proceed with installation? [y/N]: ${NC}"
    read -r CONFIRM
    if [[ "${CONFIRM}" != "y" && "${CONFIRM}" != "Y" ]]; then
        info "Installation cancelled by user."
        exit 0
    fi
fi
echo ""

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 1: Install .deb packages (if available)    ║
# ╚═══════════════════════════════════════════════════╝
if $HAS_DEBS; then
    step "Step 1/9: Installing .deb packages (offline)"

    cd "${BUNDLE_DIR}/debs-jammy"

    DEB_COUNT=$(ls -1 *.deb 2>/dev/null | wc -l)
    info "Installing ${DEB_COUNT} packages..."
    DPKG_LOG="${BUNDLE_DIR}/dpkg-install.log"

    # ── PASS 1: Force-unpack ALL .deb files, ignoring dependency order ──
    # dpkg -i processes files alphabetically which breaks dependency chains
    # (e.g. postgresql-client-common needs perl, but perl hasn't been
    # unpacked yet). Using --force-depends unpacks everything first.
    info "Pass 1: Unpacking all packages (--force-depends)..."
    dpkg -i --force-depends *.deb > "${DPKG_LOG}" 2>&1 || true

    # ── PASS 2: Configure everything in correct dependency order ──
    # Now that ALL files are on disk, dpkg --configure -a will process
    # packages in the right dependency order automatically.
    info "Pass 2: Configuring all packages (resolving dependencies)..."
    dpkg --configure -a >> "${DPKG_LOG}" 2>&1 || true

    # ── PASS 3: Let apt clean up any remaining broken deps ──
    apt-get -f install -y --no-download >> "${DPKG_LOG}" 2>&1 || true

    # ── PASS 4: Final configure pass to catch stragglers ──
    dpkg --configure -a >> "${DPKG_LOG}" 2>&1 || true

    # Check for any remaining broken packages
    BROKEN=$(dpkg -l 2>/dev/null | grep -c '^i[HU]' || true)
    if [[ "${BROKEN}" -gt 0 ]]; then
        warn "${BROKEN} packages still have issues — attempting one more fix..."
        dpkg -i --force-depends *.deb >> "${DPKG_LOG}" 2>&1 || true
        dpkg --configure -a >> "${DPKG_LOG}" 2>&1 || true
        BROKEN=$(dpkg -l 2>/dev/null | grep -c '^i[HU]' || true)
        if [[ "${BROKEN}" -gt 0 ]]; then
            warn "${BROKEN} packages have issues. Review: ${DPKG_LOG}"
        fi
    fi

    cd "${BUNDLE_DIR}"
    # Reload systemd after installing all .deb packages so it sees new service files
    systemctl daemon-reload
    ok "Packages installed from .deb files (log: ${DPKG_LOG})"

    # ── CRITICAL: Verify key packages are actually installed ('ii' state) ──
    # On minimal Ubuntu servers, dpkg -i --force-depends can leave packages
    # in 'rc' (removed) or 'iU'/'iF' (broken) states even after multiple
    # passes. Catch and fix this now, before any later step depends on them.
    #
    # IMPORTANT: perl-base MUST be first — the bundle's perl package requires
    # perl-base at the EXACT same version. If the production server has an
    # older perl-base, perl will fail with a dependency error.
    info "Verifying critical packages..."

    # Upgrade perl-base first if a newer version is in the bundle
    if compgen -G "${BUNDLE_DIR}/debs-jammy/perl-base"_*.deb >/dev/null 2>&1; then
        BUNDLE_PERLBASE_VER=$(dpkg-deb -f "${BUNDLE_DIR}"/debs-jammy/perl-base_*.deb Version 2>/dev/null || true)
        SYSTEM_PERLBASE_VER=$(dpkg -l perl-base 2>/dev/null | awk '/perl-base/{print $3}' || true)
        if [[ -n "${BUNDLE_PERLBASE_VER}" && "${BUNDLE_PERLBASE_VER}" != "${SYSTEM_PERLBASE_VER}" ]]; then
            info "Upgrading perl-base: ${SYSTEM_PERLBASE_VER} → ${BUNDLE_PERLBASE_VER}"
            dpkg -i "${BUNDLE_DIR}/debs-jammy/perl-base"_*.deb >> "${DPKG_LOG}" 2>&1 || true
        fi
    fi

    CRITICAL_PKGS=(
        "perl-modules-5.34"
        "libperl5.34"
        "perl"
        "ssl-cert"
        "locales"
        "postgresql-client-common"
        "postgresql-common"
        "postgresql-client-15"
        "postgresql-15"
        "postgresql-contrib-15"
        "redis-server"
        "nginx-core"
    )
    for CPKG in "${CRITICAL_PKGS[@]}"; do
        if ! dpkg -l "${CPKG}" 2>/dev/null | grep -q '^ii'; then
            STATE=$(dpkg -l "${CPKG}" 2>/dev/null | awk "/${CPKG}/{print \$1}" || echo "missing")
            warn "${CPKG} is in '${STATE}' state — force-reinstalling..."
            # Purge if in 'rc' state (dpkg -i can't overwrite 'rc')
            if [[ "${STATE}" == "rc" ]]; then
                dpkg --purge --force-depends "${CPKG}" 2>/dev/null || true
            fi
            if compgen -G "${BUNDLE_DIR}/debs-jammy/${CPKG}"_*.deb >/dev/null 2>&1; then
                dpkg -i --force-depends "${BUNDLE_DIR}/debs-jammy/${CPKG}"_*.deb >> "${DPKG_LOG}" 2>&1 || true
            fi
            dpkg --configure --force-depends "${CPKG}" >> "${DPKG_LOG}" 2>&1 || true
        fi
    done
    dpkg --configure -a >> "${DPKG_LOG}" 2>&1 || true

    # Hard check: perl MUST work (postgresql-common's postinst is a Perl script)
    if ! perl -e 'print "ok\n"' 2>/dev/null | grep -q ok; then
        err "perl is not functional after all install attempts.
  This is a critical dependency — everything else depends on it.
  Debug: dpkg -l perl libperl5.34 perl-modules-5.34
  Log:   ${DPKG_LOG}"
    fi
    ok "All critical packages verified"
fi

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 2: Install Java 21                         ║
# ╚═══════════════════════════════════════════════════╝
step "Step 2/9: Installing Java 21"

if command -v java &>/dev/null && java -version 2>&1 | grep -q '"2[1-9]\.\|"21\.'; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    ok "Java 21+ already installed: ${JAVA_VER}"
elif $HAS_BINARIES && [[ -f "binaries/temurin-21-jdk.tar.gz" ]]; then
    info "Installing Java 21 from binary tarball (deb install may have failed)..."

    mkdir -p /opt/java
    tar -xzf "binaries/temurin-21-jdk.tar.gz" -C /opt/java/
    JDK_DIR=$(ls -d /opt/java/jdk-21* 2>/dev/null | head -1)
    if [[ -z "${JDK_DIR}" ]]; then
        err "Failed to extract Java tarball"
    fi
    ln -sfn "${JDK_DIR}" /opt/java/temurin-21

    ln -sf /opt/java/temurin-21/bin/java    /usr/local/bin/java
    ln -sf /opt/java/temurin-21/bin/javac   /usr/local/bin/javac
    ln -sf /opt/java/temurin-21/bin/jar     /usr/local/bin/jar
    ln -sf /opt/java/temurin-21/bin/keytool /usr/local/bin/keytool

    cat > /etc/profile.d/java.sh << 'JAVAENV'
export JAVA_HOME=/opt/java/temurin-21
export PATH=$JAVA_HOME/bin:$PATH
JAVAENV
    export JAVA_HOME=/opt/java/temurin-21
    export PATH=$JAVA_HOME/bin:$PATH

    ok "Java 21 installed from tarball: $(java -version 2>&1 | head -1)"
elif command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1)
    warn "Java found but may not be version 21: ${JAVA_VER}"
else
    err "Java 21 not found. Provide either .deb packages or binaries/temurin-21-jdk.tar.gz"
fi

# Determine java path for systemd
JAVA_BIN=$(command -v java)
info "Java binary: ${JAVA_BIN}"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 3: Install Node.js 20                      ║
# ╚═══════════════════════════════════════════════════╝
step "Step 3/9: Installing Node.js 20"

if command -v node &>/dev/null; then
    ok "Node.js already installed: $(node --version)"
elif $HAS_BINARIES && [[ -f "binaries/node-v20-linux-x64.tar.xz" ]]; then
    info "Installing Node.js 20 from binary tarball..."

    mkdir -p /opt/nodejs
    tar -xJf "binaries/node-v20-linux-x64.tar.xz" -C /opt/nodejs/
    NODE_DIR=$(ls -d /opt/nodejs/node-v20* 2>/dev/null | head -1)
    if [[ -z "${NODE_DIR}" ]]; then
        err "Failed to extract Node.js tarball"
    fi
    ln -sfn "${NODE_DIR}" /opt/nodejs/node-20

    ln -sf /opt/nodejs/node-20/bin/node /usr/local/bin/node
    ln -sf /opt/nodejs/node-20/bin/npm  /usr/local/bin/npm
    ln -sf /opt/nodejs/node-20/bin/npx  /usr/local/bin/npx

    ok "Node.js installed from tarball: $(node --version)"
else
    err "Node.js 20 not found. Provide either .deb packages or binaries/node-v20-linux-x64.tar.xz"
fi

NODE_BIN=$(command -v node)
info "Node binary: ${NODE_BIN}"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 4: Configure PostgreSQL 15                 ║
# ╚═══════════════════════════════════════════════════╝
step "Step 4/9: Configuring PostgreSQL 15"

# ── 4a: Ensure all PostgreSQL packages are fully installed ──
# dpkg -i *.deb processes alphabetically, so postgresql-15 installs BEFORE
# postgresql-common. This causes post-install scripts to fail, leaving
# packages in broken states (config-files, half-installed, etc.).
# Fix: reinstall the PG packages in correct dependency order.
PG_NEEDS_REINSTALL=false

# Check if postgresql-common is NOT in 'ii' (fully installed) state
if ! dpkg -l postgresql-common 2>/dev/null | grep -q '^ii'; then
    PG_STATUS=$(dpkg -l postgresql-common 2>/dev/null | awk '/postgresql-common/{print $1}' || echo "missing")
    warn "postgresql-common is in '${PG_STATUS}' state (not installed properly)"
    PG_NEEDS_REINSTALL=true
fi

# Check if postgresql-15 is NOT in 'ii' state
if ! dpkg -l postgresql-15 2>/dev/null | grep -q '^ii'; then
    PG_STATUS=$(dpkg -l postgresql-15 2>/dev/null | awk '/postgresql-15/{print $1}' || echo "missing")
    warn "postgresql-15 is in '${PG_STATUS}' state (not installed properly)"
    PG_NEEDS_REINSTALL=true
fi

# Check if the systemd service file exists at all
if [[ ! -f /lib/systemd/system/postgresql.service ]] && \
   [[ ! -f /usr/lib/systemd/system/postgresql.service ]]; then
    warn "postgresql.service file not found on disk"
    PG_NEEDS_REINSTALL=true
fi

if $PG_NEEDS_REINSTALL; then
    info "Reinstalling PostgreSQL packages..."

    # ── First: ensure perl and ssl-cert are fully configured ──
    # postgresql-common's postinst script is written in Perl.
    # If perl isn't in 'ii' state, postgresql-common can NEVER configure.
    info "  Ensuring perl dependency chain is configured..."
    for DEP_PKG in libgdbm6 libgdbm-compat4 libperl5.34 perl-modules-5.34 perl ssl-cert locales; do
        if dpkg -l "${DEP_PKG}" 2>/dev/null | grep -q '^i[^i]'; then
            # Package is installed but NOT configured — configure it
            info "    Configuring ${DEP_PKG}..."
            dpkg --configure "${DEP_PKG}" 2>/dev/null || true
        elif ! dpkg -l "${DEP_PKG}" 2>/dev/null | grep -q '^ii'; then
            # Not installed at all — install from bundle
            if compgen -G "${BUNDLE_DIR}/debs-jammy/${DEP_PKG}"_*.deb >/dev/null 2>&1 || \
               compgen -G "${BUNDLE_DIR}/debs-jammy/${DEP_PKG}"[_-]*.deb >/dev/null 2>&1; then
                info "    Installing ${DEP_PKG} from bundle..."
                dpkg -i --force-depends "${BUNDLE_DIR}"/debs-jammy/${DEP_PKG}_*.deb 2>/dev/null || \
                dpkg -i --force-depends "${BUNDLE_DIR}"/debs-jammy/${DEP_PKG}-*.deb 2>/dev/null || true
            fi
        fi
    done
    dpkg --configure -a 2>/dev/null || true

    # Verify perl works
    if ! perl -e 'print "ok\n"' 2>/dev/null | grep -q ok; then
        warn "perl is not functional — attempting full reconfigure..."
        dpkg -i --force-depends "${BUNDLE_DIR}"/debs-jammy/libperl5*.deb 2>/dev/null || true
        dpkg -i --force-depends "${BUNDLE_DIR}"/debs-jammy/perl-modules*.deb 2>/dev/null || true
        dpkg -i --force-depends "${BUNDLE_DIR}"/debs-jammy/perl_*.deb 2>/dev/null || true
        dpkg --configure -a 2>/dev/null || true
        perl -e 'print "ok\n"' 2>/dev/null | grep -q ok || \
            err "perl is broken and cannot be fixed. Cannot continue.
  Debug: dpkg -l perl libperl5.34 perl-modules-5.34"
    fi
    ok "perl is functional"

    # ── Now purge and reinstall PG packages ──
    dpkg --purge --force-depends postgresql-15 2>/dev/null || true
    dpkg --purge --force-depends postgresql-client-15 2>/dev/null || true
    dpkg --purge --force-depends postgresql-common 2>/dev/null || true
    dpkg --purge --force-depends postgresql-client-common 2>/dev/null || true

    # Reinstall in dependency order WITH --force-depends
    PG_DEBS="${BUNDLE_DIR}/debs-jammy"
    for PKG in postgresql-client-common postgresql-common libpq5 postgresql-client-15 postgresql-15; do
        if compgen -G "${PG_DEBS}/${PKG}"_*.deb >/dev/null 2>&1; then
            info "  Installing ${PKG}..."
            dpkg -i --force-depends --force-confdef --force-confold "${PG_DEBS}/${PKG}"_*.deb || true
        fi
    done
    # Configure in correct dependency order now that all are unpacked
    dpkg --configure postgresql-client-common 2>/dev/null || true
    dpkg --configure postgresql-common 2>/dev/null || true
    dpkg --configure postgresql-client-15 2>/dev/null || true
    dpkg --configure postgresql-15 2>/dev/null || true
    dpkg --configure -a 2>/dev/null || true
    systemctl daemon-reload
    ok "PostgreSQL packages reinstalled"
else
    # Packages are fine, just make sure they're fully configured
    info "PostgreSQL packages already installed — ensuring configuration..."
    dpkg --configure postgresql-common 2>/dev/null || true
    dpkg --configure postgresql-15 2>/dev/null || true
    systemctl daemon-reload
fi

# ── 4a-verify: Ensure pg_lsclusters works (hard check) ──
if ! command -v pg_lsclusters &>/dev/null; then
    echo ""
    warn "═══ CRITICAL: pg_lsclusters still not available after install ═══"
    echo "--- dpkg states ---"
    dpkg -l perl postgresql-client-common postgresql-common postgresql-15 2>&1 | cat
    echo "--- dpkg --configure output ---"
    dpkg --configure -a 2>&1 | tail -20
    echo "--- checking for the binary ---"
    find /usr -name pg_lsclusters 2>/dev/null || echo "pg_lsclusters binary not found anywhere"
    echo ""
    err "postgresql-common failed to install. pg_lsclusters not found. See debug above."
fi
ok "pg_lsclusters is available"

# ── 4b: Verify the postgresql.service unit exists ──
if ! systemctl list-unit-files postgresql.service 2>/dev/null | grep -q postgresql; then
    warn "postgresql.service unit not found — attempting to fix..."

    # Remove any stale fake service file from previous failed installs
    rm -f /lib/systemd/system/postgresql.service

    # The real service file comes from postgresql-common. Re-install it.
    if compgen -G "${BUNDLE_DIR}/debs-jammy/postgresql-common"_*.deb >/dev/null 2>&1; then
        info "Reinstalling postgresql-common .deb to create service file..."
        dpkg -i --force-depends --force-confdef --force-confold "${BUNDLE_DIR}"/debs-jammy/postgresql-common_*.deb 2>/dev/null || true
        dpkg --configure --force-depends postgresql-common 2>/dev/null || true
        systemctl daemon-reload
    fi

    # If STILL missing, the install is fundamentally broken
    if ! systemctl list-unit-files postgresql.service 2>/dev/null | grep -q postgresql; then
        echo ""
        warn "═══ postgresql.service STILL missing after all fix attempts ═══"
        echo "--- dpkg state ---"
        dpkg -l postgresql-common 2>&1 | cat
        echo "--- searching for service file ---"
        find / -name "postgresql.service" 2>/dev/null || echo "not found anywhere"
        echo ""
        err "Cannot create postgresql.service. postgresql-common is fundamentally broken."
    fi
fi

# ── 4c: Detect the installed PG version ──
PG_VER=$(dpkg -l 'postgresql-[0-9]*' 2>/dev/null \
    | awk '/^ii.*postgresql-[0-9]/{gsub(/postgresql-/,"",$2); print $2}' | head -1)
if [[ -z "${PG_VER}" ]]; then
    # Try finding it from the binary
    PG_VER=$(ls -d /usr/lib/postgresql/*/bin/postgres 2>/dev/null \
        | grep -oP 'postgresql/\K[0-9]+' | head -1)
fi
if [[ -z "${PG_VER}" ]]; then
    err "Cannot detect PostgreSQL version. Check that postgresql-15 .deb was installed correctly.
  Debug: dpkg -l 'postgresql*'"
fi
info "Detected PostgreSQL version: ${PG_VER}"

# ── 4d: Pre-requisites — locale & SSL (critical on minimal installs) ──
# PostgreSQL REFUSES to start without a valid locale. On minimal Ubuntu
# installs (common on production servers), en_US.UTF-8 is not generated.
info "Ensuring locale en_US.UTF-8 is available..."
if ! locale -a 2>/dev/null | grep -qi 'en_US\.utf.*8'; then
    warn "en_US.UTF-8 locale missing — generating it..."
    sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen 2>/dev/null || true
    echo "en_US.UTF-8 UTF-8" >> /etc/locale.gen 2>/dev/null || true
    locale-gen en_US.UTF-8 2>/dev/null || true
    update-locale LANG=en_US.UTF-8 2>/dev/null || true
    ok "Locale en_US.UTF-8 generated"
else
    ok "Locale en_US.UTF-8 already available"
fi

# PostgreSQL's default config enables SSL and expects the snakeoil cert.
# The ssl-cert package should have created it, but on offline installs
# the post-install hook often silently fails.
if [[ ! -f /etc/ssl/certs/ssl-cert-snakeoil.pem ]]; then
    warn "SSL snakeoil certificate missing — generating it..."
    if command -v make-ssl-cert &>/dev/null; then
        make-ssl-cert generate-default-snakeoil --force-overwrite 2>/dev/null || true
    else
        # Fallback: create a self-signed cert manually
        mkdir -p /etc/ssl/certs /etc/ssl/private
        openssl req -new -x509 -days 3650 -nodes \
            -out /etc/ssl/certs/ssl-cert-snakeoil.pem \
            -keyout /etc/ssl/private/ssl-cert-snakeoil.key \
            -subj "/CN=$(hostname)" 2>/dev/null || true
    fi
    # Ensure postgres user can read the key
    chown root:ssl-cert /etc/ssl/private/ssl-cert-snakeoil.key 2>/dev/null || true
    chmod 640 /etc/ssl/private/ssl-cert-snakeoil.key 2>/dev/null || true
    usermod -aG ssl-cert postgres 2>/dev/null || true
    ok "SSL snakeoil certificate created"
fi

# ── 4e: Ensure a cluster exists ──
if command -v pg_lsclusters &>/dev/null; then
    if ! pg_lsclusters -h 2>/dev/null | grep -q .; then
        warn "No PostgreSQL cluster found — creating '${PG_VER}/main'..."
        # Stop any partially-running postgres first
        systemctl stop postgresql 2>/dev/null || true
        # Drop broken cluster if data dir exists but cluster is not registered
        if [[ -d "/var/lib/postgresql/${PG_VER}/main" ]]; then
            pg_dropcluster "${PG_VER}" main --stop 2>/dev/null || true
        fi
        pg_createcluster "${PG_VER}" main --start --locale=en_US.UTF-8 || {
            warn "pg_createcluster failed — dumping log..."
            cat "/var/log/postgresql/postgresql-${PG_VER}-main.log" 2>/dev/null || true
            err "pg_createcluster ${PG_VER} main failed. See log above."
        }
        systemctl daemon-reload
    fi
else
    # pg_lsclusters not available — try creating cluster the hard way
    if [[ ! -d "/var/lib/postgresql/${PG_VER}/main/PG_VERSION" ]]; then
        warn "pg_lsclusters not found. Attempting manual cluster creation..."
        mkdir -p "/var/lib/postgresql/${PG_VER}/main"
        chown -R postgres:postgres /var/lib/postgresql
        sudo -u postgres "/usr/lib/postgresql/${PG_VER}/bin/initdb" \
            --locale=en_US.UTF-8 \
            -D "/var/lib/postgresql/${PG_VER}/main" 2>/dev/null || true
    fi
fi

# ── 4f: Unmask all PostgreSQL services (offline dpkg can mask them) ──
systemctl unmask postgresql 2>/dev/null || true
systemctl unmask "postgresql@${PG_VER}-main" 2>/dev/null || true
for MASKED_PG in $(systemctl list-unit-files 'postgresql*' 2>/dev/null | awk '/masked/{print $1}'); do
    info "Unmasking ${MASKED_PG}..."
    systemctl unmask "${MASKED_PG}" 2>/dev/null || true
done

# ── 4g: Start PostgreSQL (try multiple methods) ──
PG_STARTED=false

# Method 1: Standard systemctl
if systemctl start postgresql 2>/dev/null; then
    PG_STARTED=true
    info "PostgreSQL started via systemctl"
fi

# Method 2: Try the versioned cluster service directly
if ! $PG_STARTED; then
    warn "systemctl start postgresql failed — trying postgresql@${PG_VER}-main..."
    if systemctl start "postgresql@${PG_VER}-main" 2>/dev/null; then
        PG_STARTED=true
        info "PostgreSQL started via postgresql@${PG_VER}-main"
    fi
fi

# Method 3: Use pg_ctlcluster directly
if ! $PG_STARTED && command -v pg_ctlcluster &>/dev/null; then
    warn "Trying pg_ctlcluster ${PG_VER} main start..."
    if pg_ctlcluster "${PG_VER}" main start 2>/dev/null; then
        PG_STARTED=true
        info "PostgreSQL started via pg_ctlcluster"
    fi
fi

if ! $PG_STARTED; then
    echo ""
    warn "═══ PostgreSQL debug info ═══"
    echo "--- dpkg status ---"
    dpkg -l 'postgresql*' 2>/dev/null || true
    echo "--- systemd units ---"
    systemctl list-unit-files 'postgresql*' 2>/dev/null || true
    echo "--- pg_lsclusters ---"
    pg_lsclusters 2>/dev/null || true
    echo "--- journalctl ---"
    journalctl -u postgresql --no-pager -n 30 2>/dev/null || true
    journalctl -u "postgresql@${PG_VER}-main" --no-pager -n 30 2>/dev/null || true
    echo "--- PG log file ---"
    tail -50 "/var/log/postgresql/postgresql-${PG_VER}-main.log" 2>/dev/null || true
    echo ""
    err "All methods to start PostgreSQL failed. See debug output above."
fi

systemctl enable postgresql 2>/dev/null || true
systemctl enable "postgresql@${PG_VER}-main" 2>/dev/null || true

# Wait for PostgreSQL to be ready (poll instead of fixed sleep)
info "Waiting for PostgreSQL to be ready..."
PG_READY=false
for i in {1..15}; do
    if sudo -u postgres pg_isready &>/dev/null; then
        PG_READY=true
        break
    fi
    sleep 2
done

if ! $PG_READY; then
    echo ""
    warn "═══ PostgreSQL not accepting connections — dumping logs ═══"
    pg_lsclusters 2>/dev/null || true
    tail -30 "/var/log/postgresql/postgresql-${PG_VER}-main.log" 2>/dev/null || true
    journalctl -u postgresql --no-pager -n 20 2>/dev/null || true
    err "PostgreSQL started but not accepting connections. See logs above."
fi

# Find the actual port
PG_PORT=5432
if command -v pg_lsclusters &>/dev/null; then
    DETECTED_PORT=$(pg_lsclusters -h 2>/dev/null | awk '$4=="online"{print $3; exit}')
    [[ -n "${DETECTED_PORT}" ]] && PG_PORT="${DETECTED_PORT}"
fi
info "PostgreSQL running on port ${PG_PORT}"
PSQL="sudo -u postgres psql -p ${PG_PORT}"

# Create database and user (idempotent)
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
    info "Adding kraken user to pg_hba.conf..."
    # Back up the original before modifying
    cp "${PG_HBA}" "${PG_HBA}.bak.$(date +%Y%m%d%H%M%S)"
    ok "Original pg_hba.conf backed up"
    # Insert kraken rules BEFORE the first non-comment, non-empty line
    # so they take priority. Fall back to appending if sed pattern fails.
    if grep -q '^local\|^host' "${PG_HBA}" 2>/dev/null; then
        sed -i '0,/^\(local\|host\)/s//# Kraken application\nlocal   all       kraken                md5\nhost    all       kraken  127.0.0.1\/32  md5\nhost    all       kraken  ::1\/128       md5\n\n\1/' "${PG_HBA}"
    else
        # Append if no existing rules found (unlikely but safe)
        printf '\n# Kraken application\nlocal   all       kraken                md5\nhost    all       kraken  127.0.0.1/32  md5\nhost    all       kraken  ::1/128       md5\n' >> "${PG_HBA}"
    fi
    systemctl restart postgresql
fi

ok "PostgreSQL configured (port ${PG_PORT})"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 5: Configure Redis                         ║
# ╚═══════════════════════════════════════════════════╝
step "Step 5/9: Configuring Redis"

REDIS_CONF="/etc/redis/redis.conf"
# redis.io packages sometimes use a different config path
if [[ ! -f "${REDIS_CONF}" ]] && [[ -f "/etc/redis/redis-server.conf" ]]; then
    REDIS_CONF="/etc/redis/redis-server.conf"
fi
REDIS_PASSWORD='ItlfcC7mVCKg/V2FfnFmEogc68LfuN1m'

if [[ -f "${REDIS_CONF}" ]]; then
    # Back up before modifying
    cp "${REDIS_CONF}" "${REDIS_CONF}.bak.$(date +%Y%m%d%H%M%S)"
    ok "Original redis.conf backed up"

    # Set password (handles both commented and uncommented lines)
    if grep -q '^# requirepass' "${REDIS_CONF}"; then
        sed -i 's/^# requirepass .*/requirepass ItlfcC7mVCKg\/V2FfnFmEogc68LfuN1m/' "${REDIS_CONF}"
    elif grep -q '^requirepass' "${REDIS_CONF}"; then
        sed -i 's/^requirepass .*/requirepass ItlfcC7mVCKg\/V2FfnFmEogc68LfuN1m/' "${REDIS_CONF}"
    else
        echo "requirepass ${REDIS_PASSWORD}" >> "${REDIS_CONF}"
    fi

    # Bind to localhost
    sed -i 's/^bind .*/bind 127.0.0.1 ::1/' "${REDIS_CONF}"

    # Unmask Redis — same issue as PostgreSQL with offline dpkg installs
    systemctl unmask redis-server 2>/dev/null || true
    systemctl restart redis-server
    systemctl enable redis-server

    # Verify
    if redis-cli -a "${REDIS_PASSWORD}" ping 2>/dev/null | grep -q PONG; then
        ok "Redis configured and running"
    else
        warn "Redis installed but ping failed — check manually"
    fi
else
    warn "Redis config not found at ${REDIS_CONF} — may not be installed via .deb"
fi

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 6: Deploy Backend                          ║
# ╚═══════════════════════════════════════════════════╝
step "Step 6/9: Deploying Backend"

# Create directories
mkdir -p /opt/kraken/backend/target
mkdir -p /opt/kraken/backend/config
mkdir -p /var/log/ussd/exports
mkdir -p /mediation

# Create system user (if doesn't exist)
id kraken-app &>/dev/null || useradd --system --shell /usr/sbin/nologin --create-home kraken-app
mkdir -p /home/kraken-app/.ssh
chown -R kraken-app:kraken-app /home/kraken-app
chown -R kraken-app:kraken-app /var/log/ussd
chown -R kraken-app:kraken-app /mediation 2>/dev/null || true
chmod -R 775 /mediation 2>/dev/null || true

# Copy JAR
cp "${BUNDLE_DIR}/kraken-backend.jar" /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar
ok "Backend JAR deployed"

# Create environment file
# NOTE: Uses the detected PG_PORT in case PG landed on a non-default port.
cat > /opt/kraken/backend/config/ussd-gateway.env << EOF
# Kraken Backend Environment — generated by install-on-server.sh
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
ok "Backend environment configured"

# Create systemd service
cat > /etc/systemd/system/kraken-backend.service << EOF
[Unit]
Description=Kraken Backend (Spring Boot)
After=network.target postgresql.service redis-server.service
Requires=postgresql.service redis-server.service

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
StartLimitIntervalSec=60
StartLimitBurst=3

NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/var/log/ussd /mediation /home/kraken-app/.ssh
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable kraken-backend
ok "Backend systemd service created"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 6b: Deploy Kraken APN Gateway (Port 8081)  ║
# ╚═══════════════════════════════════════════════════╝
if [[ -f "${BUNDLE_DIR}/kraken-apn.jar" ]]; then
    step "Step 6b: Deploying Kraken APN Gateway"
    mkdir -p /opt/kraken/apn/target /opt/kraken/apn/config
    cp "${BUNDLE_DIR}/kraken-apn.jar" /opt/kraken/apn/target/kraken-apn.jar
    chown -R kraken-app:kraken-app /opt/kraken/apn

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

    cat > /etc/systemd/system/kraken-apn.service << EOF
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
    ok "APN Gateway systemd service created"
fi

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 7: Deploy Frontend                         ║
# ╚═══════════════════════════════════════════════════╝
step "Step 7/9: Deploying Frontend"

# Create frontend directory
mkdir -p /opt/kraken/frontend

# Extract standalone build
tar -xzf "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" -C /opt/kraken/frontend/

# Verify server.js exists
if [[ ! -f /opt/kraken/frontend/server.js ]]; then
    err "Frontend extraction failed — server.js not found in /opt/kraken/frontend/"
fi

# Create system user (if doesn't exist)
id nextjs-app &>/dev/null || useradd --system --shell /usr/sbin/nologin nextjs-app

# Set permissions
chown -R nextjs-app:nextjs-app /opt/kraken/frontend
ok "Frontend deployed"

# Create systemd service
cat > /etc/systemd/system/kraken-frontend.service << EOF
[Unit]
Description=Kraken Frontend (Next.js)
After=network.target kraken-backend.service

[Service]
Type=simple
User=nextjs-app
Group=nextjs-app

Environment=NODE_ENV=production
Environment=PORT=3000
Environment=HOSTNAME=0.0.0.0
Environment=NEXT_PUBLIC_API_URL=http://${SERVER_IP}/api/v1

WorkingDirectory=/opt/kraken/frontend
ExecStart=${NODE_BIN} server.js

StandardOutput=append:/var/log/ussd/frontend-stdout.log
StandardError=append:/var/log/ussd/frontend-stderr.log

Restart=on-failure
RestartSec=5
StartLimitIntervalSec=60
StartLimitBurst=5

NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/log/ussd
ReadOnlyPaths=/opt/kraken/frontend
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable kraken-frontend
ok "Frontend systemd service created"

# ╔═══════════════════════════════════════════════════╗
# ║  STEP 8: Configure Nginx                         ║
# ╚═══════════════════════════════════════════════════╝
step "Step 8/9: Configuring Nginx"

cat > /etc/nginx/sites-available/kraken << NGINX
# Rate limiting
limit_req_zone \$binary_remote_addr zone=api_limit:10m rate=30r/s;

server {
    listen 80;
    server_name ${SERVER_IP};

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "origin-when-cross-origin" always;

    client_max_body_size 50M;

    # ── Reports & Excel streaming export (unbuffered, 600s timeout for large datasets) ──
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

    # ── Real-time USSD APN traffic (35s timeout for 30s downstream API) ──
    location /api/v1/ussd/ {
        proxy_pass http://127.0.0.1:8081/api/v1/ussd/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_connect_timeout 5s;
        proxy_send_timeout 35s;
        proxy_read_timeout 35s;
    }

    # ── Live trace SSE stream ──
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

    # ── Backend API ──
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

    # ── Actuator (internal only) ──
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

    # ── Swagger UI ──
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

    # ── WebSocket ──
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

    # ── Frontend (catch-all) ──
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

# Enable the site
ln -sf /etc/nginx/sites-available/kraken /etc/nginx/sites-enabled/kraken
if [[ -f /etc/nginx/sites-enabled/default ]]; then
    cp /etc/nginx/sites-enabled/default "/etc/nginx/sites-enabled/default.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null || true
    rm -f /etc/nginx/sites-enabled/default
    info "Default nginx site disabled (backup saved)"
fi

# Test and restart
if ! nginx -t 2>&1; then
    err "Nginx config test failed. Check /etc/nginx/sites-available/kraken"
fi
# Unmask Nginx — same issue as PostgreSQL with offline dpkg installs
systemctl unmask nginx 2>/dev/null || true
systemctl restart nginx
systemctl enable nginx
ok "Nginx configured as reverse proxy"

# ╔═══════════════════════════════════════════════════════════╗
# ║  STEP 9: Start services & verify with polling            ║
# ╚═══════════════════════════════════════════════════════════╝
step "Step 9/9: Starting all services"

# Setup log rotation before starting services
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
ok "Log rotation configured"

info "Starting backend..."
systemctl start kraken-backend

# Poll for backend health (up to 90 seconds)
info "Waiting for backend to become healthy..."
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
    warn "Backend not yet healthy after 90s — may still be running Flyway migrations"
    warn "Check: sudo journalctl -u kraken-backend -f"
fi

if systemctl list-unit-files kraken-apn.service 2>/dev/null | grep -q kraken-apn; then
    info "Starting APN Gateway..."
    systemctl start kraken-apn
fi

info "Starting frontend..."
systemctl start kraken-frontend
sleep 5

# ─── Final status check ───
echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${BOLD}              SERVICE STATUS REPORT               ${NC}"
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"

check_service() {
    local name="$1"
    local service="$2"
    if systemctl is-active --quiet "${service}" 2>/dev/null; then
        echo -e "  ${GREEN}●${NC} ${name}: ${GREEN}active${NC}"
    else
        echo -e "  ${RED}●${NC} ${name}: ${RED}inactive${NC}"
    fi
}

check_service "PostgreSQL " "postgresql"
check_service "Redis      " "redis-server"
check_service "Backend    " "kraken-backend"
check_service "APN Gateway" "kraken-apn"
check_service "Frontend   " "kraken-frontend"
check_service "Nginx      " "nginx"

echo ""

FRONTEND_OK=false
HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" http://localhost:3000 2>/dev/null || echo "000")
if [[ "${HTTP_CODE}" == "200" || "${HTTP_CODE}" == "307" || "${HTTP_CODE}" == "302" ]]; then
    FRONTEND_OK=true
    echo -e "  ${GREEN}✓${NC} Frontend health check: PASSED (HTTP ${HTTP_CODE})"
else
    echo -e "  ${RED}✗${NC} Frontend health check: FAILED (HTTP ${HTTP_CODE})"
fi

if $BACKEND_OK; then
    echo -e "  ${GREEN}✓${NC} Backend health check:  PASSED"
else
    echo -e "  ${RED}✗${NC} Backend health check:  FAILED (may still be starting)"
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
    echo -e "  Check logs with:"
    echo -e "    sudo journalctl -u kraken-backend -f"
    echo -e "    sudo journalctl -u kraken-frontend -f"
    echo -e "    sudo tail -f /var/log/ussd/backend-stdout.log"
fi

echo ""
echo -e "${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
info "Useful commands:"
info "  sudo systemctl status kraken-backend"
info "  sudo systemctl status kraken-frontend"
info "  sudo systemctl restart kraken-backend"
info "  sudo systemctl restart kraken-frontend"
info "  sudo tail -f /var/log/ussd/backend-stdout.log"
info "  sudo tail -f /var/log/ussd/frontend-stdout.log"
