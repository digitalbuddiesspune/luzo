# PotLudo Rounds Server — Deployment Guide

Standalone **Node.js 20 + Express** API for Ludo round history, admin profit/loss dashboards, and platform settings. It **reads** match data written by the main Kotlin game server and stores its own admin/session data in the **same MongoDB database**.

**Not the live game server** — deploy this separately from `server/` (Spring Boot).

---

## Architecture

```
┌──────────────┐                    ┌─────────────────────┐
│ Admin UI     │ ─── CORS + REST ─► │ rounds-server       │
│ (Vite/React) │                    │ (Node / Express)    │
└──────────────┘                    └──────────┬──────────┘
                                               │
                                               ▼
                                    ┌─────────────────────┐
                                    │ MongoDB             │
                                    │ (shared with game   │
                                    │  server)            │
                                    │                     │
                                    │ reads: matches,     │
                                    │        rooms        │
                                    │ writes: admin_*,    │
                                    │         platform_   │
                                    │         settings    │
                                    └─────────────────────┘
         ┌─────────────────────┐
         │ Main game server    │ ──writes──► matches, rooms
         │ (server/)           │
         └─────────────────────┘
```

| Dependency | Required | Role |
|---|---|---|
| **MongoDB** | Yes | Same cluster/DB as main game server |
| **Redis** | No | Not used |
| **RabbitMQ** | No | Not used |

---

## Prerequisites

| Item | Notes |
|---|---|
| **Node.js** | ≥ 20 (`package.json` engines) |
| **MongoDB** | Atlas or self-hosted; must contain `matches` and `rooms` from game server |
| **Admin frontend** | Optional; `admin/` app points at this API |

---

## Configuration

Environment is loaded from `rounds-server/.env` (via `dotenv` in `src/config/env.js`).

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `MONGODB_URI` | **Yes** | — | MongoDB connection string |
| `MONGODB_DATABASE` | No | `Ludo` | Database name (must match game server DB) |
| `PORT` | No | `8083` | HTTP listen port |
| `APP_WALLET_CURRENCY` | No | `INR` | Currency label in round responses |
| `APP_WALLET_HOUSE_USER_ID` | No | `house` | House user id for P&L calculations |
| `APP_WALLET_PLATFORM_FEE_PER_PLAYER` | No | `10` | Default platform fee per seat (used until Mongo override exists) |
| `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS` | No | `0` | Legacy display rake (0–10000 bps); live fee prefers `platform_settings` |
| `CORS_ORIGINS` | No | `http://localhost:5174,...` | Comma-separated admin UI origins allowed to call this API |

### Example `.env` (local)

```dotenv
MONGODB_URI=mongodb+srv://USER:PASS@cluster.mongodb.net/LudoProd?appName=Cluster0
MONGODB_DATABASE=LudoProd
PORT=8083
APP_WALLET_CURRENCY=INR
APP_WALLET_HOUSE_USER_ID=house
APP_WALLET_PLATFORM_FEE_PER_PLAYER=10
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
CORS_ORIGINS=http://localhost:5174,http://127.0.0.1:5174,https://your-admin.example.com
```

### Render / PaaS note

On **Render** and similar platforms, **`PORT` is injected by the host** — do not hard-code it in the dashboard. Set `MONGODB_URI`, wallet vars, and `CORS_ORIGINS` only.

Use the same `MONGODB_URI` and `MONGODB_DATABASE` as production game server so round history stays in sync.

---

## Local development

### 1. Install dependencies

```bash
cd rounds-server
npm install
```

### 2. Configure `.env`

Copy from `.env.example` and set `MONGODB_URI` to your cluster.

- **Node on host:** `mongodb://localhost:27017` or Atlas URI  
- **Docker → host MongoDB:** `mongodb://host.docker.internal:27017`

### 3. Run

```bash
npm start
# or with auto-reload:
npm run dev
```

API listens on `http://localhost:8083` (or your `PORT`).

### 4. Verify

```bash
curl -s http://localhost:8083/health | jq .
```

Expected when MongoDB is reachable:

```json
{
  "status": "ok",
  "service": "ludo-rounds-server",
  "database": "connected",
  "timestamp": "..."
}
```

---

## Docker deployment

### Build and run (docker compose)

From `rounds-server/`:

```bash
cp .env.example .env
# edit .env with real MONGODB_URI and CORS_ORIGINS
docker compose up --build -d
```

Compose maps `${PORT:-8083}` and runs a **health check** against `/health` every 15s.

### Build image manually

```bash
docker build -t ludo-rounds-api:latest ./rounds-server
```

### Run container

```bash
docker run -d \
  --name ludo-rounds-api \
  -p 8083:8083 \
  -e MONGODB_URI="mongodb+srv://..." \
  -e MONGODB_DATABASE=LudoProd \
  -e PORT=8083 \
  -e CORS_ORIGINS="https://your-admin.example.com" \
  -e APP_WALLET_PLATFORM_FEE_PER_PLAYER=10 \
  ludo-rounds-api:latest
```

The Dockerfile uses `node:20-alpine`, runs as non-root `node` user, and exposes port **8083**.

---

## Production checklist

### Before deploy

- [ ] `MONGODB_URI` points to the **same database** as the live game server
- [ ] `MONGODB_DATABASE` matches game server (`MONGODB_DATABASE` in `server/.env`)
- [ ] `CORS_ORIGINS` includes every admin UI origin (production URL, no trailing slash)
- [ ] Admin app env vars point to this service (see below)
- [ ] Default admin password changed after first login (see Security)
- [ ] `.env` not committed to git

### Admin frontend wiring

The `admin/` Vite app calls this service. Set in admin build env (e.g. Render static site):

```dotenv
VITE_API_BASE_URL=https://rounds-api.example.com/api/v1/admin/profit-loss
VITE_SETTINGS_API_BASE_URL=https://rounds-api.example.com/api/v1/admin/settings
VITE_AUTH_API_BASE_URL=https://rounds-api.example.com/api/v1/admin/auth
```

Local defaults are in `admin/.env.example`. Vite dev proxy also targets `http://localhost:8083`.

### Reverse proxy

Standard HTTP reverse proxy (Nginx, Caddy, Render, etc.). No WebSocket requirement for this service.

Ensure **CORS** allows the admin origin, or proxy admin and API under the same origin.

Example Nginx location:

```nginx
location / {
    proxy_pass http://127.0.0.1:8083;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

### Health checks

| Endpoint | Use |
|---|---|
| `GET /health` | Load balancer / Docker / k8s probe |

Returns **non-200** if MongoDB ping fails.

---

## API surface

### Public / operator

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | No | Service + MongoDB health |
| `GET` | `/api/v1/rounds/ludo` | No | Paginated finished rounds (JSON) |
| `GET` | `/api/v1/rounds/ludo/single` | No | Single round statement (HTML) |

Query params for `/ludo/single`: `user_id`, `operator_id`, `lobby_id` (required).

Full contract: [`rounds-server/LUDO_ROUNDS_API.md`](../rounds-server/LUDO_ROUNDS_API.md).

### Admin (Bearer token)

Login via `POST /api/v1/admin/auth/login`, then send `Authorization: Bearer <token>`.

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/admin/auth/login` | Admin login |
| `POST` | `/api/v1/admin/auth/logout` | Invalidate session |
| `GET` | `/api/v1/admin/auth/me` | Current admin profile |
| `GET` | `/api/v1/admin/profit-loss/summary` | P&L summary |
| `GET` | `/api/v1/admin/profit-loss/games` | Game list with filters |
| `DELETE` | `/api/v1/admin/profit-loss/games/:roundId` | Remove game from admin view |
| `GET` | `/api/v1/admin/profit-loss/users` | User-level P&L |
| `GET` | `/api/v1/admin/settings` | Platform fee settings |
| `PUT` | `/api/v1/admin/settings` | Update platform fee |

Admin sessions expire after **7 days** (`admin_sessions` TTL index).

---

## MongoDB collections

| Collection | Written by | Purpose |
|---|---|---|
| `matches` | Game server | Round history source |
| `rooms` | Game server | Join reservations, seats, pot |
| `platform_settings` | Rounds server | Global `platformFeePerPlayer` (synced conceptually with game server) |
| `admin_accounts` | Rounds server | Admin users |
| `admin_sessions` | Rounds server | Admin session tokens |

Indexes are created on startup for `admin_accounts` and `admin_sessions`.

---

## Post-deploy verification

1. **Health:** `curl https://rounds-api.example.com/health`
2. **Rounds list:** `curl "https://rounds-api.example.com/api/v1/rounds/ludo?page=1&limit=5"`
3. **Admin login:** `POST /api/v1/admin/auth/login` with admin credentials
4. **Admin UI:** Open admin dashboard; confirm games load and CORS has no errors
5. **After a live match:** Confirm new finished match appears in `/api/v1/rounds/ludo`

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `MONGODB_URI is required` on start | Missing or empty env var |
| Health returns error / 503 | Wrong URI, IP allowlist, or DB name |
| Empty rounds list | Wrong `MONGODB_DATABASE`; or no `FINISHED` matches yet |
| Admin CORS error in browser | Admin origin missing from `CORS_ORIGINS` |
| Admin login fails | Wrong credentials; check `admin_accounts` collection |
| P&L fee mismatch vs game server | Update `platform_settings` via admin or align `APP_WALLET_PLATFORM_FEE_PER_PLAYER` with `server` config |
| Docker cannot reach MongoDB on host | Use `host.docker.internal` (Mac/Windows) or host network / Atlas URI |

---

## Security notes

### Default admin account

On **first startup**, if no admin exists, the server seeds:

- Email: `admin@gmail.com`
- Password: `123456`

**Change this immediately in production** (create a new admin in DB or update password hash) and restrict network access to admin routes.

### Secrets

- Never commit `rounds-server/.env` with production credentials
- Use the same MongoDB credentials as game server only if this service is trusted; consider read-only DB user for strict separation (would require code changes for admin write paths)

### CORS

Only origins listed in `CORS_ORIGINS` receive `Access-Control-Allow-Origin`. Admin routes use `Authorization` header with credentials support when origin matches.

---

## Scaling

- **Stateless** HTTP API — scale horizontally behind a load balancer
- All instances must share the **same MongoDB**
- No sticky sessions required (admin token is in `Authorization` header)
- Read-heavy workload; size MongoDB for query patterns on `matches` (`status: FINISHED`, sort by `updatedAt`)

For high traffic on `/api/v1/rounds/ludo`, add MongoDB indexes on `matches.status` + `matches.updatedAt` if not already present.

---

## Related docs

- [Rounds API contract](../rounds-server/LUDO_ROUNDS_API.md)
- [Rounds server README](../rounds-server/README.md)
- [Game server deployment](./server-deployment.md)
- [Match wallet money flow](./match-wallet-money-flow.md)
