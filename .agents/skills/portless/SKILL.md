---
name: portless
description: >
  Manage local development URLs and reverse proxy using portless (Vercel Labs).
  Replaces arbitrary port numbers with clean, stable, named .localhost HTTPS/2 URLs
  (e.g. https://myapp.localhost, https://pts.localhost). Supports single projects,
  monorepos, static aliases (Docker/external ports), LAN testing, and Tailscale sharing.
  WHEN: "portless", "domain local", "https localhost", "localhost name", "port proxy",
  "map port to domain", "ตั้งชื่อโดเมน local", "รัน portless", "local ssl", "local https".
---

# Portless Skill

**Portless** replaces arbitrary port numbers (e.g., `localhost:3000`, `localhost:8888`) with stable, named `.localhost` URLs with automatic HTTPS/2 for local development.

---

## 1. Quick Reference & Core Commands

| Action | Command | Example / Note |
| :--- | :--- | :--- |
| **Run app with explicit name** | `portless <name> <command>` | `portless myapp npm run dev` ➔ `https://myapp.localhost` |
| **Run app inferring name** | `portless` or `portless run` | Runs `"dev"` script in `package.json` |
| **Monorepo run all** | `portless` at repo root | Reads `portless.json` workspaces |
| **Register static port / Docker** | `portless alias <name> <port>` | `portless alias pts 8888` ➔ `https://pts.localhost` |
| **Remove static alias** | `portless alias --remove <name>` | `portless alias --remove pts` |
| **List active routes** | `portless list` | Shows active `.localhost` mappings |
| **Start proxy (standard port 443)** | `portless proxy start` | Needs `sudo` on macOS/Linux |
| **Start proxy (unprivileged port)** | `portless proxy start --port 1355` | Does not require sudo |
| **Stop background proxy** | `portless proxy stop` | Stops proxy daemon |
| **Trust root CA** | `portless trust` | Adds CA to system trust store for green HTTPS |
| **Sync hosts (Safari support)** | `portless hosts sync` | Adds routes to `/etc/hosts` |
| **Clean all state** | `portless clean` | Removes certificates, trust, and state |

---

## 2. Monorepo Configuration (`portless.json`)

Place `portless.json` at the root of any monorepo (npm workspaces, pnpm, yarn, bun):

```json
{
  "apps": {
    "frontend": { "name": "pts" },
    "backend": { "name": "api.pts" }
  }
}
```

Running `portless` at the root will start both packages with:
- `frontend` ➔ `https://pts.localhost`
- `backend` ➔ `https://api.pts.localhost`

---

## 3. Important Framework Gotchas

1. **IPv4 vs IPv6 Binding**:
   - Modern frameworks (Nuxt 3 / Vite) on Node 20+ often bind to IPv6 `::1` only when started with `--port`.
   - **Fix**: Always specify `--host 0.0.0.0` or `--host 127.0.0.1` so Portless proxy can forward traffic cleanly via IPv4 loopback.
   - Example in `package.json`:
     ```json
     "dev": "nuxt dev --host 0.0.0.0 --port 8888"
     ```

2. **Privileged Ports vs Non-Privileged Ports**:
   - Port 443 (default HTTPS): Requires `sudo` once to bind.
   - Unprivileged port: If running in non-interactive CI or restricted shell, use `--port 1355` (e.g. `portless proxy start --port 1355`).

3. **Subdomains & Routing**:
   - `.localhost` subdomains resolve automatically to `127.0.0.1` in Chrome, Firefox, and Edge.
   - For Safari, run `portless hosts sync` if subdomains fail to resolve.
