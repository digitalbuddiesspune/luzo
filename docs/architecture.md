# Architecture

## Overview

PotLudo is split into three deployable applications:

- `client/`: a Next.js 16 app that renders the game UI and calls backend APIs
- `server/`: a Spring Boot 3 WebFlux service that owns identity, lobby, wallet,
  match progression, and realtime delivery
- `rounds-server/`: a Node.js read-only API that exposes completed Ludo round
  history from the same MongoDB database

The server is authoritative for multiplayer gameplay. The client never decides
game outcomes, valid moves, turn order, wallet settlement, or room ownership.
The rounds service does not mutate gameplay state; it joins completed
`matches` with their source `rooms` for reporting and operator round lookups.

## Major Backend Domains

### Identity

Implemented in `server/src/main/kotlin/com/craft/ludo/identity/IdentityModule.kt`.

Responsibilities:

- Create guest sessions
- Create operator-backed sessions from platform launch tokens or operator login
- Resolve the current player from `X-Session-Token`
- Update guest display names
- Set session expiry based on `APP_SESSION_TTL_DAYS`
- Ensure a wallet exists for each newly created guest session

Current session model:

- Guest sessions for direct demo access
- Operator-backed sessions for platform launch access
- Stateless client token storage
- No first-party password, OAuth, or device trust layer

### Operator Gateway

Implemented in `server/src/main/kotlin/com/craft/ludo/operator/OperatorGatewayModule.kt`.

Responsibilities:

- Login to the external operator gateway when direct operator credentials are
  used
- Fetch operator user detail and wallet balance
- Debit operator wallet balance when entry fees are reserved
- Enqueue external credit messages for refunds and winner payouts

### Wallet

Implemented in `server/src/main/kotlin/com/craft/ludo/wallet/WalletModule.kt`.

Responsibilities:

- Create wallet accounts for guests
- Mirror operator wallet balances into local wallet accounts
- Seed initial balance
- Reserve coins when a match starts
- Refund reserved coins on rollback paths
- Pay out match winners
- Optionally retain house rake
- Expose transaction history
- Guard reservation and settlement flows with idempotency keys

Room entry-fee reservation is idempotent by room, user, and amount. The first
caller claims the reservation and performs the local reservation or operator
debit. A concurrent caller for the same room/user waits briefly for the first
ledger entry and reuses it, which prevents duplicate debits and avoids failing
room start on duplicate idempotency-key insertion.

Wallet transaction types currently used:

- `GUEST_STARTING_BALANCE`
- `ADMIN_CREDIT`
- `ROOM_RESERVATION`
- `ROOM_REFUND`
- `MATCH_PAYOUT`
- `HOUSE_RAKE`

### Lobby and Matchmaking

Implemented in `server/src/main/kotlin/com/craft/ludo/gameplay/GameplayModule.kt`.

Responsibilities:

- Manage public online rooms
- Manage private friend rooms
- Assign unique seat colors
- Start matches when room conditions are satisfied
- Handle room leave behavior before and during gameplay

Public rooms created by current code store `ownerInstanceId` and
`ownedWaitingDeadlineAt`. The API still exposes an effective waiting deadline to
the client, but the legacy `waitingDeadlineAt` field is left empty for new
public rooms so older deployed backend instances do not start rooms created by
newer code during rolling deployments.

Room modes:

- `ONLINE_PUBLIC`
- `PRIVATE_FRIENDS`

Room statuses:

- `WAITING`
- `ACTIVE`
- `FINISHED`

### Match Engine

Also implemented in `GameplayModule.kt`.

Responsibilities:

- Maintain the authoritative board state
- Roll dice server-side
- Validate token moves
- Advance turn phases
- Run bot turns
- Detect token capture and home completion
- Detect winner and settle the wallet
- Publish new match snapshots to connected clients

Match phases:

- `ROLLING`
- `AWAITING_MOVE`
- `BOT_MOVING`
- `ADVANCING`
- `FINISHED`

### Realtime

Implemented through:

- `/ws/matches/{matchId}?sessionToken=...`
- WebRTC config and signaling endpoints in `GameplayModule.kt`

Responsibilities:

- Broadcast match snapshots over WebSocket
- Carry WebRTC signaling events to participants in the same match

## Frontend Responsibilities

The Next.js client is responsible for:

- Session bootstrap in local storage
- Wallet and room polling
- Screen transitions between menu, waiting lobby, and board
- Rendering the board from the current user perspective
- Subscribing to WebSocket match updates
- Falling back to HTTP polling for snapshots and room state

Important client-side implementation notes:

- The server remains authoritative
- The board can rotate visually based on the assigned player color
- Waiting lobbies and leave flows are guarded to avoid board flicker during
  mode transitions

## Persistence Model

MongoDB is the primary persistence layer.

Core collections:

- `guest_sessions`
- `rooms`
- `matches`
- `wallet_accounts`
- `wallet_transactions`
- `wallet_entries`
- `idempotency_keys`

Redis is used for realtime fan-out across server instances and lightweight
instance locks/heartbeats. RabbitMQ is used for operator credit payout messages.

The Node.js round-history service reads from the `matches` and `rooms`
collections. It returns only `FINISHED` matches, calculates display payouts from
the completed pot and configured rake, and never writes to MongoDB.

## Match Lifecycle

The high-level lifecycle is:

1. A client establishes or restores a guest session.
2. The player enters a public or private room flow.
3. The lobby service creates or updates a room document.
4. When start conditions are met, the server reserves entry fees.
5. Reservation uses the local wallet for guest users and operator debit for
   operator-backed users.
6. The server creates a `MatchDocument` and marks the room active.
7. Clients receive match snapshots through HTTP and WebSocket.
8. The match engine advances phases until a winner is found.
9. The wallet service settles the pot and optional rake.
10. The final state remains queryable through the match snapshot endpoint.

## Color and Perspective Model

Seat colors are unique within a room. Match assignment can be randomized before
play starts. The client rotates the board presentation so the current player is
shown from a stable local perspective, while the server continues to reason
about canonical colors.

## Operational Endpoints

Spring Boot Actuator exposes:

- `health`
- `info`
- `metrics`
- `prometheus`

These are configured in `server/src/main/resources/application.yml`.

The rounds service exposes a separate `GET /health` endpoint on its own port.
