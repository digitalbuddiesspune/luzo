# API Reference

This document summarizes the current backend surface used by the shipped client.

## Conventions

- Base path: `/api/v1`
- Auth header: `X-Session-Token`
- Primary payload format: JSON
- Match realtime path: `/ws/matches/{matchId}?sessionToken=...`

## Identity

### `POST /api/v1/identity/guest`

Creates a guest session and initializes a wallet if needed.

Request:

```json
{
  "displayName": "Player One"
}
```

Response fields:

- `userId`
- `sessionToken`
- `displayName`
- `expiresAt`
- `isOperatorSession`

### `POST /api/v1/identity/operator/session`

Creates a local session from an operator launch token.

Request:

```json
{
  "id": "operator-launch-token",
  "gameId": 2
}
```

Response fields match guest session responses and set `isOperatorSession` to
`true` when the operator gateway accepts the token.

### `POST /api/v1/identity/operator/login`

Creates a local operator-backed session by first logging in to the operator
gateway.

Request:

```json
{
  "userId": "demo_user",
  "password": "secret"
}
```

This endpoint is primarily useful for internal or direct-login flows. Review
whether it should be exposed before production deployment.

### `GET /api/v1/identity/me`

Returns the current guest session resolved from `X-Session-Token`.

### `PATCH /api/v1/identity/profile`

Updates the guest display name.

Request:

```json
{
  "displayName": "New Name"
}
```

## Wallet

### `GET /api/v1/wallet`

Returns:

- `userId`
- `currency`
- `availableBalance`
- `reservedBalance`
- `transactions`

### `POST /api/v1/admin/credit`

Internal helper endpoint currently used to credit a local wallet.

Request:

```json
{
  "userId": "guest_123",
  "amount": 5000,
  "reason": "Support credit",
  "idempotencyKey": "optional-external-key"
}
```

Important: this endpoint is not protected by an admin authentication layer in
the current codebase. It must be placed behind trusted infrastructure or removed
before public production exposure.

## Public Lobby

### `GET /api/v1/lobby/rooms`

Returns room summaries.

### `POST /api/v1/lobby/online/join`

Creates or joins a public online room.

Response:

- `room`
- optional `match`
- optional `websocketPath`

### `POST /api/v1/lobby/online/leave`

Leaves the waiting lobby or active online room for the current user.

## Private Rooms

### `GET /api/v1/lobby/private/current`

Returns the current private room for the session user, if any.

### `POST /api/v1/lobby/private/create`

Request:

```json
{
  "roomName": "Weekend Table",
  "displayName": "Host Name",
  "entryFee": 100
}
```

### `POST /api/v1/lobby/private/join`

Request:

```json
{
  "roomCode": "ABC123",
  "displayName": "Friend Name"
}
```

### `POST /api/v1/lobby/private/host`

Transfers room host ownership.

Request:

```json
{
  "targetUserId": "guest_456"
}
```

### `POST /api/v1/lobby/private/start`

Starts the private room if the caller is host and there are at least 2 players.

### `POST /api/v1/lobby/private/leave`

Leaves the private room or replaces the player with a bot if the match is
already active.

## Matches

### `GET /api/v1/matches/{matchId}`

Returns the authoritative match snapshot for a participant.

Important response fields:

- `matchId`
- `roomId`
- `roomCode`
- `mode`
- `status`
- `phase`
- `entryFee`
- `potAmount`
- `turnTimeoutSeconds`
- `currentPlayerIndex`
- `currentTurnUserId`
- `currentTurnDisplayName`
- `dice`
- `players`
- `selectableTokenIndexes`
- `pendingNextPlayerIndex`
- `turnDeadlineAt`
- `winnerUserId`
- `winnerDisplayName`
- `sequence`
- `events`

### `POST /api/v1/matches/{matchId}/moves`

Submits the selected token index for a human move.

Request:

```json
{
  "tokenIndex": 2
}
```

The server validates:

- the match exists
- the user belongs to the match
- the match is awaiting a human move
- it is that user's turn
- the token index is currently selectable

### `POST /api/v1/matches/{matchId}/roll`

Rolls dice for the current human player when the match phase is `ROLLING`.

The server validates:

- the match exists
- the user belongs to the match
- it is that user's turn
- the match is waiting for a dice roll
- the current player is not a bot

## Realtime

### `GET /api/v1/realtime/webrtc/config`

Returns ICE/TURN server configuration for peer features.

### `POST /api/v1/realtime/webrtc/signal`

Broadcasts a signaling message into the match realtime channel.

Typical payload:

```json
{
  "matchId": "match_123",
  "targetUserId": "guest_456",
  "type": "offer",
  "sdp": "..."
}
```

### `GET /ws/matches/{matchId}?sessionToken=...`

WebSocket stream for:

- match snapshots
- WebRTC signaling events

## Operational Endpoints

Actuator exposure currently includes:

- `/actuator/health`
- `/actuator/info`
- `/actuator/metrics`
- `/actuator/prometheus`

## Round History Service

The Node.js service in `rounds-server/` runs separately from the Spring Boot
API. Its default local base URL is `http://localhost:8083`.

For the full contract, see
[`rounds-server/LUDO_ROUNDS_API.md`](../rounds-server/LUDO_ROUNDS_API.md).

### `GET /health`

Returns `200` when the service can ping MongoDB.

### `GET /api/v1/rounds/ludo?page=1&limit=20`

Returns paginated completed Ludo rounds. `page` defaults to `1`; `limit`
defaults to `20` and cannot exceed `100`.

### `GET /api/v1/rounds/ludo/single?user_id=...&operator_id=...&lobby_id=...`

Returns a `text/html` page for the latest completed round in the supplied
lobby. The lobby room must contain a non-synthetic wallet reservation matching
both `user_id` and `operator_id`.
