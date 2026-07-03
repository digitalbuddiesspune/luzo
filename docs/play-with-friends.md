# Play With Friends

This document covers the private room flow used for invited multiplayer games.

## Product Model

Private multiplayer uses `RoomMode.PRIVATE_FRIENDS`.

This mode is intended for invited players joining by room code rather than open
public matchmaking.

## User Journey

1. The host restores or creates a guest session.
2. The host creates a private room.
3. The server returns a room code and room metadata.
4. Friends join with the same room code.
5. The host can optionally transfer host ownership before start.
6. The host starts the room when at least 2 players are present.
7. The server reserves entry fees, creates the match, and returns match data.
8. Players connect to the match WebSocket and continue with normal gameplay.

## Private Room Endpoints

- `GET /api/v1/lobby/private/current`
- `POST /api/v1/lobby/private/create`
- `POST /api/v1/lobby/private/join`
- `POST /api/v1/lobby/private/host`
- `POST /api/v1/lobby/private/start`
- `POST /api/v1/lobby/private/leave`

## Room Creation

The host can provide:

- `roomName`
- `displayName`
- `entryFee`

Server-side validation currently includes:

- normalized room name
- normalized display name
- entry fee must be greater than zero
- one private room per user at a time

## Joining by Code

Friends join using the room code returned at creation time.

Join constraints:

- the room must still be in `WAITING`
- the room must not already have a `matchId`
- the room must not already be full

## Host Controls

Before the match starts, the current host can:

- transfer host ownership to another non-bot member
- start the room once enough players have joined

The server rejects host actions after the room becomes active.

## Match Start Rules

Private rooms start only when:

- the room is in `WAITING`
- no match already exists
- the caller is the current host
- at least 2 players are present

Unlike public online matchmaking, private room start does not auto-fill bots
before the match begins.

Private Ludo rooms also block match start when two real seats share the same
source signal, currently the same session user id, operator user id, or client
IP address. This prevents one person from using multiple ids to play against
themselves in one private Ludo room.

## Match Colors and Perspective

When a private room starts, participant colors are randomized across the set of
joined seats. The client then rotates the board so the local player sees a
stable personal perspective regardless of the assigned canonical color.

## Leave Behavior

### Before Match Start

If a player leaves a private room before start:

- the seat is removed
- the room is deleted if nobody remains
- if the host leaves, host ownership is transferred to the earliest remaining
  participant
- no reservation occurs because entry fees have not been locked yet

### After Match Start

If a player leaves an active private match:

- the player is marked abandoned, their tokens are removed, and their seat is
  skipped for all future turns
- the bot keeps the same color and continues the game

This behavior matches the active online-room leave semantics.

## Realtime

Once a private room becomes an active match, realtime behavior matches public
online:

- WebSocket snapshots are published to room participants
- HTTP snapshot polling remains the fallback path
- human dice rolls are submitted through `POST /api/v1/matches/{matchId}/roll`
- WebRTC config and signaling endpoints are available for future in-room voice
  or peer features

## Operational Notes

Private room UX depends on the client continuously syncing room state.

While the room is still in `WAITING`, the client polls private room state every
2 seconds. Once the room becomes active, it switches to match synchronization.
