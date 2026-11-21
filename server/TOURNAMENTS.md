# Tournament development

Tournament Mode uses the existing Next.js player application, React admin application,
Spring WebFlux/Kotlin engine, MongoDB and Redis. Existing session authentication is
reused; this repository does not use JWTs for those sessions.

## Start and configure

- Java 21; MongoDB replica set/Atlas or a sharded deployment (transactions required);
  Redis; existing player and admin services.
- Player: `npm run dev` in `client`. Open `/tournaments/`.
- Backend: `./gradlew bootRun` in `server` with Java 21.
- Admin: `npm run dev` in `admin`, plus the existing rounds server for admin login.
  The existing admin `admin_sessions` and `admin_accounts` must be in the same Mongo
  database as the game server. Admin APIs use their existing opaque Bearer sessions.
- Vite proxies `/api/admin/tournaments` to `localhost:8080`; other admin requests retain
  the existing `localhost:8083` proxy. Production hosting must route that prefix to
  the game backend, or set `VITE_TOURNAMENT_API_BASE_URL` before building.
- Configure `NEXT_PUBLIC_API_BASE_URL` and CORS origins for your deployment. Local
  admin development uses `http://localhost:5174`.
- Both web builds remain static: player details and games use `?id=` query routes.

## Rules and isolation

Paid tournaments may be configured as drafts, but cannot be published. All payment,
webhook, cash-prize and refund operations return HTTP 423; no money is collected.
There is deliberately no environment switch to bypass this. An approved payment
integration and reconciliation/refund implementation are still required before enabling
paid tournaments. Free tournaments settle **non-redeemable tournament points** in
`tournament_rewards`, not the INR wallet.

The default four-player bracket sends 16 players to four winners and then directly to
one final. For eight players, two qualifiers from each first-round match fill the final.
For irregular capacities, byes advance without playing; automatic extra qualifiers fill
the next round. Configured qualification rules must strictly reduce the field and end
with one champion. All capacities 2–512 (2-player) and 4–512 (4-player) are tested.
A smaller actual registration field is replanned at start; incompatible custom rules
place the tournament under review rather than eliminating participants arbitrarily.

`RoomMode.TOURNAMENT` reuses the board and movement engine but records finishing order
and continues after the first finisher. The last remaining player receives the last
finishing position. Forfeiting players never become qualifiers. Classic and Custom
keep their first-winner rules. Tournament dice use the engine's basic dice rule rather
than Classic's bot/user bias. Bot filling and wallet entry reservations are not used.

Check-in expiry applies the published no-show policy. If too few eligible players remain
to fill the required qualifiers, the match is disputed. The configured reconnect window
is the human turn timeout: players must reconnect and act before that deadline.
Maximum match duration pauses the match for review, without guessing a winner.
Admins can cancel, disqualify with a reason, restart an unresolved match, or recheck an
engine result. Forced completion still requires a verified final; admins cannot submit
an arbitrary champion. Eliminated players from the same non-final round share a rank.

## Persistence and recovery

The versioned `tournaments` document is the authoritative aggregate. Registrations,
participants, rounds, matches, rewards and recent audit events are persisted as queryable
projections in the requested separate collections **in the same Mongo transaction**.
Unique indexes protect registrations, round/match identities and rewards. Full audit
history remains in `tournament_audit_logs`; the aggregate retains only recent events.
Optimistic concurrency and transaction retries serialize capacity, advancement and reward
settlement. Deterministic match/session IDs and verified-result flags make retries safe.

`active_game_players` enforces one human game assignment across modes.
`tournament_player_locks` retains tournament check-ins. The persistent scheduler resumes
open/live tournaments after restart and reads the authoritative game result; clients have
no trusted result-submission endpoint. WebSocket snapshots at `/ws/tournaments/{id}`
include recent events and current state. Reconnect/polling restores the latest version;
match board synchronization reuses existing match WebSockets plus snapshot polling.
These are in-app updates; native/browser push notification delivery is not configured.

## API and verification

The contract is `openapi/tournaments.json`. Player tokens use `X-Session-Token`; admin
tokens use `Authorization: Bearer ...`. Reasons are required for administrative interventions.

Run pure tournament tests without changing the repository's existing default test setting:

```sh
./gradlew -I gradle/tournament-tests.init.gradle test \
  --tests 'com.craft.ludo.tournament.TournamentBracketTests' \
  --tests 'com.craft.ludo.gameplay.TournamentRankingTests'
```

Opt-in integration tests use the configured MongoDB and Redis, but create a randomly named
`ludo_ttest_*` database and remove it afterwards. They do not access the application database
or provider wallet. Do not interrupt cleanup; if a test process is killed, remove only its
specifically identified test database after checking it is no longer in use.

```sh
RUN_TOURNAMENT_INTEGRATION=true ./gradlew -I gradle/tournament-tests.init.gradle test \
  --tests 'com.craft.ludo.tournament.*' \
  --tests 'com.craft.ludo.gameplay.TournamentRankingTests'
```

Full production load testing, a real process-crash recovery exercise, deployment and
approved paid-entry integrations are separate release gates. A passing local build or
small concurrency test does not establish production capacity.
