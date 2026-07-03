# PotLudo Server

Spring Boot 3 + Kotlin reactive backend for the PotLudo demo.

## Modules

- `identity`: guest session lifecycle and profile updates
- `wallet`: admin credits, room reserves, refunds, payouts, ledger history
- `lobby`: room templates, public/private rooms, bot fill, readiness, room lifecycle
- `matchengine`: server-authoritative Ludo rules and turn resolution
- `realtime`: websocket fan-out for room and match streams

## Stack

- Spring Boot WebFlux
- MongoDB Atlas via connection URI as the primary database
- Redis as the realtime lease/cache channel layer
- Kotlin data classes for the persistence model

## Configuration

The server now loads configuration from `server/.env` automatically. Update that file before starting the app.

Core runtime variables:

```dotenv
MONGODB_URI=mongodb://localhost:27017/potludo
MONGODB_DATABASE=potludo
REDIS_HOST=localhost
REDIS_PORT=6379
PORT=8080
APP_SESSION_TTL_DAYS=30
APP_GAMEPLAY_TURN_TIMEOUT_SECONDS=30
APP_GAMEPLAY_ROOM_MAX_PLAYERS=4
APP_GAMEPLAY_ONLINE_ENTRY_FEE=100
APP_GAMEPLAY_LOBBY_WAIT_MILLIS=60000
APP_GAMEPLAY_ROLL_DELAY_MILLIS=700
APP_GAMEPLAY_BOT_MOVE_DELAY_MILLIS=850
APP_GAMEPLAY_ADVANCE_DELAY_MILLIS=750
APP_WALLET_CURRENCY=INR
APP_WALLET_GUEST_STARTING_BALANCE=100000
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
APP_WALLET_HOUSE_USER_ID=house
APP_REALTIME_WEB_RTC_ICE_SERVERS=stun:stun.l.google.com:19302
APP_REALTIME_WEB_RTC_TURN_USERNAME=
APP_REALTIME_WEB_RTC_TURN_CREDENTIAL=
```

For MongoDB Atlas, replace `MONGODB_URI` with your cluster connection string. If the URI does not include `/your_database_name`, set `MONGODB_DATABASE` explicitly.

`APP_WALLET_PAYOUT_RAKE_BASIS_POINTS` controls winner-settlement rake in basis points:

```text
0 = 0%
250 = 2.5%
500 = 5%
1000 = 10%
```

## Local run

1. Start Redis only:

```bash
docker compose up -d
```

2. Run the app once a Gradle wrapper or local Gradle install is available:

```bash
./gradlew bootRun
```

## Demo assumptions

- Guest-only sessions
- Admin-funded wallet credits
- No live payment gateway, KYC, or withdrawal flow
- Bots can fill empty seats for demo gameplay, but only human-paid seats contribute to the real pot amount

## Gameplay APIs

- `POST /api/v1/lobby/online/join`
  Joins an existing waiting public room when one exists, otherwise creates a new 4-seat room and bot-fills the remaining seats so the player can start immediately.
- `GET /api/v1/lobby/rooms`
  Lists known rooms and occupancy.
- `GET /api/v1/matches/{matchId}`
  Returns the current server-authoritative match snapshot for a participant.
- `POST /api/v1/matches/{matchId}/moves`
  Submits a human token move. Dice rolls and bot turns are advanced by the server automatically.
- `GET /api/v1/realtime/webrtc/config`
  Returns ICE/TURN configuration for future peer negotiation.
- `POST /api/v1/realtime/webrtc/signal`
  Broadcasts a WebRTC signaling payload to the room/match stream for later friend-room peer setup.
- `GET /ws/matches/{matchId}?sessionToken=...`
  WebSocket stream for live match snapshots and signaling events.
