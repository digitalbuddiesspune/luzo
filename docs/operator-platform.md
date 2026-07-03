# Operator Platform

This document covers the optional operator-backed launch mode. The normal guest
mode still works when the client operator flag is disabled.

## Purpose

Operator mode lets the game run inside an external platform that owns player
identity and wallet balance. The Ludo backend still owns rooms, matches, local
session tokens, and gameplay state, but it calls the operator gateway for:

- token validation and user details
- current wallet balance refresh
- entry-fee debits
- queued refund and winner-credit messages

## Client Launch Contract

Enable platform-only access in the client with:

```dotenv
NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED=true
```

When this flag is enabled, the client requires launch query parameters:

```text
?id=<operator-token>&game_id=<operator-game-id>
```

The client sends those values to `POST /api/v1/identity/operator/session`.
If the token cannot be exchanged for an operator user, the UI shows the generic
access-denied message instead of falling back to guest mode.

When the flag is disabled, the same token launch path can still create an
operator-backed session if an `id` query parameter is present. Otherwise the
client creates or restores a guest session.

## Backend Session Flow

Implemented in:

- `server/src/main/kotlin/com/craft/ludo/identity/IdentityModule.kt`
- `server/src/main/kotlin/com/craft/ludo/operator/OperatorGatewayModule.kt`

Supported identity endpoints:

- `POST /api/v1/identity/operator/session`
- `POST /api/v1/identity/operator/login`

`/operator/session` accepts a platform token in the `id` field and an optional
`gameId`. `/operator/login` accepts operator credentials and is useful for
internal testing or direct login flows.

Successful operator sessions are stored in `guest_sessions` with operator token,
operator user id, operator id, currency, and game id metadata. The response shape
matches guest sessions and includes `isOperatorSession: true`.

Operator-backed sessions currently expire after 16 hours. Guest sessions use
`APP_SESSION_TTL_DAYS`.

## Wallet Behavior

Implemented in `server/src/main/kotlin/com/craft/ludo/wallet/WalletModule.kt`.

For operator-backed users:

- `GET /api/v1/wallet` refreshes the local wallet account from operator user
  details before returning the overview.
- Match start calls the operator balance endpoint to debit the entry fee with
  `txn_type: 0`.
- The local account moves the same amount from available to reserved balance.
- Refunds and winner payouts publish credit messages to RabbitMQ.
- Settlement still records local wallet transactions for audit and UI history.

The operator debit payload is sent to
`/service/operator/user/balance/v2` with the operator token in the `token`
header. It includes the generated local room-fee transaction id as `txn_id`,
the room entry fee as `amount`, the session IP, `game_id`, operator user id,
and operator id from `/service/user/detail`.

The RabbitMQ credit target is controlled by `AMQP_URI`,
`APP_OPERATOR_CREDIT_EXCHANGE`, `APP_OPERATOR_CREDIT_QUEUE_NAME`, and
`APP_OPERATOR_CREDIT_ROUTING_KEY`. Another worker or platform process must
consume that queue and complete external credits; this repository only publishes
the message.

## Server Configuration

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

The backend still needs Redis for realtime fan-out and instance coordination.
Operator credit messages are published to RabbitMQ.

## Failure Semantics

- If operator token validation fails, session creation fails.
- If entry-fee debit fails, match start fails and any already-created
  reservations are refunded.
- Entry-fee reservation is idempotent per room, user, and amount. If a
  concurrent start path reaches the same reservation, the second caller waits
  for and reuses the first completed reservation instead of issuing another
  debit.
- If a local settlement path is retried, wallet idempotency keys protect winner
  payout and admin-credit operations from duplicate local ledger writes.
- External credit queue delivery should be monitored separately; the backend
  does not currently expose a retry dashboard for queued operator credits.

## Production Notes

Before production use, define:

- how the RabbitMQ credit queue is consumed and retried
- reconciliation between operator ledger entries and local wallet transactions
- alerting for failed operator gateway calls and stuck credit messages
- token lifetime, revocation, and replay protections with the operator platform
- whether `POST /api/v1/identity/operator/login` should be exposed publicly
