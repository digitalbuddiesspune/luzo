# Platform Launch Integration

This document describes how an external platform (for example
[aakda.in](https://aakda.in/)) launches PotLudo for an already-authenticated
user.

Use this when your platform owns identity and wallet, and Ludo owns rooms,
matches, and gameplay.

Related docs:

- [Operator Platform](./operator-platform.md) — backend wallet/debit/credit flow
- [API Reference](./api-reference.md) — identity and match endpoints
- [Configuration](./configuration.md) — environment variables

---

## 1. Required launch fields

The game reads launch data from the **browser URL query string**.

| Field | Required | Type | Description |
|-------|----------|------|-------------|
| `id` | **Yes** | string | Operator/platform user token for the logged-in player. Sent to Ludo as the operator session token. |
| `game_id` | Recommended | positive integer | Operator game id for Ludo. Alias accepted: `gameId`. Default: `2` if omitted. |

### Rules

- `id` must be non-empty after trim.
- Without `id`, launch is invalid.
- If `NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=true`, missing `id` shows access denied (no guest fallback).
- `game_id` / `gameId` must be a finite number `> 0`. Invalid values fall back to `2`.

### Client → backend body

The frontend converts query params into:

```json
{
  "id": "<operator-token>",
  "gameId": 2
}
```

and calls:

`POST /api/v1/identity/operator/session`

---

## 2. Launch URL formats

Replace hosts with your deployed game frontend.

### Home / menu (recommended launch)

Shows the 2-play / 4-play mode selection screen first.

```text
https://fashionbuddies.in/?id=<OPERATOR_TOKEN>&game_id=2
```

### Online matchmaking (skip menu)

Requires an explicit `players` value. Without `players=2` or `players=4`,
the client redirects to the home/menu screen above.

```text
https://fashionbuddies.in/play/online?id=<OPERATOR_TOKEN>&game_id=2&players=4
```

### Online with player count

```text
https://fashionbuddies.in/play/online?id=<OPERATOR_TOKEN>&game_id=2&players=2
https://fashionbuddies.in/play/online?id=<OPERATOR_TOKEN>&game_id=2&players=4
```

`players` must be `2` or `4` to start matchmaking immediately. It is a Ludo
UI/lobby preference, not an operator auth field.

### Private room

```text
https://fashionbuddies.in/play/private-room?id=<OPERATOR_TOKEN>&game_id=2
```

### Example for aakda.in → Ludo

```text
https://fashionbuddies.in/?id=eyJhbGciOi...&game_id=2
```

Keep `id` and `game_id` on every in-game navigation URL your platform controls.
The Ludo client also tries to preserve these params while the user moves between
play routes.

---

## 3. Platform integration checklist

### A. Frontend (Ludo / Vercel)

```dotenv
NEXT_PUBLIC_API_BASE_URL=https://api.fashionbuddies.in
NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=true
```

Redeploy after changing `NEXT_PUBLIC_*` values.

### B. Backend (Ludo API)

```dotenv
APP_OPERATOR_BASE_URL=https://aakda.in
APP_OPERATOR_LOGIN_PATH=/operator/user/login
APP_OPERATOR_USER_DETAIL_PATH=/service/user/detail
APP_OPERATOR_BALANCE_PATH=/service/operator/user/balance/v2
APP_OPERATOR_GAME_ID=2

APP_WEB_ALLOWED_ORIGIN_PATTERNS=https://fashionbuddies.in,https://www.fashionbuddies.in,https://aakda.in,https://www.aakda.in,https://*.vercel.app
```

Notes:

- `APP_OPERATOR_BASE_URL` is the API host (for example `https://aakda.in`).
- Path vars are paths only (for example `/operator/user/login`), not full site URLs.
- Effective calls become:
  - `https://aakda.in/operator/user/login`
  - `https://aakda.in/service/user/detail`
  - `https://aakda.in/service/operator/user/balance/v2`

### C. Your platform (aakda)

1. User is logged in on your platform.
2. Generate/retrieve that user’s operator token.
3. Open or iframe the Ludo launch URL with `id` + `game_id`.
4. Ensure user-detail and balance APIs accept that token.
5. Consume RabbitMQ credit messages for refunds/payouts (see operator doc).

---

## 4. Embed options

### Redirect (recommended)

```html
<a href="https://fashionbuddies.in/?id=USER_TOKEN&game_id=2">
  Play Ludo
</a>
```

### JavaScript

```js
function launchLudo(operatorToken, gameId = 2) {
  const url = new URL("https://fashionbuddies.in/");
  url.searchParams.set("id", operatorToken);
  url.searchParams.set("game_id", String(gameId));
  window.location.href = url.toString();
}
```

### iframe

```html
<iframe
  src="https://fashionbuddies.in/?id=USER_TOKEN&game_id=2"
  title="Ludo"
  allow="fullscreen"
  style="width:100%;height:100%;border:0;"
></iframe>
```

Add your platform origin to `APP_WEB_ALLOWED_ORIGIN_PATTERNS` if the game is
embedded cross-origin.

---

## 5. Session API contract

### Request

`POST https://api.fashionbuddies.in/api/v1/identity/operator/session`

```json
{
  "id": "<operator-token>",
  "gameId": 2
}
```

| Body field | Required | Notes |
|------------|----------|-------|
| `id` | Yes | Same value as URL `id` |
| `gameId` | No | Defaults from server `APP_OPERATOR_GAME_ID` if omitted |

### Success response (shape)

```json
{
  "userId": "op_user_123",
  "sessionToken": "ludo-session-token",
  "displayName": "Player Name",
  "expiresAt": "2026-07-26T12:00:00Z",
  "isOperatorSession": true
}
```

Operator sessions currently expire after **16 hours**.

### Failure

- Invalid/missing token → session creation fails.
- With operator platform flag enabled, UI shows access denied instead of guest mode.

---

## 6. What happens after launch

1. Browser opens Ludo with `id` + `game_id`.
2. Client posts token to `/api/v1/identity/operator/session`.
3. Ludo backend calls platform user-detail API with the token.
4. Ludo stores an operator-backed session and initializes local wallet from platform balance.
5. User joins online/private match as usual.
6. Match start debits entry fee via platform balance API.
7. Refunds / winner credits are published to RabbitMQ for your platform worker.

---

## 7. Optional vs required summary

### Required to launch into operator mode

- `id` (operator token) in the URL
- Working platform user-detail API for that token
- Ludo frontend + API deployed with operator config

### Strongly recommended

- `game_id` explicitly set (usually `2`)
- CORS origins for both platform and game domains
- RabbitMQ consumer for credit payouts

### Not required for launch auth

- `players` (lobby size only)
- Guest display name
- Private room code (only for friends mode after launch)

---

## 8. Smoke test

1. Log into the platform as a test user.
2. Open:

```text
https://fashionbuddies.in/?id=<REAL_TOKEN>&game_id=2
```

3. Confirm:
   - mode selection screen loads (2-play / 4-play)
   - Network call to `/api/v1/identity/operator/session` returns `200`
   - response has `isOperatorSession: true`
4. Pick a mode, start/join a match, and confirm entry-fee debit on the platform side.

---

## 9. Quick reference

| Item | Value |
|------|--------|
| Required query field | `id` |
| Recommended query field | `game_id` (alias `gameId`) |
| Default game id | `2` |
| Session endpoint | `POST /api/v1/identity/operator/session` |
| Example launch URL | `https://fashionbuddies.in/?id=TOKEN&game_id=2` |
| Platform example | `https://aakda.in/` |
