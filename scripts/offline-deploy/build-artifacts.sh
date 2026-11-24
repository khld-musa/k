#!/usr/bin/env bash
#
# build-artifacts.sh — Build Backend JAR + Frontend Standalone
# ═══════════════════════════════════════════════════════════════
# Run this on your DEVELOPMENT LAPTOP (internet access required)
# BEFORE running download-packages.sh.
#
# Target: Ubuntu 22.04 LTS (Jammy Jellyfish)
#
# Usage:
#   ./scripts/offline-deploy/build-artifacts.sh <SERVER_IP>
#
# Example:
#   ./scripts/offline-deploy/build-artifacts.sh 192.168.1.100
#
set -euo pipefail

# ─── Colors ───
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ─── Validate arguments ───
if [[ $# -lt 1 ]]; then
    err "Usage: $0 <SERVER_IP_OR_DOMAIN>
Example: $0 192.168.1.100"
fi

SERVER_IP="$1"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUNDLE_DIR="${PROJECT_ROOT}/offline-bundle"

info "Project root: ${PROJECT_ROOT}"
info "Server IP:    ${SERVER_IP}"
info "Bundle dir:   ${BUNDLE_DIR}"
echo ""

mkdir -p "${BUNDLE_DIR}"

# ═════════════════════════════════════════════════
# 1. BUILD BACKEND JAR
# ═════════════════════════════════════════════════
info "═══ Building Backend JAR ═══"

cd "${PROJECT_ROOT}/backend"

# Make Maven wrapper executable
chmod +x mvnw

info "Running: ./mvnw clean package -DskipTests"
./mvnw clean package -DskipTests

# Find the JAR
JAR_FILE=$(ls target/kraken-*.jar 2>/dev/null | head -1)
if [[ -z "${JAR_FILE}" ]]; then
    err "Backend JAR not found! Build failed."
fi

cp "${JAR_FILE}" "${BUNDLE_DIR}/kraken-backend.jar"
ok "Backend JAR built: $(du -h "${BUNDLE_DIR}/kraken-backend.jar" | cut -f1)"

# ═════════════════════════════════════════════════
# 1b. BUILD APN GATEWAY JAR (Standalone)
# ═════════════════════════════════════════════════
info "═══ Building Kraken APN Gateway JAR ═══"

cd "${PROJECT_ROOT}/kraken-apn"
chmod +x mvnw

info "Running: ./mvnw clean package -DskipTests"
./mvnw clean package -DskipTests

APN_JAR_FILE=$(ls target/kraken-apn-*.jar 2>/dev/null | grep -v original | head -1)
if [[ -z "${APN_JAR_FILE}" ]]; then
    err "APN JAR not found! Build failed."
fi

cp "${APN_JAR_FILE}" "${BUNDLE_DIR}/kraken-apn.jar"
ok "APN Gateway JAR built: $(du -h "${BUNDLE_DIR}/kraken-apn.jar" | cut -f1)"

# ═════════════════════════════════════════════════
# 2. BUILD FRONTEND (Next.js Standalone)
# ═════════════════════════════════════════════════
info "═══ Building Frontend ═══"

cd "${PROJECT_ROOT}/frontend"

info "Installing npm dependencies..."
npm ci

# Bake the API URL into the build
export NEXT_PUBLIC_API_URL="http://${SERVER_IP}/api/v1"
info "NEXT_PUBLIC_API_URL = ${NEXT_PUBLIC_API_URL}"

info "Running: npm run build"
npm run build

# Package standalone output
# Next.js standalone requires manual copy of public/ and .next/static/
info "Packaging standalone output..."
STANDALONE_DIR="${PROJECT_ROOT}/frontend/.next/standalone"

if [[ ! -d "${STANDALONE_DIR}" ]]; then
    err "Standalone output not found at ${STANDALONE_DIR}. Is 'output: standalone' set in next.config.ts?"
fi

# Copy public assets into standalone
cp -r "${PROJECT_ROOT}/frontend/public" "${STANDALONE_DIR}/public" 2>/dev/null || warn "No public/ directory found"

# Copy static assets (.next/static → standalone/.next/static)
mkdir -p "${STANDALONE_DIR}/.next"
cp -r "${PROJECT_ROOT}/frontend/.next/static" "${STANDALONE_DIR}/.next/static" 2>/dev/null || warn "No .next/static/ directory found"

cd "${PROJECT_ROOT}/frontend"
tar -czf "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" -C .next/standalone .

ok "Frontend standalone built: $(du -h "${BUNDLE_DIR}/kraken-frontend-standalone.tar.gz" | cut -f1)"

# ═════════════════════════════════════════════════
# 3. SAVE SERVER IP
# ═════════════════════════════════════════════════
echo "${SERVER_IP}" > "${BUNDLE_DIR}/SERVER_IP.txt"
ok "Server IP saved"

# ═════════════════════════════════════════════════
# 4. DOWNLOAD JAVA + NODE BINARY TARBALLS (fallback)
# ═════════════════════════════════════════════════
info "═══ Downloading Java 21 + Node.js 20 binary tarballs (fallback) ═══"

mkdir -p "${BUNDLE_DIR}/binaries"

# Java 21 Temurin (universal Linux x64 tarball)
if [[ ! -f "${BUNDLE_DIR}/binaries/temurin-21-jdk.tar.gz" ]]; then
    info "Downloading Eclipse Temurin JDK 21..."
    wget -q --show-progress \
        "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk" \
        -O "${BUNDLE_DIR}/binaries/temurin-21-jdk.tar.gz"
    ok "Java 21 downloaded: $(du -h "${BUNDLE_DIR}/binaries/temurin-21-jdk.tar.gz" | cut -f1)"
else
    ok "Java 21 tarball already exists, skipping download"
fi

# Node.js 20 LTS (universal Linux x64 tarball)
if [[ ! -f "${BUNDLE_DIR}/binaries/node-v20-linux-x64.tar.xz" ]]; then
    info "Downloading Node.js 20 LTS..."
    NODE_VERSION=$(wget -qO- https://nodejs.org/dist/latest-v20.x/ | grep -oP 'node-v\K[0-9.]+' | head -1)
    wget -q --show-progress \
        "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
        -O "${BUNDLE_DIR}/binaries/node-v20-linux-x64.tar.xz"
    ok "Node.js v${NODE_VERSION} downloaded: $(du -h "${BUNDLE_DIR}/binaries/node-v20-linux-x64.tar.xz" | cut -f1)"
else
    ok "Node.js 20 tarball already exists, skipping download"
fi

echo ""
ok "═══════════════════════════════════════════════════════"
ok " Artifacts built successfully!"
ok " Bundle location: ${BUNDLE_DIR}"
ok "═══════════════════════════════════════════════════════"
echo ""
info "Next step: download .deb packages:"
info "  ./scripts/offline-deploy/jammy/download-packages.sh"
