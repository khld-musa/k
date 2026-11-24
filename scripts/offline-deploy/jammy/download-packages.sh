#!/usr/bin/env bash
#
# download-packages.sh — Ubuntu 22.04 (Jammy Jellyfish)
# ═══════════════════════════════════════════════════════
# Downloads all .deb packages needed for offline installation
# on an Ubuntu 22.04 server using a Docker container.
#
# This ensures the dependency tree is resolved against the EXACT
# same OS as the target server, not your dev machine.
#
# REQUIRES: Docker or Podman installed on your dev laptop.
#
# Usage:
#   ./scripts/offline-deploy/jammy/download-packages.sh
#
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

PROJECT_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BUNDLE_DIR="${PROJECT_ROOT}/offline-bundle"
DEBS_DIR="${BUNDLE_DIR}/debs-jammy"

info "Target OS:  Ubuntu 22.04 (jammy)"
info "Output dir: ${DEBS_DIR}"
echo ""

# Check Docker or Podman is available
if command -v podman &>/dev/null; then
    CONTAINER_CMD="podman"
elif command -v docker &>/dev/null; then
    CONTAINER_CMD="docker"
else
    err "Docker or Podman is required but not installed.
Install one of them (e.g., sudo apt install podman or sudo apt install docker.io)"
fi

mkdir -p "${DEBS_DIR}"

# ═════════════════════════════════════════════════
# Run a container matching the TARGET server OS
# to download all .deb packages with correct dependencies.
#
# Strategy: apt-get install first (so apt resolves the full
# dependency tree correctly), then copy the cached .deb files.
# This is far more reliable than apt-cache depends | grep.
# ═════════════════════════════════════════════════
info "Starting Ubuntu 22.04 (jammy) container to download packages..."
info "This will take a few minutes on first run..."
echo ""

${CONTAINER_CMD} run --rm \
    -v "${DEBS_DIR}:/debs:z" \
    ubuntu:22.04 \
    bash -c '
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive

echo "══════════════════════════════════════════════"
echo " Downloading packages for Ubuntu 22.04 Jammy"
echo "══════════════════════════════════════════════"

# ── Keep .deb files in apt cache (Docker images sometimes disable this) ──
rm -f /etc/apt/apt.conf.d/docker-clean
echo "Binary::apt::APT::Keep-Downloaded-Packages \"true\";" \
    > /etc/apt/apt.conf.d/99keep-debs

apt-get update -qq

echo "[1/7] Installing base tools..."
apt-get install -y -qq wget gnupg curl lsb-release ca-certificates apt-utils 2>/dev/null

# ── Add PostgreSQL 15 Repository ──
echo "[2/7] Adding PostgreSQL repository..."
echo "deb http://apt.postgresql.org/pub/repos/apt jammy-pgdg main" > /etc/apt/sources.list.d/pgdg.list
wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | apt-key add - 2>/dev/null

# ── Add Redis Repository ──
echo "[3/7] Adding Redis repository..."
curl -fsSL https://packages.redis.io/gpg | gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg 2>/dev/null
echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb jammy main" > /etc/apt/sources.list.d/redis.list

# ── Add Adoptium (Java 21) Repository ──
echo "[4/7] Adding Adoptium repository..."
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /usr/share/keyrings/adoptium.asc >/dev/null
echo "deb [signed-by=/usr/share/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb jammy main" > /etc/apt/sources.list.d/adoptium.list

# ── Add NodeSource (Node.js 20) Repository ──
echo "[5/7] Adding NodeSource repository..."
curl -fsSL https://deb.nodesource.com/setup_20.x | bash - 2>/dev/null

apt-get update -qq

# ── Target packages ──
PACKAGES=(
    postgresql-15
    postgresql-client-15
    postgresql-contrib-15
    postgresql-contrib
    redis-server
    redis-tools
    nginx
    temurin-21-jdk
    nodejs
    curl
    wget
)

# ══════════════════════════════════════════════════════════════
# KEY FIX: Use apt-get install to resolve the FULL dependency
# tree correctly, then harvest the .deb files from apt cache.
# The old approach (apt-cache depends --recurse | grep) missed
# most transitive dependencies because they were indented.
# ══════════════════════════════════════════════════════════════
echo "[6/7] Installing packages (apt resolves all dependencies)..."
echo "     Packages: ${PACKAGES[*]}"
apt-get install -y "${PACKAGES[@]}"

echo "[7/7] Collecting .deb files from apt cache..."
cp /var/cache/apt/archives/*.deb /debs/ 2>/dev/null || true

# Also re-download with --reinstall to catch any packages that
# were already present in the base Docker image (unlikely to be
# missing on a real server, but belt-and-suspenders).
apt-get install -y --reinstall --download-only "${PACKAGES[@]}" 2>/dev/null || true
cp /var/cache/apt/archives/*.deb /debs/ 2>/dev/null || true

# ══════════════════════════════════════════════════════════════
# CRITICAL: Download Essential/base packages that are pre-installed
# in the Docker image but may be at DIFFERENT versions on the
# production server. Packages like perl have strict version-match
# dependencies (perl depends on perl-base = exact-version). If
# the production server has an older perl-base, perl cannot install.
# ══════════════════════════════════════════════════════════════
echo "[7b/7] Downloading base/Essential packages (version-match safety)..."
BASE_PACKAGES=(
    perl-base
    libperl5.34
    perl-modules-5.34
    perl
    python3-minimal
    python3.10-minimal
    libpython3.10-minimal
    libpython3.10-stdlib
    libpython3-stdlib
    python3.10
    python3
    libc6
    libcrypt1
    libgcc-s1
    libssl3
    openssl
    locales
    ssl-cert
)
apt-get install -y --reinstall --download-only "${BASE_PACKAGES[@]}" 2>/dev/null || true
cp /var/cache/apt/archives/*.deb /debs/ 2>/dev/null || true

echo ""
echo "Download complete!"
echo "  Total .deb files: $(ls -1 /debs/*.deb 2>/dev/null | wc -l)"
echo "  Total size: $(du -sh /debs/ | cut -f1)"
'

DEB_COUNT=$(ls -1 "${DEBS_DIR}"/*.deb 2>/dev/null | wc -l)

if [[ "${DEB_COUNT}" -lt 10 ]]; then
    err "Only ${DEB_COUNT} .deb files downloaded — something went wrong."
fi

echo ""
ok "═══════════════════════════════════════════════════════"
ok " Jammy (22.04) packages downloaded to: ${DEBS_DIR}"
ok " Total files: ${DEB_COUNT}"
ok " Total size:  $(du -sh "${DEBS_DIR}" | cut -f1)"
ok "═══════════════════════════════════════════════════════"
echo ""
info "Next step: Create the final bundle:"
info "  ./scripts/offline-deploy/jammy/create-bundle.sh"
