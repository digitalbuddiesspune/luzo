# Configuration

This document is the runtime configuration reference for local development and
deployment.

## Applications

### Client

Location: `client/`

Key commands:

```bash
npm install
npm run dev
npm run build
npm run start
```

Primary client runtime variable:

```dotenv
NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8080
NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=false
```

Important note:

The client code falls back to `http://127.0.0.1:8082` if
`NEXT_PUBLIC_API_BASE_URL` is not set. Keep this aligned with the server port
you actually run in local and production environments.

`NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=true` requires operator launch query
parameters and disables guest fallback in the UI.

### Server

Location: `server/`

Key commands:

```bash
docker compose up -d
gradle build
gradle bootRun
```

Java requirement:

- Java 21 toolchain

### Round History Server

Location: `rounds-server/`

Key commands:

```bash
npm install
npm start
docker compose up --build
```

Node.js requirement:

- Node.js 20 or newer

Primary runtime variables:

```dotenv
MONGODB_URI=mongodb://host.docker.internal:27017
MONGODB_DATABASE=Ludo
PORT=8083
APP_WALLET_CURRENCY=INR
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
```

The service loads `rounds-server/.env`, reads the main game's `matches` and
`rooms` collections, and serves completed round history at
`/api/v1/rounds/ludo`.

## Server Environment Variables

The server loads `server/.env` through Spring config import.

Reference file:

- `server/.env.example`

### Database and network

```dotenv
MONGODB_URI=mongodb://localhost:27017/potludo
MONGODB_DATABASE=potludo
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_USERNAME=
REDIS_PASSWORD=
REDIS_SSL_ENABLED=false
PORT=8080
```

### Session

```dotenv
APP_SESSION_TTL_DAYS=30
```

### Gameplay

```dotenv
APP_GAMEPLAY_TURN_TIMEOUT_SECONDS=30
APP_GAMEPLAY_ROOM_MAX_PLAYERS=4
APP_GAMEPLAY_ONLINE_ENTRY_FEE=100
APP_GAMEPLAY_LOBBY_WAIT_MILLIS=60000
APP_GAMEPLAY_ONLINE_PVP_REAL_PLAYER_THRESHOLD=25
APP_GAMEPLAY_ROLL_DELAY_MILLIS=700
APP_GAMEPLAY_BOT_MOVE_DELAY_MILLIS=850
APP_GAMEPLAY_ADVANCE_DELAY_MILLIS=750
```

### Wallet

```dotenv
APP_WALLET_CURRENCY=INR
APP_WALLET_GUEST_STARTING_BALANCE=100000
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
APP_WALLET_HOUSE_USER_ID=house
```

### Operator gateway

```dotenv
APP_OPERATOR_BASE_URL=https://sp.adminsportal.com
APP_OPERATOR_LOGIN_PATH=/operator/user/login
APP_OPERATOR_USER_DETAIL_PATH=/service/user/detail
APP_OPERATOR_BALANCE_PATH=/service/operator/user/balance/v2
AMQP_URI=amqp://guest:guest@localhost:5672
APP_OPERATOR_CREDIT_EXCHANGE=/games/admin
APP_OPERATOR_CREDIT_QUEUE_NAME=games_cashout
APP_OPERATOR_CREDIT_ROUTING_KEY=games_cashout
APP_OPERATOR_GAME_ID=2
```

### Instance coordination

```dotenv
APP_INSTANCE_ID=
APP_INSTANCE_LOCK_KEY_PREFIX=potludo:lock
APP_INSTANCE_HEARTBEAT_KEY_PREFIX=potludo:instance
APP_INSTANCE_LOCK_TTL_MILLIS=5000
APP_INSTANCE_HEARTBEAT_TTL_MILLIS=15000
```

### Realtime / WebRTC

```dotenv
APP_REALTIME_REDIS_CHANNEL=potludo:realtime
APP_REALTIME_WEB_RTC_ICE_SERVERS=stun:stun.l.google.com:19302
APP_REALTIME_WEB_RTC_TURN_USERNAME=
APP_REALTIME_WEB_RTC_TURN_CREDENTIAL=
```

### Web / CORS

```dotenv
APP_WEB_ALLOWED_ORIGIN_PATTERNS=http://localhost:3000,http://127.0.0.1:3000,http://localhost:3005,http://127.0.0.1:3005,https://*.vercel.app
```

## Round History Environment Variables

The round-history service loads `rounds-server/.env` through `dotenv`.

Reference file:

- `rounds-server/.env.example`

```dotenv
MONGODB_URI=mongodb://host.docker.internal:27017
MONGODB_DATABASE=Ludo
PORT=8083
APP_WALLET_CURRENCY=INR
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
```

Notes:

- `MONGODB_URI` is required.
- `PORT` must be an integer from `1` to `65535`; it defaults to `8083`.
- `MONGODB_DATABASE` defaults to `Ludo`.
- `APP_WALLET_CURRENCY` defaults to `INR` and is uppercased.
- `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS` must be an integer from `0` to
  `10000`; it defaults to `0`.
- Use `host.docker.internal` when the Dockerized rounds service connects to a
  MongoDB instance running on the host machine. Use `localhost` for a direct
  local Node.js run.

## Runtime Defaults

Defaults are defined in:

- `server/src/main/resources/application.yml`
- `server/src/main/kotlin/com/craft/ludo/shared/config/AppProperties.kt`

The deployment contract should treat `.env` values as authoritative overrides.

## Local Development Checklist

1. Start MongoDB and Redis.
2. Copy `server/.env.example` to `server/.env` if needed.
3. Verify `PORT` and `NEXT_PUBLIC_API_BASE_URL` match.
4. Configure RabbitMQ if testing operator credit payouts.
5. Run the backend.
6. Run the frontend.
7. Confirm guest session creation, wallet load, room creation, and match sync.
8. If testing reporting flows, start `rounds-server/` and confirm
   `GET http://localhost:8083/health` returns `database: "connected"`.

## Deployment Guidance

For production deployments:

- store secrets outside committed files
- inject environment variables from the hosting platform
- set the client API base URL explicitly
- set `NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED` deliberately for the deployed
  frontend
- configure operator gateway values only from trusted secret/config storage
- configure a production TURN service before using peer features
- protect actuator endpoints with network policy or auth
- confirm database indexes and backup policy on MongoDB
- deploy the round-history service only with read access to the production
  gameplay database

## Monitoring Hooks

The backend exposes:

- health
- info
- metrics
- prometheus

These should be wired into your platform monitoring before release.
