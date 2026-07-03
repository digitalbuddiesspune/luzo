# PotLudo Documentation

This folder is the operational documentation set for the Ludo multiplayer repo.
It is organized around the main gameplay modes first, then the platform and
production concerns needed to run and evolve the system safely.

## Document Map

- [Online Multiplayer](./online-multiplayer.md)
  Explains the lifecycle of public matchmaking, waiting lobby behavior, bot fill,
  wallet reservation, live match progression, and disconnect/leave behavior.
- [Play With Friends](./play-with-friends.md)
  Documents private room creation, room codes, host transfer, room start rules,
  and active-match behavior for invited friends.
- [Architecture](./architecture.md)
  Covers the frontend/backend split, persistence model, match lifecycle, realtime
  channels, and key documents stored in MongoDB.
- [API Reference](./api-reference.md)
  Lists the HTTP and WebSocket contracts used by the current client, plus the
  round-history service endpoints.
- [Configuration](./configuration.md)
  Central reference for environment variables, local development setup, runtime
  defaults, and deployment-sensitive settings.
- [Operator Platform](./operator-platform.md)
  Documents the external operator launch/session flow, wallet balance refresh,
  entry-fee debits, and queued credit payouts.
- [AWS Deployment](./aws-deployment.md)
  Step-by-step deployment guidance for running the backend on AWS and wiring the
  Vercel frontend to the API subdomain over HTTPS.
- [Production Readiness](./production-readiness.md)
  A practical checklist of what is already implemented and what still needs
  hardening for a true production rollout.
- [Round History API](../rounds-server/LUDO_ROUNDS_API.md)
  Documents the standalone Node.js service that reads completed Ludo rounds
  from MongoDB for operator/reporting integrations.

## Current Scope

The current codebase already implements:

- Guest and operator-backed session creation and persistence
- Wallet initialization, reservation, refund, and winner payout
- Public online matchmaking with a timed waiting lobby
- Private friend rooms with host controls
- Server-authoritative Ludo turn resolution
- Match WebSocket streaming plus WebRTC signaling endpoints
- Redis-backed realtime fan-out and instance coordination
- Standalone read-only round history API for completed Ludo matches
- Basic operational endpoints through Spring Boot Actuator

The current codebase does not yet represent a fully hardened consumer product.
See [Production Readiness](./production-readiness.md) for the exact gaps.

## Recommended Reading Order

1. Read [Architecture](./architecture.md) for the system model.
2. Read either [Online Multiplayer](./online-multiplayer.md) or
   [Play With Friends](./play-with-friends.md) depending on the product flow.
3. Use [API Reference](./api-reference.md), [Configuration](./configuration.md),
   and [Operator Platform](./operator-platform.md) while implementing platform
   launch or wallet changes.
4. Review [Production Readiness](./production-readiness.md) before shipping.
