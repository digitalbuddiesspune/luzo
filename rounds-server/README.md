# Ludo Round History Server

Standalone, read-only Node.js API for completed Ludo rounds stored by the main
game server.

For the full Node.js API contract, see
[`LUDO_ROUNDS_API.md`](./LUDO_ROUNDS_API.md).

## Start with Docker

The local `.env` is already ignored by Git. Configure it from `.env.example`,
then run:

```bash
docker compose up --build
```

The API is available at `http://localhost:8083`.

The default Docker-oriented MongoDB URI is:

```dotenv
MONGODB_URI=mongodb://host.docker.internal:27017
```

Use `mongodb://localhost:27017` instead when running Node.js directly on the
host.

## Configuration

Required and common variables:

```dotenv
MONGODB_URI=mongodb://host.docker.internal:27017
MONGODB_DATABASE=Ludo
PORT=8083
APP_WALLET_CURRENCY=INR
APP_WALLET_PAYOUT_RAKE_BASIS_POINTS=0
```

- `MONGODB_URI` is required.
- `MONGODB_DATABASE` defaults to `Ludo`.
- `PORT` defaults to `8083`.
- `APP_WALLET_CURRENCY` defaults to `INR`.
- `APP_WALLET_PAYOUT_RAKE_BASIS_POINTS` controls display payout rake in basis
  points, from `0` to `10000`.

## Endpoints

### Health

```http
GET /health
```

Returns `200` only when the API can ping MongoDB.

### Completed Ludo rounds

```http
GET /api/v1/rounds/ludo?page=1&limit=20
```

- `page` defaults to `1`.
- `limit` defaults to `20` and may not exceed `100`.
- Rounds are sorted by completion time, newest first.
- Only matches with `status: "FINISHED"` are returned.
- Room data is joined from the `rooms` collection by `roomId`.
- Missing reservations are reported as a zero bet.

Example response:

```json
{
  "data": [
    {
      "game": "ludo",
      "roundId": "match-example",
      "roomId": "room-example",
      "roomCode": "ABC123",
      "mode": "ONLINE",
      "status": "FINISHED",
      "startedAt": "2026-06-24T10:00:00.000Z",
      "completedAt": "2026-06-24T10:10:00.000Z",
      "currency": "INR",
      "entryFee": 100,
      "totalPotAmount": 400,
      "players": [
        {
          "userId": "user-1",
          "displayName": "Player One",
          "color": "RED",
          "isAbandoned": false,
          "betAmount": 100,
          "isWinner": true,
          "winAmount": 400
        }
      ],
      "winner": {
        "userId": "user-1",
        "displayName": "Player One",
        "betAmount": 100,
        "winAmount": 400
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

Invalid pagination returns a structured `400` response:

```json
{
  "error": {
    "code": "INVALID_PAGINATION",
    "message": "limit must be no greater than 100."
  }
}
```

### Single completed Ludo round as HTML

```http
GET /api/v1/rounds/ludo/single?user_id=user-1&operator_id=operator-1&lobby_id=room-example
```

- Returns `text/html`; this endpoint does not return JSON.
- `user_id`, `operator_id`, and `lobby_id` are required query parameters.
- `lobby_id` is matched against the Ludo match `roomId`.
- The room must contain a wallet reservation matching both `user_id` and
  `operator_id`; synthetic bot reservations do not satisfy this lookup.
- If more than one completed match exists for the lobby, the newest one is
  returned.
- Missing parameters return an HTML `400` page.
- Unknown or mismatched Ludo round details return an HTML `404` page.
- Ludo statements include the amount, debit/credit direction, game name, and
  round id.

## Local Node run

```bash
npm install
npm start
```

For auto-reload during development:

```bash
npm run dev
```

To syntax-check the service:

```bash
npm run check
```
