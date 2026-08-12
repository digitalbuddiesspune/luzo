# PotLudo Server — Deployment Guide

Spring Boot 3 + Kotlin **WebFlux** backend for PotLudo (identity, wallet, lobby, match engine, WebSockets).

**Requirements:** Java **21**, MongoDB, Redis, RabbitMQ (for operator wallet cashouts), and network access to the operator platform API.

---

## Architecture

```
┌─────────────┐     HTTP / WS      ┌──────────────────┐
│ Next.js     │ ─────────────────► │ potludo-server   │
│ client      │                    │ (Spring WebFlux) │
└─────────────┘                    └────────┬─────────┘
                                            │
              ┌─────────────────────────────┼─────────────────────────────┐
              ▼                             ▼                             ▼
       ┌────────────┐               ┌────────────┐               ┌─────────────────┐
       │ MongoDB    │               │ Redis      │               │ RabbitMQ        │
       │ (primary)  │               │ locks +    │               │ games_cashout   │
       └────────────┘               │ realtime   │               │ (winner credit) │
                                    └────────────┘               └─────────────────┘
                                            │
                                            ▼
                                    ┌─────────────────┐
                                    │ Operator API    │
                                    │ (debit HTTP)    │
                                    └─────────────────┘
```

| Component | Role |
|---|---|
| **MongoDB** | Sessions, rooms, matches, wallet ledger |
| **Redis** | Per-match/room locks, instance heartbeats, WebSocket fan-out pub/sub |
| **RabbitMQ** | Publish winner/refund credits to operator (`games_cashout`) |
| **Operator HTTP** | Debit entry fees at match start; fetch user balance |

---

## Prerequisites

| Item | Version / notes |
|---|---|
| **JDK** | 21 (build and runtime) |
| **MongoDB** | Atlas or self-hosted; connection string with database name |
| **Redis** | 7.x recommended; required for multi-instance and realtime |
| **RabbitMQ** | Required in production when using operator wallet credits |
| **Reverse proxy** | Nginx, Caddy, or cloud LB — must support **WebSocket upgrade** for `/ws/**` |

---

## Configuration

The server loads `server/.env` automatically via Spring:

```yaml
spring.config.import: optional:file:.env[.properties]
```

For production, prefer **environment variables** or a secrets manager instead of committing `.env`.

### Core infrastructure

| Variable | Required | Description |
|---|---|---|
| `MONGODB_URI` | Yes | MongoDB connection string (include user/password and cluster host) |
| `MONGODB_DATABASE` | Yes* | Database name if not embedded in URI |
| `REDIS_HOST` | Yes | Redis hostname |
| `REDIS_PORT` | Yes | Redis port (default `6379`) |
| `REDIS_USERNAME` | No | Redis ACL username |
| `REDIS_PASSWORD` | No | Redis password |
| `REDIS_SSL_ENABLED` | No | `true` for TLS Redis (e.g. managed cloud) |
| `AMQP_URI` | Yes (prod) | RabbitMQ URI, e.g. `amqp://user:pass@host:5672` |
| `PORT` | No | HTTP port (default `8080`) |

### Gameplay

| Variable | Default | Description |
|---|---|---|
| `APP_GAMEPLAY_TURN_TIMEOUT_SECONDS` | `30` | Seconds before a human turn times out |
| `APP_GAMEPLAY_MAX_MISSED_TURNS` | `2` | Total missed turns before auto-removal |
| `APP_GAMEPLAY_ROOM_MAX_PLAYERS` | `4` | Max seats per room |
| `APP_GAMEPLAY_ONLINE_ENTRY_FEE` | `100` | Entry fee amount (wallet units) |
| `APP_GAMEPLAY_LOBBY_WAIT_MILLIS` | `60000` | Lobby wait before auto-start |
| `APP_GAMEPLAY_ONLINE_PVP_REAL_PLAYER_THRESHOLD` | `0` | Min waiting real players to allow public PVP matchmaking (`0` = always allow) |
| `APP_GAMEPLAY_ROLL_DELAY_MILLIS` | `700` | Delay after dice roll animation |
| `APP_GAMEPLAY_BOT_MOVE_DELAY_MILLIS` | `850` | Delay before bot moves |
| `APP_GAMEPLAY_ADVANCE_DELAY_MILLIS` | `750` | Delay before passing turn |

### Session & wallet

| Variable | Default | Description |
|---|---|---|
| `APP_SESSION_TTL_DAYS` | `30` | Guest session lifetime |
| `APP_WALLET_CURRENCY` | `INR` | Display/settlement currency label |
| `APP_WALLET_GUEST_STARTING_BALANCE` | `100000` | Starting balance for non-operator guests |
| `APP_WALLET_PLATFORM_FEE_PER_PLAYER` | `10` | Flat platform fee per seat at settlement |
| `APP_WALLET_HOUSE_USER_ID` | `house` | Internal user id for house/platform ledger |
| `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS` | `0` | Legacy; live settlement uses flat fee above |

### Operator / wallet integration

| Variable | Description |
|---|---|
| `APP_OPERATOR_BASE_URL` | Operator API base, e.g. `https://api.example.com` |
| `APP_OPERATOR_LOGIN_PATH` | Login path (default `/operator/user/login`) |
| `APP_OPERATOR_USER_DETAIL_PATH` | User detail path |
| `APP_OPERATOR_BALANCE_PATH` | **Debit** path — entry fee HTTP debit at match start |
| `APP_OPERATOR_GAME_ID` | Game id sent to operator (e.g. `2`) |
| `APP_OPERATOR_CREDIT_EXCHANGE` | RabbitMQ exchange for cashouts (default `/games/admin`) |
| `APP_OPERATOR_CREDIT_QUEUE_NAME` | Queue name (default `games_cashout`) |
| `APP_OPERATOR_CREDIT_ROUTING_KEY` | Routing key (default `games_cashout`) |

**Important:** Winner credits go through **RabbitMQ**, not `APP_OPERATOR_BALANCE_PATH`. The balance path is **debit only**.

Optional RabbitMQ tuning (see `application.yml`): `APP_OPERATOR_CREDIT_DECLARE_TOPOLOGY`, DLQ settings, retry delays.

### Multi-instance / scaling

| Variable | Default | Description |
|---|---|---|
| `APP_INSTANCE_ID` | auto | Unique id per JVM (`HOSTNAME` if unset) |
| `APP_INSTANCE_LOCK_KEY_PREFIX` | `potludo:lock` | Redis key prefix for distributed locks |
| `APP_INSTANCE_HEARTBEAT_KEY_PREFIX` | `potludo:instance` | Redis heartbeat keys |
| `APP_INSTANCE_LOCK_TTL_MILLIS` | `5000` | Lock expiry |
| `APP_INSTANCE_HEARTBEAT_TTL_MILLIS` | `15000` | Heartbeat expiry |

Run **multiple replicas** only when all instances share the **same MongoDB, Redis, and RabbitMQ**. Use a load balancer with **sticky sessions** for WebSocket connections.

### Realtime & CORS

| Variable | Description |
|---|---|
| `APP_REALTIME_REDIS_CHANNEL` | Redis pub/sub channel for cross-instance match updates |
| `APP_REALTIME_WEB_RTC_ICE_SERVERS` | Comma-separated STUN/TURN URLs for WebRTC |
| `APP_REALTIME_WEB_RTC_TURN_USERNAME` | TURN username (optional) |
| `APP_REALTIME_WEB_RTC_TURN_CREDENTIAL` | TURN password (optional) |
| `APP_WEB_ALLOWED_ORIGIN_PATTERNS` | Comma-separated CORS origins for `/api/**` and `/ws/**` |

Add every frontend origin (production domain, Vercel preview, local dev). Example:

```dotenv
APP_WEB_ALLOWED_ORIGIN_PATTERNS=https://yourgame.com,https://*.vercel.app,http://localhost:3000
```

---

## Local development

### 1. Start Redis

From `server/`:

```bash
docker compose up -d
```

This starts Redis on `localhost:6379` only. MongoDB and RabbitMQ must be configured separately (Atlas + remote AMQP in `.env`).

### 2. Configure environment

Copy and edit `server/.env` with your MongoDB URI, Redis, AMQP, and operator settings.

### 3. Run the server

```bash
cd server
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
./gradlew bootRun
```

Server listens on `PORT` (default `8080`, or `8082` if set in `.env`).

### 4. Verify

```bash
curl -s http://localhost:8082/actuator/health | jq .
curl -s http://localhost:8082/actuator/health/readiness | jq .
```

---

## Build JAR (without Docker)

```bash
cd server
./gradlew bootJar --no-daemon
java -jar build/libs/*.jar
```

The fat JAR is produced under `server/build/libs/`.

---

## Docker deployment

### Build image

From repository root:

```bash
docker build -t potludo-server:latest ./server
```

The Dockerfile uses **JDK 21** multi-stage build: Gradle compiles the JAR, JRE 21 runs it.

### Run container

Pass env vars at runtime (do **not** bake `.env` into the image):

```bash
docker run -d \
  --name potludo-server \
  -p 8080:8080 \
  -e PORT=8080 \
  -e MONGODB_URI="mongodb+srv://..." \
  -e MONGODB_DATABASE=LudoProd \
  -e REDIS_HOST=redis.internal \
  -e REDIS_PORT=6379 \
  -e AMQP_URI="amqp://user:pass@rabbit.internal:5672" \
  -e APP_OPERATOR_BASE_URL=https://api.example.com \
  -e APP_OPERATOR_BALANCE_PATH=/service/operator/user/balance/v2 \
  -e APP_OPERATOR_CREDIT_EXCHANGE=/games/admin \
  -e APP_OPERATOR_CREDIT_ROUTING_KEY=games_cashout \
  -e APP_OPERATOR_GAME_ID=2 \
  -e APP_WEB_ALLOWED_ORIGIN_PATTERNS=https://yourgame.com \
  potludo-server:latest
```

**Note:** Image `EXPOSE`s `8080`. Set `PORT` to match your published port mapping.

### Docker Compose (production sketch)

Extend `docker-compose.yml` with the app service, external MongoDB/RabbitMQ URLs, and a shared Redis service. Keep secrets in env files ignored by git.

---

## Production checklist

### Before deploy

- [ ] MongoDB Atlas (or cluster) with backups and IP allowlist / VPC peering
- [ ] Redis reachable from all app instances (TLS if required)
- [ ] RabbitMQ exchange `/games/admin` and queue `games_cashout` exist (operator team usually owns topology)
- [ ] Operator API credentials and `APP_OPERATOR_GAME_ID` confirmed
- [ ] `APP_WEB_ALLOWED_ORIGIN_PATTERNS` includes production client URL(s)
- [ ] Secrets not committed to git (rotate any leaked credentials)
- [ ] Client `NEXT_PUBLIC_*` API URL points to this server

### Reverse proxy (Nginx example)

WebSockets must be proxied for live matches:

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
}
```

Spring uses `server.forward-headers-strategy: framework` so HTTPS and client IPs work behind a proxy.

### Health checks

| Endpoint | Use |
|---|---|
| `GET /actuator/health/liveness` | Kubernetes liveness |
| `GET /actuator/health/readiness` | Kubernetes readiness |
| `GET /actuator/health` | General health |
| `GET /actuator/prometheus` | Metrics (if scraped) |

RabbitMQ health probe is **disabled** in config (`management.health.rabbit.enabled: false`).

### Horizontal scaling

- Run **N** identical instances behind a load balancer
- Set unique `APP_INSTANCE_ID` per instance (or rely on `HOSTNAME`)
- Shared MongoDB + Redis + RabbitMQ are mandatory
- Enable **session affinity** for WebSocket routes (`/ws/matches/*`)
- Monitor Redis lock contention and MongoDB read load on the 150ms match scheduler

See `docs/match-wallet-money-flow.md` for wallet debit/credit behaviour in production.

---

## API surface (client integration)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/identity/guest` | Create guest session |
| `POST` | `/api/v1/lobby/online/join` | Join online lobby |
| `GET` | `/api/v1/matches/{matchId}` | Match snapshot |
| `POST` | `/api/v1/matches/{matchId}/moves` | Submit token move |
| `GET` | `/ws/matches/{matchId}?sessionToken=...` | Live match WebSocket |

All REST calls use header `X-Session-Token` except guest creation.

---

## Post-deploy verification

1. **Health:** `curl https://api.yourgame.com/actuator/health`
2. **CORS:** Open client from allowed origin; check browser console for CORS errors
3. **Guest session:** `POST /api/v1/identity/guest` → receive session token
4. **Online join:** Join lobby with operator-linked session; confirm debit in operator logs
5. **WebSocket:** Connect to `/ws/matches/{id}` during a live match; snapshots should stream
6. **Win flow:** Finish a match; verify `MATCH_PAYOUT` in history and RabbitMQ cashout message

Startup logs include environment diagnostics (without printing secrets):

```
Environment diagnostics: MONGODB_URI_present=true, REDIS_HOST=..., APP_OPERATOR_BASE_URL=...
```

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| CORS / “page not accessible” | Missing origin in `APP_WEB_ALLOWED_ORIGIN_PATTERNS` |
| `502` on operator debit | Wrong `APP_OPERATOR_BASE_URL` or balance path |
| Winner not credited on real wallet | RabbitMQ down, wrong exchange/routing key, or operator consumer not running |
| WebSocket disconnects immediately | Proxy missing `Upgrade` headers; or invalid `sessionToken` |
| Matches feel laggy under load | Scheduler scanning all active matches every 150ms — scale MongoDB / reduce concurrent matches |
| Redis errors on start | `REDIS_HOST` unreachable or TLS mismatch (`REDIS_SSL_ENABLED`) |
| Duplicate settlement | Should be blocked by idempotency key `wallet:settlement:{matchId}` — check logs if seen |

---

## Security notes

- Never commit `server/.env` with production credentials
- Rotate MongoDB, Redis, RabbitMQ, and operator credentials if exposed
- Restrict MongoDB network access to app servers only
- Use HTTPS everywhere; terminate TLS at the reverse proxy or load balancer
- Consider disabling or protecting `/api/v1/debug/**` in production

---

## Related docs

- [Match wallet money flow](./match-wallet-money-flow.md) — debit, pot, credit, bot/house wins
- [Server README](../server/README.md) — module overview and local API list
