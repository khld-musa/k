# Kraken — Full Deployment Guide (Ubuntu Server)

> **Kraken** is a Telecom USSD Management Platform built with:
> - **Backend**: Spring Boot 3.2 (Java 21) + PostgreSQL + Redis
> - **Frontend**: Next.js 16 (React 19, Standalone output)
>
> This guide covers **two deployment methods**: Docker Compose (recommended) and bare-metal (without Docker).

---

## 🚀 TL;DR — Just Run the Script

Pre-built automation scripts are in the `scripts/` folder. Copy the project to the server, make the scripts executable, and run **one command**:

```bash
chmod +x scripts/*.sh
```

**Docker deployment:**
```bash
sudo ./scripts/deploy-docker.sh 192.168.1.100     # ← replace with your server IP
```

**Bare-metal deployment:**
```bash
sudo ./scripts/deploy-bare-metal.sh 192.168.1.100  # ← replace with your server IP
```

### All Available Scripts

| Script | Description | Usage |
|--------|-------------|-------|
| `scripts/deploy-docker.sh` | Full Docker deployment (installs Docker, builds, starts) | `sudo ./scripts/deploy-docker.sh <IP>` |
| `scripts/deploy-bare-metal.sh` | Full bare-metal deployment (installs all deps, builds, configures) | `sudo ./scripts/deploy-bare-metal.sh <IP>` |
| `scripts/update-docker.sh` | Pull code + rebuild containers | `./scripts/update-docker.sh [--full]` |
| `scripts/update-bare-metal.sh` | Pull code + rebuild + restart services | `sudo ./scripts/update-bare-metal.sh [--backend\|--frontend\|--all]` |
| `scripts/backup.sh` | Database backup (auto-detects Docker/bare-metal) | `./scripts/backup.sh` or `./scripts/backup.sh --install` (cron) |
| `scripts/restore.sh` | Restore database from backup | `./scripts/restore.sh <dump-file>` |
| `scripts/status.sh` | Health check & status dashboard | `./scripts/status.sh` |

> All secrets are pre-configured in `.env.example` — you only need to replace the server IP.

---

## Table of Contents

1. [Prerequisites & Server Preparation](#1-prerequisites--server-preparation)
2. [Method 1 — Deploy with Docker Compose (Recommended)](#2-method-1--deploy-with-docker-compose-recommended)
   - [2.1 Install Docker & Docker Compose](#21-install-docker--docker-compose)
   - [2.2 Clone the Repository](#22-clone-the-repository)
   - [2.3 Configure Environment Variables](#23-configure-environment-variables)
   - [2.4 Build & Start All Services](#24-build--start-all-services)
   - [2.5 Verify the Deployment](#25-verify-the-deployment)
   - [2.6 Manage & Monitor](#26-manage--monitor)
   - [2.7 Update / Redeploy](#27-update--redeploy)
3. [Method 2 — Deploy Without Docker (Bare-Metal)](#3-method-2--deploy-without-docker-bare-metal)
   - [3.1 Install System Dependencies](#31-install-system-dependencies)
   - [3.2 Install & Configure PostgreSQL 15](#32-install--configure-postgresql-15)
   - [3.3 Install & Configure Redis 7](#33-install--configure-redis-7)
   - [3.4 Install Java 21 (Eclipse Temurin)](#34-install-java-21-eclipse-temurin)
   - [3.5 Install Node.js 20 LTS](#35-install-nodejs-20-lts)
   - [3.6 Clone the Repository](#36-clone-the-repository)
   - [3.7 Build & Deploy the Backend](#37-build--deploy-the-backend)
   - [3.8 Build & Deploy the Frontend](#38-build--deploy-the-frontend)
   - [3.9 Configure Nginx as Reverse Proxy](#39-configure-nginx-as-reverse-proxy)
   - [3.10 Enable HTTPS with Let's Encrypt (Optional but Recommended)](#310-enable-https-with-lets-encrypt-optional-but-recommended)
   - [3.11 Verify the Deployment](#311-verify-the-deployment)
4. [Firewall Configuration (Both Methods)](#4-firewall-configuration-both-methods)
5. [Backup & Restore](#5-backup--restore)
6. [Troubleshooting](#6-troubleshooting)
7. [Production Hardening Checklist](#7-production-hardening-checklist)

---

## 1. Prerequisites & Server Preparation

### Minimum Server Requirements

| Resource | Minimum   | Recommended |
|----------|-----------|-------------|
| CPU      | 2 cores   | 4 cores     |
| RAM      | 4 GB      | 8 GB        |
| Disk     | 20 GB SSD | 50 GB SSD   |
| OS       | Ubuntu 22.04 LTS or 24.04 LTS | Ubuntu 24.04 LTS |

### Initial Server Setup

```bash
# Update the system
sudo apt update && sudo apt upgrade -y

# Install essential tools
sudo apt install -y curl wget git unzip software-properties-common apt-transport-https ca-certificates gnupg lsb-release

# Set the timezone (adjust to your region)
sudo timedatectl set-timezone Africa/Khartoum

# Create a dedicated user (optional but recommended)
sudo adduser kraken --disabled-password --gecos ""
sudo usermod -aG sudo kraken
```

---

## 2. Method 1 — Deploy with Docker Compose (Recommended)

This is the **fastest and most reproducible** way to deploy Kraken. All services (PostgreSQL, Redis, Backend, Frontend) run in isolated containers.

### 2.1 Install Docker & Docker Compose

```bash
# Remove old Docker versions (if any)
sudo apt remove -y docker docker-engine docker.io containerd runc 2>/dev/null

# Add Docker's official GPG key
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Add Docker repository
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Install Docker Engine + Compose plugin
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

# Allow your user to run Docker without sudo
sudo usermod -aG docker $USER
newgrp docker

# Verify installation
docker --version
docker compose version
```

### 2.2 Clone the Repository

```bash
cd /opt
sudo mkdir -p kraken && sudo chown $USER:$USER kraken
git clone <YOUR_REPO_URL> /opt/kraken
cd /opt/kraken
```

### 2.3 Configure Environment Variables

A ready-to-use `.env.example` file is included in the repository with **pre-generated secure secrets**. Simply copy it:

```bash
cd /opt/kraken
cp .env.example .env
```

Then **only update the server IP / domain** in the `.env` file:

```bash
# Replace YOUR_SERVER_IP with your actual server IP or domain name
sed -i 's/YOUR_SERVER_IP/192.168.1.100/g' .env   # ← change 192.168.1.100 to your IP
```

Verify the values:

```bash
cat .env
```

> ⚠️ The only values you **must** change are `YOUR_SERVER_IP` in `NEXT_PUBLIC_API_URL` and `FRONTEND_URL`. All secrets (DB password, Redis password, JWT, encryption) are already pre-generated and production-ready.

### 2.4 Build & Start All Services

```bash
cd /opt/kraken

# Build images and start all containers in detached mode
docker compose up -d --build

# Watch the build progress (first build takes 5-10 minutes)
docker compose logs -f
```

**What this starts:**
| Service    | Container Name     | Port  | Description                   |
|------------|--------------------|-------|-------------------------------|
| PostgreSQL | `kraken_db`        | 5432  | Database                      |
| Redis      | `kraken_redis`     | 6379  | Cache & session store         |
| Backend    | `kraken_backend`   | 8080  | Spring Boot API               |
| Frontend   | `kraken_frontend`  | 3000  | Next.js web application       |

### 2.5 Verify the Deployment

```bash
# Check all containers are running
docker compose ps

# Expected output — all should show "Up" status:
# NAME               STATUS         PORTS
# kraken_db          Up (healthy)   0.0.0.0:5432->5432/tcp
# kraken_redis       Up             6379/tcp
# kraken_backend     Up             0.0.0.0:8080->8080/tcp
# kraken_frontend    Up             0.0.0.0:3000->3000/tcp

# Test the backend health endpoint
curl -s http://localhost:8080/actuator/health | python3 -m json.tool

# Test the frontend
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000
# Should return: 200

# Check backend logs for errors
docker compose logs backend --tail=50

# Check that Flyway migrations ran successfully
docker compose logs backend | grep -i flyway
```

**Access the application:**
- **Frontend**: `http://YOUR_SERVER_IP:3000`
- **Backend API**: `http://YOUR_SERVER_IP:8080/api/v1`
- **Swagger/OpenAPI**: `http://YOUR_SERVER_IP:8080/swagger-ui.html`
- **Default Login**: username `admin` / password you set in `ADMIN_INITIAL_PASSWORD`

### 2.6 Manage & Monitor

```bash
# View real-time logs for all services
docker compose logs -f

# View logs for a specific service
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f db

# Restart a single service
docker compose restart backend

# Stop all services (preserves data volumes)
docker compose down

# Stop and remove ALL data (⚠️ DESTRUCTIVE — deletes database!)
docker compose down -v

# Check resource usage
docker stats

# Enter a container shell for debugging
docker exec -it kraken_backend bash
docker exec -it kraken_db psql -U kraken -d kraken_db
docker exec -it kraken_redis redis-cli -a YOUR_REDIS_PASSWORD
```

### 2.7 Update / Redeploy

```bash
cd /opt/kraken

# Pull latest code
git pull origin main

# Rebuild and restart (zero-downtime for DB/Redis)
docker compose up -d --build backend frontend

# Or full rebuild
docker compose down
docker compose up -d --build

# Clean up old images
docker image prune -f
```

---

## 3. Method 2 — Deploy Without Docker (Bare-Metal)

This method installs all services directly on the Ubuntu server. It gives you more control but requires more manual configuration and maintenance.

### 3.1 Install System Dependencies

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y \
  curl wget git unzip build-essential \
  software-properties-common apt-transport-https \
  ca-certificates gnupg lsb-release \
  openssh-client nginx
```

### 3.2 Install & Configure PostgreSQL 15

```bash
# Add PostgreSQL official repository
sudo sh -c 'echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list'
wget --quiet -O - https://www.postgresql.org/media/keys/ACCC4CF8.asc | sudo apt-key add -
sudo apt update

# Install PostgreSQL 15
sudo apt install -y postgresql-15 postgresql-client-15

# Start and enable the service
sudo systemctl start postgresql
sudo systemctl enable postgresql

# Verify it's running
sudo systemctl status postgresql
```

**Create the Kraken database and user:**

```bash
sudo -u postgres psql << 'SQL'
-- Create the application user
CREATE USER kraken WITH PASSWORD 'wqDV2fO/IpJZoL0JdFkjsNvG/QhKOMwl';

-- Create the database
CREATE DATABASE kraken_db OWNER kraken;

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE kraken_db TO kraken;

-- Connect to the database and grant schema privileges
\c kraken_db
GRANT ALL ON SCHEMA public TO kraken;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO kraken;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO kraken;
SQL
```

**Configure PostgreSQL to accept local connections** (edit if needed):

```bash
# Check the pg_hba.conf location
sudo -u postgres psql -c "SHOW hba_file;"

# Edit pg_hba.conf — ensure this line exists for local connections:
# local   all   kraken   md5
sudo nano /etc/postgresql/15/main/pg_hba.conf
```

Make sure the file contains:
```
# TYPE  DATABASE  USER    ADDRESS       METHOD
local   all       kraken                md5
host    all       kraken  127.0.0.1/32  md5
host    all       kraken  ::1/128       md5
```

```bash
# Restart PostgreSQL to apply changes
sudo systemctl restart postgresql

# Verify connection works
psql -h localhost -U kraken -d kraken_db -c "SELECT 1;"
```

### 3.3 Install & Configure Redis 7

```bash
# Add Redis official repository
curl -fsSL https://packages.redis.io/gpg | sudo gpg --dearmor -o /usr/share/keyrings/redis-archive-keyring.gpg
echo "deb [signed-by=/usr/share/keyrings/redis-archive-keyring.gpg] https://packages.redis.io/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/redis.list
sudo apt update

# Install Redis
sudo apt install -y redis-server

# Configure Redis with a password
sudo cp /etc/redis/redis.conf /etc/redis/redis.conf.bak
sudo sed -i 's/^# requirepass .*/requirepass ItlfcC7mVCKg\/V2FfnFmEogc68LfuN1m/' /etc/redis/redis.conf
sudo sed -i 's/^requirepass .*/requirepass ItlfcC7mVCKg\/V2FfnFmEogc68LfuN1m/' /etc/redis/redis.conf

# Bind only to localhost for security
sudo sed -i 's/^bind .*/bind 127.0.0.1 ::1/' /etc/redis/redis.conf

# Enable Redis as a systemd service
sudo systemctl restart redis-server
sudo systemctl enable redis-server

# Verify Redis is running
sudo systemctl status redis-server

# Test connection
redis-cli -a 'ItlfcC7mVCKg/V2FfnFmEogc68LfuN1m' ping
# Expected output: PONG
```

### 3.4 Install Java 21 (Eclipse Temurin)

```bash
# Add Adoptium (Temurin) repository
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo tee /usr/share/keyrings/adoptium.asc
echo "deb [signed-by=/usr/share/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update

# Install JDK 21
sudo apt install -y temurin-21-jdk

# Verify
java -version
# Expected: openjdk version "21.x.x"

# Set JAVA_HOME
echo 'export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64' | sudo tee /etc/profile.d/java.sh
source /etc/profile.d/java.sh
```

### 3.5 Install Node.js 20 LTS

```bash
# Add NodeSource repository for Node.js 20
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -

# Install Node.js
sudo apt install -y nodejs

# Verify
node --version   # Expected: v20.x.x
npm --version    # Expected: 10.x.x
```

### 3.6 Clone the Repository

```bash
sudo mkdir -p /opt/kraken
sudo chown $USER:$USER /opt/kraken
git clone <YOUR_REPO_URL> /opt/kraken
cd /opt/kraken
```

### 3.7 Build & Deploy the Backend

#### 3.7.1 Build the JAR

```bash
cd /opt/kraken/backend

# Make the Maven wrapper executable
chmod +x mvnw

# Build the application (skip tests for deployment)
./mvnw clean package -DskipTests

# The JAR will be at:
ls -la target/kraken-*.jar
```

#### 3.7.2 Create a System User for the Backend

```bash
sudo useradd --system --shell /usr/sbin/nologin --home /opt/kraken kraken-app

# Create directories
sudo mkdir -p /var/log/ussd
sudo mkdir -p /opt/kraken/backend/config
sudo mkdir -p /home/kraken-app/.ssh
sudo chown -R kraken-app:kraken-app /var/log/ussd
sudo chown -R kraken-app:kraken-app /home/kraken-app
```

#### 3.7.3 Create the Production Environment File

The secrets below match the pre-generated values in `.env.example`. Only replace `YOUR_SERVER_IP_OR_DOMAIN` with your actual server address.

```bash
sudo tee /opt/kraken/backend/config/ussd-gateway.env << 'EOF'
# Kraken Backend Environment Variables
SPRING_PROFILES_ACTIVE=prod

# Database
DB_URL=jdbc:postgresql://localhost:5432/kraken_db
DB_USER=kraken
DB_PASSWORD=wqDV2fO/IpJZoL0JdFkjsNvG/QhKOMwl

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=ItlfcC7mVCKg/V2FfnFmEogc68LfuN1m

# Security
JWT_SECRET=a8030653e0513f0b4655e4c44e22cad46458d761d77f0b45119866ed026b5940
ENCRYPTION_SECRET=5670da72c391bff9256bb4e255ffba74ce67ea93583f8272a187d59ab2975652

# Admin initial password (only used on first startup)
ADMIN_INITIAL_PASSWORD=Admin@Kraken2026!

# Frontend URL (for CORS) — CHANGE THIS
FRONTEND_URL=http://YOUR_SERVER_IP_OR_DOMAIN

# USSD Log Path
USSD_LOG_BASE_PATH=/var/log/ussd

# SSH Settings
SSH_STRICT_HOST_KEY_CHECKING=yes
SSH_KNOWN_HOSTS_FILE=/home/kraken-app/.ssh/known_hosts
EOF

# Replace the server IP placeholder (change 192.168.1.100 to your actual IP)
sudo sed -i 's/YOUR_SERVER_IP_OR_DOMAIN/192.168.1.100/g' /opt/kraken/backend/config/ussd-gateway.env

# Secure the file
sudo chmod 600 /opt/kraken/backend/config/ussd-gateway.env
sudo chown kraken-app:kraken-app /opt/kraken/backend/config/ussd-gateway.env
```

#### 3.7.4 Create a Systemd Service

```bash
sudo tee /etc/systemd/system/ussd-gateway-backend.service << 'EOF'
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

ExecStart=/usr/bin/java \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -jar /opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar

# Logging
StandardOutput=append:/var/log/ussd/backend-stdout.log
StandardError=append:/var/log/ussd/backend-stderr.log

# Restart policy
Restart=on-failure
RestartSec=10
StartLimitIntervalSec=60
StartLimitBurst=3

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/var/log/ussd /home/kraken-app/.ssh
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# Reload systemd, enable and start the service
sudo systemctl daemon-reload
sudo systemctl enable kraken-backend
sudo systemctl start kraken-backend

# Check status
sudo systemctl status kraken-backend

# View logs
sudo journalctl -u kraken-backend -f
# or
sudo tail -f /var/log/ussd/backend-stdout.log
```

#### 3.7.5 Verify Backend is Running

```bash
# Wait 15-30 seconds for the application to start, then:
curl -s http://localhost:8080/actuator/health | python3 -m json.tool
# Expected: {"status":"UP"}
```

### 3.8 Build & Deploy the Frontend

#### 3.8.1 Build the Next.js Application

```bash
cd /opt/kraken/frontend

# Install dependencies
npm ci

# Set the backend API URL at build time
export NEXT_PUBLIC_API_URL=http://YOUR_SERVER_IP_OR_DOMAIN/api/v1

# Build the production standalone app
npm run build
```

> **Important**: `NEXT_PUBLIC_API_URL` is baked into the build at compile time. If you change the backend URL, you **must rebuild** the frontend.

#### 3.8.2 Set Up the Standalone Output

The `next.config.ts` has `output: "standalone"` enabled. After building, the standalone server is at `.next/standalone/server.js`.

```bash
# Copy the public and static assets into standalone
cp -r public .next/standalone/public
cp -r .next/static .next/standalone/.next/static
```

#### 3.8.3 Create a System User for the Frontend

```bash
sudo useradd --system --shell /usr/sbin/nologin --home /opt/kraken/frontend nextjs-app
```

#### 3.8.4 Create a Systemd Service

```bash
sudo tee /etc/systemd/system/ussd-gateway-frontend.service << 'EOF'
[Unit]
Description=Kraken Frontend (Next.js)
After=network.target ussd-gateway-backend.service

[Service]
Type=simple
User=nextjs-app
Group=nextjs-app

Environment=NODE_ENV=production
Environment=PORT=3000
Environment=HOSTNAME=0.0.0.0
Environment=NEXT_PUBLIC_API_URL=http://YOUR_SERVER_IP_OR_DOMAIN/api/v1

WorkingDirectory=/opt/kraken/frontend/.next/standalone

ExecStart=/usr/bin/node server.js

# Logging
StandardOutput=append:/var/log/ussd/frontend-stdout.log
StandardError=append:/var/log/ussd/frontend-stderr.log

# Restart policy
Restart=on-failure
RestartSec=5
StartLimitIntervalSec=60
StartLimitBurst=5

# Security
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/log/ussd
ReadOnlyPaths=/opt/kraken/frontend
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# Set permissions
sudo chown -R nextjs-app:nextjs-app /opt/kraken/frontend/.next

# Reload, enable and start
sudo systemctl daemon-reload
sudo systemctl enable kraken-frontend
sudo systemctl start kraken-frontend

# Check status
sudo systemctl status kraken-frontend

# View logs
sudo journalctl -u kraken-frontend -f
```

### 3.9 Configure Nginx as Reverse Proxy

Nginx will serve as the entry point, proxying requests to the frontend (port 3000) and backend (port 8080).

```bash
sudo tee /etc/nginx/sites-available/kraken << 'NGINX'
# Rate limiting zone
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=30r/s;

server {
    listen 80;
    server_name YOUR_SERVER_IP_OR_DOMAIN;

    # Security headers
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "origin-when-cross-origin" always;

    # Max upload size (for file uploads if any)
    client_max_body_size 50M;

    # ========================================
    # Backend API (/api/*)
    # ========================================
    location /api/ {
        limit_req zone=api_limit burst=20 nodelay;

        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Timeouts
        proxy_connect_timeout 60s;
        proxy_send_timeout 120s;
        proxy_read_timeout 120s;
    }

    # ========================================
    # Actuator / Health (restrict to internal)
    # ========================================
    location /actuator/ {
        proxy_pass http://127.0.0.1:8080/actuator/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # Restrict to localhost and internal networks only
        allow 127.0.0.1;
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
    }

    # ========================================
    # Swagger UI
    # ========================================
    location /swagger-ui/ {
        proxy_pass http://127.0.0.1:8080/swagger-ui/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /v3/api-docs {
        proxy_pass http://127.0.0.1:8080/v3/api-docs;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # ========================================
    # WebSocket support (for terminal sessions, SSE, etc.)
    # ========================================
    location /ws/ {
        proxy_pass http://127.0.0.1:8080/ws/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 86400;
    }

    # ========================================
    # Frontend (Next.js) — catch-all
    # ========================================
    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket support for Next.js HMR (not needed in production but harmless)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
NGINX

# Enable the site
sudo ln -sf /etc/nginx/sites-available/kraken /etc/nginx/sites-enabled/kraken

# Remove default site
sudo rm -f /etc/nginx/sites-enabled/default

# Test Nginx configuration
sudo nginx -t

# Restart Nginx
sudo systemctl restart nginx
sudo systemctl enable nginx
```

### 3.10 Enable HTTPS with Let's Encrypt (Optional but Recommended)

If you have a domain name pointed to this server:

```bash
# Install Certbot
sudo apt install -y certbot python3-certbot-nginx

# Obtain and install certificate (replace with your domain)
sudo certbot --nginx -d your-domain.com

# Certbot will automatically:
# 1. Obtain the SSL certificate
# 2. Modify your Nginx config to enable HTTPS
# 3. Set up auto-renewal

# Verify auto-renewal
sudo certbot renew --dry-run
```

After enabling HTTPS, update:
1. **Backend `FRONTEND_URL`** → `https://your-domain.com`
2. **Frontend `NEXT_PUBLIC_API_URL`** → `https://your-domain.com/api/v1`
3. Rebuild the frontend and restart both services.

### 3.11 Verify the Deployment

```bash
# Check all services are running
sudo systemctl status postgresql redis-server kraken-backend kraken-frontend nginx

# Test backend health
curl -s http://localhost:8080/actuator/health

# Test frontend
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000

# Test via Nginx (the public-facing URL)
curl -s -o /dev/null -w "%{http_code}" http://YOUR_SERVER_IP_OR_DOMAIN

# Check for errors in logs
sudo journalctl -u kraken-backend --since "10 minutes ago" --no-pager
sudo journalctl -u kraken-frontend --since "10 minutes ago" --no-pager
sudo tail -20 /var/log/nginx/error.log
```

**Access the application:**
- **Website**: `http://YOUR_SERVER_IP_OR_DOMAIN` (or `https://` if SSL is set up)
- **API**: `http://YOUR_SERVER_IP_OR_DOMAIN/api/v1`
- **Swagger**: `http://YOUR_SERVER_IP_OR_DOMAIN/swagger-ui/index.html`
- **Default Login**: username `admin` / password you set in `ADMIN_INITIAL_PASSWORD`

---

## 4. Firewall Configuration (Both Methods)

```bash
# Install UFW (if not installed)
sudo apt install -y ufw

# Default policies
sudo ufw default deny incoming
sudo ufw default allow outgoing

# Allow SSH (IMPORTANT — don't lock yourself out!)
sudo ufw allow 22/tcp comment "SSH"

# Allow HTTP and HTTPS
sudo ufw allow 80/tcp comment "HTTP"
sudo ufw allow 443/tcp comment "HTTPS"

# --- Docker Method: also expose direct ports if not using Nginx ---
# sudo ufw allow 3000/tcp comment "Frontend"
# sudo ufw allow 8080/tcp comment "Backend API"

# --- Bare-Metal: Do NOT expose 3000 and 8080 directly —
# --- Nginx handles proxying on port 80/443 ---

# Enable the firewall
sudo ufw enable

# Check status
sudo ufw status verbose
```

> ⚠️ **Docker Note**: Docker modifies `iptables` directly, which can bypass UFW rules. If you're using Docker and want UFW to control Docker ports, add the following to `/etc/docker/daemon.json`:
> ```json
> { "iptables": false }
> ```
> Then restart Docker: `sudo systemctl restart docker`

---

## 5. Backup & Restore

### 5.1 Database Backup

**Docker Method:**
```bash
# Backup
docker exec kraken_db pg_dump -U kraken -d kraken_db -F c -f /tmp/kraken_backup.dump
docker cp kraken_db:/tmp/kraken_backup.dump ./backups/kraken_$(date +%Y%m%d_%H%M%S).dump

# Restore
docker cp ./backups/kraken_backup.dump kraken_db:/tmp/kraken_backup.dump
docker exec kraken_db pg_restore -U kraken -d kraken_db -c /tmp/kraken_backup.dump
```

**Bare-Metal:**
```bash
# Backup
sudo -u postgres pg_dump -d kraken_db -F c -f /var/backups/kraken_$(date +%Y%m%d_%H%M%S).dump

# Restore
sudo -u postgres pg_restore -d kraken_db -c /var/backups/kraken_backup.dump
```

### 5.2 Automated Daily Backups (Cron)

```bash
# Create backup script
sudo tee /opt/kraken/backup.sh << 'SCRIPT'
#!/bin/bash
BACKUP_DIR="/var/backups/kraken"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP_DIR"

# Database backup
sudo -u postgres pg_dump -d kraken_db -F c -f "$BACKUP_DIR/db_$TIMESTAMP.dump"

# Keep only last 7 days of backups
find "$BACKUP_DIR" -name "db_*.dump" -mtime +7 -delete

echo "[$TIMESTAMP] Backup completed: $BACKUP_DIR/db_$TIMESTAMP.dump"
SCRIPT

chmod +x /opt/kraken/backup.sh

# Add to crontab (runs daily at 2:00 AM)
(crontab -l 2>/dev/null; echo "0 2 * * * /opt/kraken/backup.sh >> /var/log/ussd/backup.log 2>&1") | crontab -
```

---

## 6. Troubleshooting

### Common Issues

| Problem | Cause | Solution |
|---------|-------|----------|
| Backend won't start | DB connection refused | Check PostgreSQL is running: `sudo systemctl status postgresql` |
| Backend won't start | Redis connection refused | Check Redis is running: `sudo systemctl status redis-server` |
| Frontend shows "API Error" | Wrong `NEXT_PUBLIC_API_URL` | Verify the URL, rebuild the frontend if changed |
| `docker compose up` fails | Port already in use | Check: `sudo lsof -i :5432 -i :8080 -i :3000` |
| Flyway migration fails | Schema mismatch | Check logs: `docker compose logs backend \| grep flyway` |
| 502 Bad Gateway (Nginx) | Backend/Frontend not running | Check: `sudo systemctl status kraken-backend kraken-frontend` |
| Cannot connect from browser | Firewall blocking | Check: `sudo ufw status` and ensure ports are open |
| Java OutOfMemoryError | Insufficient RAM | Increase `-XX:MaxRAMPercentage` or add more server RAM |
| Permission denied errors | Wrong file ownership | Fix: `sudo chown -R kraken-app:kraken-app /opt/kraken/backend` |

### Useful Diagnostic Commands

```bash
# --- Docker ---
docker compose ps                          # Container status
docker compose logs --tail=100 backend     # Last 100 backend log lines
docker exec -it kraken_db psql -U kraken -d kraken_db  # Enter DB shell
docker exec -it kraken_redis redis-cli     # Enter Redis shell

# --- Bare-Metal ---
sudo systemctl status kraken-backend       # Service status
sudo journalctl -u kraken-backend -n 100   # Last 100 journal lines
sudo journalctl -u kraken-backend -f       # Follow live logs
sudo tail -f /var/log/ussd/backend-stdout.log
psql -h localhost -U kraken -d kraken_db   # Enter DB shell
redis-cli -a YOUR_REDIS_PASSWORD           # Enter Redis shell

# --- General ---
free -h                                    # Memory usage
df -h                                      # Disk usage
htop                                       # CPU/Memory monitor
sudo netstat -tlnp                         # All listening ports
curl -v http://localhost:8080/actuator/health  # Verbose health check
```

---

## 7. Production Hardening Checklist

Before going live, ensure the following:

- [ ] **Secrets**: Verified `.env.example` was copied to `.env` and `YOUR_SERVER_IP` was replaced
- [ ] **Admin Password**: Changed from default after first login
- [ ] **Database**: `DB_PASSWORD` is strong (20+ chars, alphanumeric + symbols)
- [ ] **Redis**: Password set and strong
- [ ] **JWT Secret**: At least 32 characters, randomly generated
- [ ] **Encryption Secret**: Randomly generated, stored securely
- [ ] **HTTPS**: TLS/SSL certificate installed (Let's Encrypt or commercial)
- [ ] **Firewall**: UFW enabled, only ports 22, 80, 443 open to public
- [ ] **SSH**: Root login disabled, key-based auth only
- [ ] **Database**: PostgreSQL not exposed to the internet (bind to 127.0.0.1)
- [ ] **Redis**: Not exposed to the internet (bind to 127.0.0.1)
- [ ] **Backups**: Automated daily backups configured and tested
- [ ] **Monitoring**: Health endpoints monitored (e.g., UptimeRobot, Prometheus)
- [ ] **Logs**: Log rotation configured (`logrotate` or Docker logging driver)
- [ ] **OS Updates**: Unattended security updates enabled:
  ```bash
  sudo apt install -y unattended-upgrades
  sudo dpkg-reconfigure -plow unattended-upgrades
  ```
- [ ] **Swap**: Configured if RAM is limited:
  ```bash
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  ```

### Log Rotation (Bare-Metal)

```bash
sudo tee /etc/logrotate.d/kraken << 'LOGROTATE'
/var/log/ussd/*.log {
    daily
    missingok
    rotate 14
    compress
    delaycompress
    notifempty
    create 0640 kraken-app kraken-app
    sharedscripts
    postrotate
        systemctl restart kraken-backend kraken-frontend 2>/dev/null || true
    endscript
}
LOGROTATE
```

---

## Quick Reference Card

| Action | Docker Method | Bare-Metal Method |
|--------|--------------|-------------------|
| Start all | `docker compose up -d` | `sudo systemctl start postgresql redis-server kraken-backend kraken-frontend nginx` |
| Stop all | `docker compose down` | `sudo systemctl stop kraken-frontend kraken-backend` |
| Restart backend | `docker compose restart backend` | `sudo systemctl restart kraken-backend` |
| View backend logs | `docker compose logs -f backend` | `sudo journalctl -u kraken-backend -f` |
| Rebuild backend | `docker compose up -d --build backend` | `cd /opt/kraken/backend && ./mvnw clean package -DskipTests && sudo systemctl restart kraken-backend` |
| Rebuild frontend | `docker compose up -d --build frontend` | `cd /opt/kraken/frontend && npm ci && npm run build && cp -r public .next/standalone/public && cp -r .next/static .next/standalone/.next/static && sudo systemctl restart kraken-frontend` |
| DB shell | `docker exec -it kraken_db psql -U kraken -d kraken_db` | `psql -h localhost -U kraken -d kraken_db` |
| Redis shell | `docker exec -it kraken_redis redis-cli` | `redis-cli -a PASSWORD` |
| Health check | `curl localhost:8080/actuator/health` | `curl localhost:8080/actuator/health` |

---

*Guide generated for Kraken v1.0.0-SNAPSHOT — Last updated: April 2026*

