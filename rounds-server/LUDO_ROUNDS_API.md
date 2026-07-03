# Ludo Rounds API

This document covers the APIs exposed by the Node.js rounds server in
`rounds-server`.

## Node.js Rounds Server

The Node.js server is a read-only service for completed Ludo round history. It
reads the `matches` and `rooms` MongoDB collections written by the main game
server.

Default local base URL:

```text
http://localhost:8083
```

Runtime configuration is loaded from `rounds-server/.env`.

Required or commonly used environment variables:

```dotenv
PORT=8083
MONGODB_URI=mongodb://host.docker.internal:27017
MONGODB_DATABASE=Ludo
APP_WALLET_CURRENCY=INR
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
```

`MONGODB_URI` is required. `MONGODB_DATABASE`, `PORT`,
`APP_WALLET_CURRENCY`, and `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS` have the
defaults shown above. Use `mongodb://localhost:27017` for a direct local Node.js
run; use `host.docker.internal` when the Docker container connects to MongoDB on
the host.

### Health Check

```http
GET /health
```

Checks whether the service can connect to MongoDB.

Successful response:

```json
{
  "status": "ok",
  "service": "ludo-rounds-server",
  "database": "connected",
  "timestamp": "2026-06-30T00:00:00.000Z"
}
```

If MongoDB cannot be reached, the endpoint returns an error response from the
common error handler.

### List Completed Ludo Rounds

```http
GET /api/v1/rounds/ludo?page=1&limit=20
```

Returns completed Ludo rounds in JSON format.

Query parameters:

| Parameter | Required | Default | Notes |
| --- | --- | --- | --- |
| `page` | No | `1` | Must be a positive integer. |
| `limit` | No | `20` | Must be a positive integer and cannot exceed `100`. |

Behavior:

- Only `FINISHED` matches are returned.
- Results are sorted by `updatedAt` descending, then `_id` descending.
- Room data is joined from the `rooms` collection using `match.roomId`.
- Abandoned players are included with `isAbandoned: true`.
- If a reservation is missing, the player bet amount is reported as `0`.
- Winner payout is calculated from the total pot after
  `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS`.
- Bot players are included with `isBot: true`.

Successful response shape:

```json
{
  "data": [
    {
      "game": "ludo",
      "roundId": "match-id",
      "roomId": "room-id",
      "roomCode": "ABC123",
      "mode": "ONLINE_PUBLIC",
      "status": "FINISHED",
      "startedAt": "2026-06-30T10:00:00.000Z",
      "completedAt": "2026-06-30T10:10:00.000Z",
      "currency": "INR",
      "entryFee": 100,
      "totalPotAmount": 100,
      "players": [
        {
          "userId": "user-1",
          "displayName": "Player One",
          "color": "red",
          "isAbandoned": false,
          "betAmount": 100,
          "isWinner": true,
          "winAmount": 100
        }
      ],
      "winner": {
        "userId": "user-1",
        "displayName": "Player One",
        "betAmount": 100,
        "winAmount": 100
      }
    }
  ],
  "pagination": {
    "page": 1,
    "limit": 20,
    "totalItems": 1,
    "totalPages": 1,
    "hasNextPage": false,
    "hasPreviousPage": false
  }
}
```

Invalid pagination response:

```json
{
  "error": {
    "code": "INVALID_PAGINATION",
    "message": "limit must be no greater than 100."
  }
}
```

### Single Completed Ludo Round HTML

```http
GET /api/v1/rounds/ludo/single?user_id=user-1&operator_id=operator-1&lobby_id=room-id
```

Returns a `text/html` page for one completed Ludo round.

Query parameters:

| Parameter | Required | Notes |
| --- | --- | --- |
| `user_id` | Yes | Must match a wallet reservation in the room. |
| `operator_id` | Yes | Must match the same wallet reservation as `user_id`. |
| `lobby_id` | Yes | Matched against the Ludo room id and completed match `roomId`. |

Behavior:

- The endpoint first looks up a room where `_id = lobby_id`.
- The room must contain a wallet reservation matching `user_id` and
  `operator_id`. Synthetic bot reservations are ignored for this lookup.
- Then the endpoint finds the latest `FINISHED` match for that room.
- The HTML includes round summary, player rows, debit statements, and winner
  credit statements.
- This endpoint always returns HTML, including error pages.

Common failures:

```text
400 INVALID_ROUND_LOOKUP
```

Returned when a required query parameter is missing.

```text
404 ROUND_NOT_FOUND
```

Returned when no matching room, wallet reservation, or completed match exists.
