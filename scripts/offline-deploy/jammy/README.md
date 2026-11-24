# Offline Deployment — Ubuntu 22.04 LTS (Jammy Jellyfish)

Deploy Kraken to an **air-gapped Ubuntu 22.04** server with zero internet access.

## Workflow

```
YOUR DEV LAPTOP (has internet + Docker)
  │
  │  Step 1: Build JAR + frontend
  │  Step 2: Download .deb packages via Docker
  │  Step 3: Create bundle tarball
  │
  ▼
USB DRIVE / NETWORK SHARE / SCP
  │
  ▼
PRODUCTION SERVER (Ubuntu 22.04, no internet)
  │
  │  Step 4: Extract and run install-on-server.sh
  │
  ▼
✅ DEPLOYED!
```

## Step-by-Step Commands

### On your Dev Laptop:

```bash
cd /home/khld/SUDATEL/ussdGateway

# Step 1: Build backend JAR + frontend (takes ~3-5 min)
chmod +x scripts/offline-deploy/build-artifacts.sh
./scripts/offline-deploy/build-artifacts.sh 192.168.x.x   # ← your server IP

# Step 2: Download .deb packages (requires Docker, takes ~5-10 min)
chmod +x scripts/offline-deploy/jammy/download-packages.sh
./scripts/offline-deploy/jammy/download-packages.sh

# Step 3: Create the final bundle
chmod +x scripts/offline-deploy/jammy/create-bundle.sh
./scripts/offline-deploy/jammy/create-bundle.sh
```

Output: `kraken-bundle-jammy.tar.gz` (~300-500 MB)

### Transfer to the Production Server:

Copy via USB, SCP, WinSCP, or any file transfer method:
```bash
scp kraken-bundle-jammy.tar.gz user@SERVER_IP:/tmp/
```

### On the Production Server:

```bash
cd /tmp
tar -xzf kraken-bundle-jammy.tar.gz
sudo bash install-on-server.sh 192.168.x.x  # ← your server IP
```

That's it! The install script handles everything automatically.

## What Gets Installed

| Component    | Version  | Method                 |
|-------------|----------|------------------------|
| Java 21     | Temurin  | .deb or binary tarball |
| Node.js 20  | LTS      | .deb or binary tarball |
| PostgreSQL  | 15       | .deb packages          |
| Redis       | 7        | .deb packages          |
| Nginx       | latest   | .deb packages          |

## Files in this Folder

| File | Run Where | Purpose |
|------|-----------|---------|
| `download-packages.sh` | Dev laptop | Downloads .deb packages via Docker |
| `create-bundle.sh` | Dev laptop | Creates the transfer tarball |
| `install-on-server.sh` | Prod server | Installs everything offline |

## Re-deploying / Updating

To update the application code only (without re-downloading .deb packages):

1. Re-run `build-artifacts.sh` with the server IP
2. Re-run `create-bundle.sh` (it reuses existing `debs-jammy/`)
3. Transfer and re-run `install-on-server.sh` on the server
