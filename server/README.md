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

## Custom rooms with friends

The home screen's **Custom Room** option lets a host select **2 Players** or
**4 Players** and create a private room with the game-configured entry fee. Friends join using
the generated room code. Codes are case insensitive. No bots fill private rooms.
Only the host can start, and all selected seats must be occupied.

Existing endpoints use the `X-Session-Token` header:

- `POST /api/v1/lobby/private/create`: `{ "roomName": "Weekend Table", "displayName": "Host", "maxPlayers": 2 }`.
  `maxPlayers` accepts 2 or 4, defaults to 4, and rejects other values with 400.
  The server sets `entryFee` from `app.gameplay.online-entry-fee`; hosts cannot override it.
- `POST /api/v1/lobby/private/join`: `{ "roomCode": "ABC123", "displayName": "Friend" }`.
- `GET /api/v1/lobby/private/current`: current room, members, host, capacity, and active match if present.
- `POST /api/v1/lobby/private/start`: host only; returns 409 until all selected seats are filled.
- `POST /api/v1/lobby/private/host`: existing host can transfer ownership using `{ "targetUserId": "..." }`.
- `POST /api/v1/lobby/private/leave`: leave the lobby, or forfeit an active round.

After completion and settlement, the room returns to `WAITING` with the same
code, configuration, and remaining friends. The result screen offers **Back to
Lobby** and returns automatically after eight seconds. The host can start the
next round once the room is full. Each round uses a new match/provider round ID
and new entry-fee reservations. Previous reservations are cleared after
settlement. Players who left or were removed are not added back automatically;
a remaining member becomes host if the previous host left. Public matchmaking
continues to close its rooms after each match.
