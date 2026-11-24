# Local Deployment — Fedora Workstation 44

Deploy Kraken directly on your **local Fedora Workstation 44** machine with internet access.

## One-Command Deploy

```bash
cd /home/khld/SUDATEL/ussdGateway
chmod +x scripts/local-deploy/deploy-local.sh
sudo bash scripts/local-deploy/deploy-local.sh [SERVER_IP]
```

If you omit `SERVER_IP`, it auto-detects your machine's primary IP.

## What It Does

1. **Installs** PostgreSQL 17, Redis 7+, Nginx, Node.js 20 via `dnf`
2. **Configures** PostgreSQL (initializes + creates `kraken_db` + user), Redis (password), Nginx (reverse proxy)
3. **Builds** the backend JAR and frontend standalone from source
4. **Creates** systemd services for backend + frontend
5. **Starts** everything and verifies health

## What Gets Installed

| Component    | Version  | Method      |
|-------------|----------|-------------|
| Java        | Existing | Pre-installed |
| Node.js 20  | LTS      | dnf         |
| PostgreSQL  | 17       | dnf         |
| Redis       | 7+       | dnf         |
| Nginx       | latest   | dnf         |

## After Deployment

```
http://<YOUR_IP>              → Frontend
http://<YOUR_IP>/api/v1       → Backend API
http://<YOUR_IP>/swagger-ui/  → API Docs

Default login: admin / Admin@Kraken2026!
```

## Re-deploying

Just run the script again — it's idempotent. It will rebuild the app and restart services.

## Logs

```bash
sudo tail -f /var/log/ussd/backend-stdout.log
sudo tail -f /var/log/ussd/frontend-stdout.log
sudo journalctl -u kraken-backend -f
sudo journalctl -u kraken-frontend -f
```

