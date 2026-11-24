# Offline Deploy — Rebuild & Update Guide

Use these scripts when you fix a bug or make changes and need to push the update to the **offline production server** — without doing a full reinstall.

---

## Scripts Overview

| Script | Runs On | Purpose |
|--------|---------|---------|
| `rebuild-artifacts.sh` | Dev laptop | Rebuild backend JAR and/or frontend, output to `offline-bundle/` |
| `apply-update.sh` | Production server | Apply the new artifact(s) and restart services |

---

## Step-by-Step Workflow

### 1 — Make your code changes on the dev laptop

Edit backend Java files, frontend TypeScript, config files, etc.

---

### 2 — Rebuild the artifact(s) on the dev laptop

```bash
# From the project root
./scripts/offline-deploy/rebuild-artifacts.sh both       # rebuild everything (default)
./scripts/offline-deploy/rebuild-artifacts.sh backend    # backend JAR only
./scripts/offline-deploy/rebuild-artifacts.sh frontend   # frontend only
```

Output will be placed in `offline-bundle/`:

```
offline-bundle/
  kraken-backend.jar                   ← new backend JAR
  kraken-frontend-standalone.tar.gz    ← new frontend build
```

---

### 3 — Copy artifact(s) to the production server

```bash
# Both
scp offline-bundle/kraken-backend.jar \
    offline-bundle/kraken-frontend-standalone.tar.gz \
    user@SERVER_IP:/tmp/

# Backend only
scp offline-bundle/kraken-backend.jar user@SERVER_IP:/tmp/

# Frontend only
scp offline-bundle/kraken-frontend-standalone.tar.gz user@SERVER_IP:/tmp/
```

> You can also use **FileZilla** or **XFTP** to transfer if SCP is not available.

---

### 4 — Copy `apply-update.sh` to the server *(first time only)*

```bash
scp scripts/offline-deploy/apply-update.sh user@SERVER_IP:/opt/kraken/
```

You only need to do this once. The script stays on the server for future updates.

---

### 5 — Apply the update on the production server

SSH into the server:

```bash
ssh user@SERVER_IP
```

Then run:

```bash
sudo bash /opt/kraken/apply-update.sh both       # apply both
sudo bash /opt/kraken/apply-update.sh backend    # backend only
sudo bash /opt/kraken/apply-update.sh frontend   # frontend only
```

The script will:
- Stop the service
- Replace the artifact
- Start the service
- Wait for health check
- Report success or failure

---

## What Each Script Does Internally

### `rebuild-artifacts.sh` (dev laptop)

**Backend:**
1. Runs `./mvnw clean package -DskipTests`
2. Copies the JAR to `offline-bundle/kraken-backend.jar`

**Frontend:**
1. Runs `npm ci` then `npm run build` with `NEXT_PUBLIC_API_URL=/api/v1` (relative — works on any server IP)
2. Injects `public/` and `.next/static/` into the standalone output
3. Packages it as `offline-bundle/kraken-frontend-standalone.tar.gz`

---

### `apply-update.sh` (production server)

**Backend:**
1. Stops `kraken-backend` service
2. Copies `/tmp/kraken-backend.jar` → `/opt/kraken/backend/target/kraken-1.0.0-SNAPSHOT.jar`
3. Starts the service and waits up to 90s for health check at `http://localhost:8080/actuator/health`

**Frontend:**
1. Stops `kraken-frontend` service
2. Wipes `/opt/kraken/frontend/` and extracts `/tmp/kraken-frontend-standalone.tar.gz`
3. Starts the service and checks `http://localhost:3000`

---

## Troubleshooting

**Backend not healthy after update:**
```bash
sudo journalctl -u kraken-backend -n 50 --no-pager
sudo tail -f /var/log/ussd/backend-stdout.log
```

**Frontend not responding after update:**
```bash
sudo journalctl -u kraken-frontend -n 30 --no-pager
sudo systemctl status kraken-frontend
```

**Rollback — restart services without changes:**
```bash
sudo systemctl restart kraken-backend
sudo systemctl restart kraken-frontend
```

