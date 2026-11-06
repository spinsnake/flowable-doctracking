---
name: pts-docker-deploy
description: Deploy Project Tracking System (PTS) to the MFU Beta Docker host or any Docker server. Supports dual deployment paths (automated via GitLab CI/CD on git push to dev, or direct script-based release via scripts/deploy-docker-server.sh). Use when asked to push, deploy, build, run, verify, or repeat a PTS Docker release safely.
---

# PTS Docker Deploy

Standardized release and deployment guide for **Project Tracking System (PTS)** at Mae Fah Luang University.

Supports two complementary deployment mechanisms:
1. **Automated CI/CD Deployment:** Triggered on `git push origin dev` via `.gitlab-ci.yml`.
2. **Direct CLI / Script Deployment:** Executed via `scripts/deploy-docker-server.sh` from the repository root.

---

## Infrastructure & Hosting Architecture

| Parameter | Value | Description |
| :--- | :--- | :--- |
| **Target Host** | `192.168.11.90` | MFU Internal Beta / UAT Server |
| **Public Hostname** | `https://pts.beta.mfu.ac.th` | Wildcard Let's Encrypt TLS terminated by Host Nginx |
| **Published Loopback Port** | `8096` (`192.168.11.90:8096`) | Bound to Nuxt 3 Web container |
| **Internal API Port** | `8889` | NestJS backend container (`api`) |
| **Database** | PostgreSQL 16 Alpine (`postgres:5432`) | Persistent Docker volume `postgres_data` |
| **Cache / Queue** | Redis 7 Alpine (`redis:6379`) | In-memory cache and session store |
| **Remote Directory** | `/opt/pts-beta/app` | Application source and runtime Compose project |
| **Compose File** | `docker-compose.beta.yml` | Multi-container stack definition |
| **SSH Identity** | `uatenv@192.168.11.90` | Key: `~/.ssh/pts_gitlab_deploy` or GitLab CI `$SSH_PRIVATE_KEY` |

---

## Deployment Workflows

### Method 1: Push to Git (Automated GitLab CI/CD)

The repository's `.gitlab-ci.yml` pipeline automatically deploys any commit pushed to the `dev` branch:

1. **Commit your changes:**
   ```bash
   git add .
   git commit -m "feat(scope): your commit description"
   ```

2. **Push to the `dev` branch:**
   ```bash
   git push origin dev
   ```

3. **Pipeline Stages:**
   - `test`: Runs unit tests and linting.
   - `build`: Builds NestJS API (`backend/dist`) and Nuxt 3 SSR (`frontend/.output`).
   - `deploy_beta`: Automatically syncs source to `/opt/pts-beta/app/`, builds Docker images tagged with commit SHA, runs migrations, and brings up containers via `docker compose -f docker-compose.beta.yml up -d`.

---

### Method 2: Direct Script-Based Deploy (`scripts/deploy-docker-server.sh`)

Use the helper script when you need an immediate release, manual intervention, or local testing before pushing to remote:

```bash
./scripts/deploy-docker-server.sh
```

#### What the Script Does:
1. **Reads Configuration:** Automatically detects configuration from `.env.beta` (git-ignored) or environment variables.
2. **Preflight Check:** Verifies SSH and Docker Engine connectivity on `192.168.11.90`.
3. **File Sync:** Synchronizes repository files to `/opt/pts-beta/app/` using `rsync` (excludes `.git`, `node_modules`, `.nuxt`, and secrets).
4. **Docker Image Build:** Builds `pts-beta-api:<sha>` and `pts-beta-web:<sha>`.
5. **Database Migration & Launch:** Starts Postgres, applies TypeORM migrations via the ephemeral `migrate` service, and starts API and Web services.
6. **Multi-layer Verification:**
   - Container states (`docker compose -f docker-compose.beta.yml ps`)
   - Internal API Health (`GET /api/v1/health`)
   - Local Web Endpoint (`http://192.168.11.90:8096/login`)
   - Public HTTPS Endpoint (`https://pts.beta.mfu.ac.th/login`)

#### Override Parameters (Optional):
```bash
BETA_SSH_HOST=192.168.11.90 \
BETA_SSH_USER=uatenv \
APP_PUBLISHED_PORT=8096 \
./scripts/deploy-docker-server.sh
```

---

## Safety & Operational Guardrails

- **Zero-Secret Leakage:** Never commit `.env.beta`, SSH private keys, or database passwords to Git. All secrets must remain in `.env.beta` locally or in `/opt/pts-beta/.env` remotely.
- **Database Safety:** Never run destructive database drops or migrations without prior backup. PostgreSQL volume `postgres_data` must be preserved across deployments.
- **Nginx Boundary:** Host Nginx owns ports `80` and `443` and handles SSL termination. The application must only bind to its designated loopback/host port (`8096`).
- **Health Confirmation:** A deployment is only considered successful when both the container health checks pass (`healthy`) and public HTTPS returns HTTP 200.
