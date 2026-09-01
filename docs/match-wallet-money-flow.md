# Match Wallet Money Flow

End-to-end flow for how entry fees are debited, where the pot lives, and how money is credited when a human or bot wins.

## Quick answer: if a bot wins, where does the money go?

**To the house (platform)** — not to the bot, and not refunded to players.

- Settled as `HOUSE_WIN`
- House receives **real** player entry fees only (bot synthetic fees are ignored)
- No `MATCH_PAYOUT` to any player
- No RabbitMQ `games_cashout` credit
- No refund of entry fees
- Platform keeps the money because it was **already debited at match start** — there is **no** extra credit call of the pot through `APP_OPERATOR_BALANCE_PATH`

---

## Production channels (important)

`APP_OPERATOR_BALANCE_PATH` is used to **debit players only** (take entry fee at match start).  
It is **not** used to credit the platform fee or bot-win amount to the platform.

| Action | Channel |
|---|---|
| Take entry fee from player | HTTP **`APP_OPERATOR_BALANCE_PATH`** (debit) |
| Credit winner (operator user) | HTTP **`APP_OPERATOR_CREDIT_URL`** |
| Platform fee / bot win accounting | Local Ludo ledger only (`HOUSE_RAKE` / `HOUSE_WIN`) |

### Example: entry `100`, 2 real players, platform fee `10` / seat

**At match start**

- Each real player is debited **100** via `APP_OPERATOR_BALANCE_PATH`
- So **200** is already taken from players into the operator/platform wallet

#### If a real player wins

| Step | Channel | Amount |
|---|---|---|
| Winner credit | HTTP credit API (**not** balance path) | **180** back to winner |
| Platform fee `20` | Local Ludo ledger only (`HOUSE_RAKE`) | No HTTP / no RabbitMQ to “credit platform” |

Platform keeps **20** because it already took **200** and only sends **180** back.

#### If a bot wins

| Step | Channel | Amount |
|---|---|---|
| Player refund / credit | **None** | — |
| Platform “credit” of 200 | **No call** to `APP_OPERATOR_BALANCE_PATH` | — |
| Accounting | Local ledger `HOUSE_WIN` | Records real fees kept |

Platform keeps the **200** because it was already debited at start and **nothing is credited back**.

### Summary

- `APP_OPERATOR_BALANCE_PATH` → **debit only** (take entry fee)
- Winner credit → **HTTP `APP_OPERATOR_CREDIT_URL`**
- Platform fee / bot win → platform keeps money by **not refunding**; Ludo only writes `HOUSE_RAKE` / `HOUSE_WIN` in its own DB — it does **not** push +20 or +200 through the balance API

---

## Overview diagram

```
Join lobby        → balance check only (no debit)
Match starts      → debit / reserve entry fee → pot
                    (operator: APP_OPERATOR_BALANCE_PATH debit)
Human wins        → MATCH_PAYOUT + HTTP credit API (+ HOUSE_RAKE local)
Bot / house wins  → HOUSE_WIN local only (keep already-debited fees; no cashout)
```

---

## 1. Join lobby — no debit yet

When a player joins an **online public** or **private** lobby:

1. Server calls `WalletService.requireSufficientBalance`
2. Operator users: balance refreshed from operator API
3. Guest users: local wallet balance checked
4. **Money is not taken yet** — soft check only

### Online public

- `OnlineMatchmakingService.joinOnlineMatch`
- Player is seated in a `WAITING` room

### Private friends

- `LobbyService.createPrivateRoom` / `joinPrivateRoom`
- Same balance check; host starts later

---

## 2. Match start — money is taken

Money moves when the match actually starts.

| Player type | What happens |
|---|---|
| **Operator (real wallet)** | HTTP **debit** via `OperatorGatewayClient.debit` (`txn_type=0` on `APP_OPERATOR_BALANCE_PATH`) |
| **Guest** | Local wallet: `available` → `reserved` |
| **Bot seats** | Synthetic fee only (no real money movement) |

### Online public start

1. Lobby deadline / full → `WAITING → STARTING`
2. Soft re-check balance
3. `reserveEntryFeesForSeats` → `WalletService.reserveEntryFee`
4. Bot seats get synthetic reservations
5. Persist `room.walletReservations`
6. `MatchService.createStartedMatch`

### Private start

1. Host starts room
2. Same `reserveEntryFeesForSeats` for human seats only
3. No bot fill / no synthetic fees on private start

### Failed debit

- Idempotency key released
- Partial batch refunded via `refundReservation`
- Start rolled back

---

## 3. During the match — where fees live

| Store | Role |
|---|---|
| `RoomDocument.walletReservations` | Source of truth for settlement (real + synthetic) |
| `MatchDocument.potAmount` | Snapshot at start = sum of reservation amounts |
| `MatchDocument.entryFee` | Per-seat entry fee |
| Guest `wallet_accounts.reservedBalance` | Held for guest players |
| Operator wallets | Already debited externally; Ludo does not hold their cash |

Platform fee is **not** taken mid-match. It is computed at settlement:

```text
rake = min(platformFeePerPlayer × seatCountIncludingBots, potAmount)
```

---

## 4. Human wins — how money is credited

Triggered when match status becomes `FINISHED`:

`MatchService.persistMatchTransition` → `WalletService.payoutWinner` → `payoutWinnerWithFee`

### Steps

1. Idempotency lock: `wallet:settlement:{matchId}`
2. Release local reserved balances (guest / non-external reservations)
3. Credit winner available balance + ledger **`MATCH_PAYOUT`** (`pot − platform fee`)
4. Credit house + ledger **`HOUSE_RAKE`**
5. If winner is an operator user with confirmed external debit:
   - `enqueueWinnerExternalCreditIfNeeded`
   - HTTP POST to `APP_OPERATOR_CREDIT_URL`
6. Guest winners: local ledger only (no AMQP)

### Example

| Item | Value |
|---|---|
| Entry fee | `100` |
| Seats | `2` |
| Platform fee / seat | `10` |
| Pot | `200` |
| Rake | `20` |
| Winner credit | **180** |

---

## 5. Bot wins / house win

Any of:

- Bot finishes all tokens home
- All humans leave / AFK and only bots remain → `finishMatchAsBotWin`
- Explicit house win (`finishMatchAsHouseWin`)
- 2-player forfeit where the remaining seat is a bot

### Settlement

- Winner reservation is synthetic / house → `isHouseWinner = true`
- `HOUSE_WIN` amount = sum of **real** reservation amounts only
- Synthetic bot fees ignored for house credit
- No player credit
- No refund
- No RabbitMQ cashout
- Rake = `0` (house keeps the full real pot)

---

## 6. Forfeit / AFK / abandon

AFK rule: miss **2 turns** in the whole match → removed (`maxMissedTurns`).

Logic: `resolveAbandonOutcome` in `GameplayModule.kt`.

| Situation | Outcome | Money |
|---|---|---|
| True **2-seat** match, one leaves / AFK-kicked | Remaining player wins | Normal human/bot settlement |
| ≥1 human still active (e.g. 4p) | Match continues | No settle yet |
| **0 humans**, bots remain | Bot winner | **House win** (real fees) |
| **0 humans, 0 bots** | House win | Explicit `HOUSE_WIN` |

Entry fees are **not** returned on leave/AFK; they stay in the pot for the winner/house.

---

## 7. Guest vs operator paths

| Step | Guest | Operator |
|---|---|---|
| Balance check | Local `wallet_accounts` | Refresh from operator user detail |
| Debit at start | Local reserve (`available` → `reserved`) | HTTP debit; ledger reservation only |
| During match | Reserved held locally | Cash already at operator |
| Win | `MATCH_PAYOUT` local credit | `MATCH_PAYOUT` local **+** HTTP credit API |
| Refund (cancel / failed start) | Unreserve + `ROOM_REFUND` | HTTP credit of entry fee + `ROOM_REFUND` |
| House / bot win | Reserved released; `HOUSE_WIN` to house | No refund; `HOUSE_WIN` accounting |

Detection: active guest session with operator credentials (`operatorSessionForUser`).

---

## Config keys

| Concern | Config / env | Default |
|---|---|---|
| Online entry fee | `APP_GAMEPLAY_ONLINE_ENTRY_FEE` | `100` |
| Platform fee per seat | `APP_WALLET_PLATFORM_FEE_PER_PLAYER` | `10` |
| House user | `APP_WALLET_HOUSE_USER_ID` | `house` |
| Guest starting balance | `APP_WALLET_GUEST_STARTING_BALANCE` | `100000` |
| Operator debit HTTP | `APP_OPERATOR_BASE_URL` + `APP_OPERATOR_BALANCE_PATH` | balance API |
| Operator credit HTTP | `APP_OPERATOR_CREDIT_URL` (or `APP_OPERATOR_CREDIT_PATH`) | winner/refund credit |
| Cashout AMQP (legacy, unused for credits) | `AMQP_URI`, `APP_OPERATOR_CREDIT_*` | exchange `/games/admin`, queue/key `games_cashout` |
| AFK miss limit | `APP_GAMEPLAY_MAX_MISSED_TURNS` | `2` |

Source: `server/src/main/resources/application.yml`

---

## Edge cases

| Case | Behavior |
|---|---|
| Failed debit mid-batch | Completed seats refunded; start rolled back |
| Public start failure after fees | Refund real reservations; roll back to waiting |
| Hung `STARTING` with fees | Refund + finish lobby |
| Leave lobby during `STARTING` with fees, no match | Refund then leave |
| Duplicate settle | Blocked by idempotency `wallet:settlement:{matchId}` |
| AMQP credit fails after `MATCH_PAYOUT` | Ledger kept; may need manual replay |
| Operator without confirmed debit | Winner external credit skipped / forced to `0` |

---

## Key files

- `server/src/main/kotlin/com/craft/ludo/wallet/WalletModule.kt`
- `server/src/main/kotlin/com/craft/ludo/operator/OperatorGatewayModule.kt`
- `server/src/main/kotlin/com/craft/ludo/gameplay/OnlineMatchmakingService.kt`
- `server/src/main/kotlin/com/craft/ludo/gameplay/GameplayModule.kt`
- `server/src/main/resources/application.yml`
