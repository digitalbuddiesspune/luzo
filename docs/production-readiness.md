# Production Readiness

This document is the practical checklist for turning the current repo into a
production-grade service.

## Already Present

- Server-authoritative multiplayer rules
- Reactive backend with MongoDB persistence
- Guest session bootstrap and lookup
- Wallet reservation and payout flows
- Optional operator-backed session and wallet integration
- Idempotency handling for wallet reservation and settlement paths
- Public and private room flows
- Match WebSocket delivery
- Redis-backed realtime fan-out and instance coordination
- Basic actuator endpoints for monitoring
- Environment-based runtime configuration

## Product Gaps to Close

### Authentication and account model

Current state:

- guest sessions and operator-backed sessions
- token stored client-side
- no first-party account recovery

Production expectations:

- real account model
- secure authentication
- session revocation
- device/session management
- abuse prevention and rate limiting

### Payments and compliance

Current state:

- demo wallet
- optional external operator wallet debits and queued credits
- admin credit helper
- room entry-fee reservations are idempotent per room, user, and amount
- no real payment rail
- no withdrawal path
- no KYC or fraud tooling

Production expectations:

- deposits and withdrawals
- payment gateway reconciliation
- operator queue reconciliation and retry tooling
- ledger audit tooling
- fraud controls
- jurisdiction and regulatory review

### Security hardening

Current state:

- application-level validation exists
- no documented API gateway or WAF layer
- admin credit and operator login endpoints need explicit exposure policy

Production expectations:

- TLS termination strategy
- secret management
- rate limiting
- bot and abuse controls
- audit logging for privileged actions
- dependency and container scanning

### Testing

Current state:

- the repo builds successfully
- no meaningful automated test suite is visible in this codebase

Production expectations:

- unit tests for match rules
- integration tests for lobby and settlement flows
- regression tests for room leave and reconnect behavior
- contract tests for API payloads
- load tests for matchmaking and WebSocket fan-out

### Observability

Current state:

- actuator metrics endpoints are enabled

Production expectations:

- structured logs
- log correlation ids
- room and match lifecycle dashboards
- wallet settlement alerts
- reconnect and socket health metrics
- on-call runbooks

### Data management

Current state:

- MongoDB is the primary persistence layer
- `idempotency_keys` are append-only except for failed room-reservation claims,
  which are released so a later retry can debit/reserve correctly

Production expectations:

- explicit index strategy
- retention policy
- documented retention or archival policy for idempotency keys that does not
  permit duplicate financial settlement
- backup and restore testing
- migration/versioning strategy for documents
- PII handling policy

### Release engineering

Current state:

- client and server build independently

Production expectations:

- CI pipeline
- branch protection
- automated quality gates
- release tagging
- deployment promotion by environment
- rollback procedure

## Recommended Repo Standards

To keep this repository production-friendly, maintain:

- this `docs/` folder as the source of truth
- a tested local bootstrap path
- environment variable reference updates whenever config changes
- API docs updates whenever endpoints or payloads change
- architecture docs updates whenever ownership boundaries change

## Minimum Ship Checklist

Before calling the system production-ready, complete at least:

1. Add automated backend tests for wallet settlement, room start, and move
   validation.
2. Add client and server CI builds with blocking checks.
3. Lock down auth, secrets, and actuator exposure.
4. Verify MongoDB index, backup, and restore procedures.
5. Define incident handling for failed settlements, reconnect storms, and room
   corruption.
6. Replace demo wallet assumptions with real-money-safe infrastructure if this
   is intended for stakes-based gameplay.
