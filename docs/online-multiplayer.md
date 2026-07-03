# Online Multiplayer

This document explains how the public online flow works from room join through
match settlement.

## User Journey

1. The client restores or creates a guest session.
2. The client calls `POST /api/v1/lobby/online/join` with the selected
   `maxPlayers` value (`2` or `4`). Older clients without a request body
   default to four-player matchmaking.
3. The server either:
   - returns an existing active room/match for that user,
   - joins the user to a waiting public room, or
   - creates a new waiting public room.
4. While the room is waiting, the client shows the waiting lobby screen.
5. When start conditions are met, the server starts the match and returns a
   WebSocket path.
6. The client connects to `/ws/matches/{matchId}` and continues polling the
   snapshot endpoint as a safety net.

## Public Room Model

Public matchmaking uses `RoomMode.ONLINE_PUBLIC`. Two-player and four-player
rooms share this mode but remain in separate queues based on each room's
`maxPlayers` value.

Each room stores:

- room id and join code
- seat list
- entry fee
- waiting deadline returned to the client
- owner instance id used by the backend to start the room safely
- current match id
- wallet reservations

The public room flow is optimized for quick game start instead of long queueing.

## Waiting Lobby Behavior

When a player enters public matchmaking, the server creates or reuses a waiting
room. The room remains in `WAITING` state until one of these happens:

- enough real players join to fill the room, or
- the waiting deadline expires

The waiting deadline is controlled by `APP_GAMEPLAY_LOBBY_WAIT_MILLIS`.
Public PvP is gated by `APP_GAMEPLAY_ONLINE_PVP_REAL_PLAYER_THRESHOLD`, which
defaults to `25`. While the public waiting population plus the joining user is
25 or fewer real players, the joining user is not placed into a room with
another real player. They receive their own waiting room, and that room can
start with bot fill when its deadline expires. Once the population goes above
25 real players, normal PvP room joining is allowed.

New rooms store the current-code deadline in `ownedWaitingDeadlineAt` and leave
the legacy `waitingDeadlineAt` field empty. Room summaries still return the
effective waiting deadline to the client. This prevents older deployed backend
instances that only understand `waitingDeadlineAt` from starting rooms created
by newer code while a rolling deployment is in progress.

The current client polls room state every 2 seconds while waiting.

## Bot Fill Behavior

When the waiting deadline expires, the server fills every remaining seat with
bots so the match always starts with the selected number of participants.

Important settlement rule:

- only real player entry fee reservations contribute to the real pot
- bots do not contribute wallet reservations

If the selected number of real players joins before timeout, the match starts
immediately without bots.

## Same-Source Protection

Public Ludo matchmaking avoids seating two real users in the same waiting room
when they share the same source signal, currently the same session user id,
operator user id, or client IP address. The newer joiner skips that room and can
be placed into a different Ludo room, which can later use bot fill if needed.

## Color Assignment

Seat colors are unique per room and the final match colors may be randomized
before match start. The client then rotates the board presentation to keep the
current player in a stable local viewing position.

## Match Start

At match start, the lobby service:

1. normalizes or randomizes seat colors as needed
2. reserves the entry fee for each real player
3. stores those reservations on the room
4. creates a `MatchDocument`
5. marks the room as `ACTIVE`

If reservation fails, the flow rolls back and previously created reservations
are refunded.

Room start is guarded by both a Redis lock and a MongoDB claim. The room claim
checks `ownerInstanceId` when present, so a current backend instance will not
start a room owned by a different current instance. Entry-fee reservations are
also idempotent per room, user, and amount: if two start paths race, only one
path performs the debit/reservation and the other reuses the completed
reservation instead of failing the room with a duplicate idempotency error.

## Realtime Match Flow

During an active public match:

- human users call the roll endpoint during their `ROLLING` phase
- human users submit token selections only when the server returns selectable
  token indexes
- bot dice rolls and bot token moves advance automatically
- every authoritative state transition increments the match sequence
- clients receive match snapshots over WebSocket
- clients also poll `GET /api/v1/matches/{matchId}` to recover from dropped
  realtime events

## Leave and Disconnect Behavior

### Waiting Lobby

If a player leaves during `WAITING`:

- the seat is removed
- the room is deleted if no real players remain
- no entry fee is deducted because reservations happen only at match start

### Active Match

If a player leaves after the match is active:

- that player is marked as abandoned and remains visible with the same color
  and name, while all of their tokens are removed from the board
- the abandoned seat is skipped for all future turns and cannot win
- the departed user's session is detached from the active room

This keeps the match playable for the remaining human and bot participants.

## Settlement

When a winner is determined:

- the wallet service pays out the reserved pool
- optional rake is calculated from `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS`
- the winner receives `MATCH_PAYOUT`
- the house user receives `HOUSE_RAKE` when rake is enabled

## Client UX Notes

The client now guards two transition cases to prevent UI flashes:

- it shows a neutral transition screen before the waiting lobby or match state
  is known
- it shows the same transition screen while leaving a room so players do not see
  the board rotate or flip during teardown

## Relevant Server Endpoints

- `POST /api/v1/lobby/online/join` with optional body `{ "maxPlayers": 2 | 4 }`
- `POST /api/v1/lobby/online/leave`
- `GET /api/v1/matches/{matchId}`
- `POST /api/v1/matches/{matchId}/roll`
- `POST /api/v1/matches/{matchId}/moves`
- `GET /ws/matches/{matchId}?sessionToken=...`
