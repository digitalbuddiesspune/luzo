const { config } = require("../config/env");
const { HttpError } = require("../errors/httpError");

const MATCH_PROJECTION = {
  _id: 1,
  roomId: 1,
  roomCode: 1,
  mode: 1,
  status: 1,
  entryFee: 1,
  potAmount: 1,
  "players.userId": 1,
  "players.displayName": 1,
  "players.color": 1,
  "players.isBot": 1,
  "players.isAbandoned": 1,
  winnerUserId: 1,
  winnerDisplayName: 1,
  createdAt: 1,
  updatedAt: 1,
};

const ROOM_PROJECTION = {
  _id: 1,
  code: 1,
  mode: 1,
  entryFee: 1,
  "seats.userId": 1,
  "seats.displayName": 1,
  "seats.color": 1,
  "seats.isBot": 1,
  "seats.isAbandoned": 1,
  "walletReservations.userId": 1,
  "walletReservations.amount": 1,
  "walletReservations.synthetic": 1,
  "walletReservations.operatorId": 1,
};

function safeAmount(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function formatAmount(amount, currency) {
  return `${safeAmount(amount)} ${currency}`;
}

function formatDate(value) {
  if (!value) {
    return "Not available";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "Not available";
  }

  return date.toISOString();
}

function renderHtmlPage({ title, body }) {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    :root { color-scheme: light; font-family: Arial, sans-serif; }
    body { margin: 0; background: #f7f7f3; color: #202124; }
    main { max-width: 980px; margin: 0 auto; padding: 28px 18px 40px; }
    h1 { margin: 0 0 18px; font-size: 28px; }
    h2 { margin: 28px 0 12px; font-size: 20px; }
    .summary { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 10px; }
    .field { border: 1px solid #d7d7cf; background: #fff; padding: 12px; border-radius: 6px; }
    .label { color: #60635f; font-size: 12px; text-transform: uppercase; }
    .value { display: block; margin-top: 5px; font-weight: 700; overflow-wrap: anywhere; }
    table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid #d7d7cf; }
    th, td { padding: 10px; border-bottom: 1px solid #e6e6df; text-align: left; vertical-align: top; }
    th { background: #efefe8; font-size: 13px; }
    .statements { margin: 0; padding-left: 18px; }
    .statements li { margin: 8px 0; }
  </style>
</head>
<body>
  <main>${body}</main>
</body>
</html>`;
}

function renderErrorHtml(status, message) {
  return renderHtmlPage({
    title: `Ludo round ${status}`,
    body: `<h1>Ludo round request failed</h1>
<div class="field"><span class="label">Status</span><span class="value">${status}</span></div>
<div class="field"><span class="label">Message</span><span class="value">${escapeHtml(message)}</span></div>`,
  });
}

function buildDebitStatement(player, round) {
  return `Amount ${formatAmount(player.betAmount, round.currency)} debited for Ludo game round ${round.roundId}`;
}

function buildCreditStatement(player, round) {
  return `Amount ${formatAmount(player.winAmount, round.currency)} credited for Ludo game round ${round.roundId}`;
}

function renderSingleRoundHtml(round) {
  const winnerText = round.winner
    ? `${round.winner.displayName || round.winner.userId} (${formatAmount(round.winner.winAmount, round.currency)})`
    : "Not available";
  const playerRows = round.players.map((player) => {
    const creditStatement = player.winAmount > 0
      ? `<br>${escapeHtml(buildCreditStatement(player, round))}`
      : "";

    return `
    <tr>
      <td>${escapeHtml(player.userId)}</td>
      <td>${escapeHtml(player.displayName || "Player")}</td>
      <td>${escapeHtml(player.color || "")}</td>
      <td>${player.isBot ? "Yes" : "No"}</td>
      <td>${escapeHtml(formatAmount(player.betAmount, round.currency))}</td>
      <td>${player.isWinner ? "Yes" : "No"}</td>
      <td>${escapeHtml(formatAmount(player.winAmount, round.currency))}</td>
      <td>${escapeHtml(buildDebitStatement(player, round))}${creditStatement}</td>
    </tr>`;
  }).join("");
  const statements = round.players.flatMap((player) => {
    const lines = [buildDebitStatement(player, round)];
    if (player.winAmount > 0) {
      lines.push(buildCreditStatement(player, round));
    }
    return lines;
  }).map((statement) => `<li>${escapeHtml(statement)}</li>`).join("");

  return renderHtmlPage({
    title: `Ludo round ${round.roundId}`,
    body: `<h1>Ludo Round ${escapeHtml(round.roundId)}</h1>
<section class="summary">
  <div class="field"><span class="label">Game</span><span class="value">Ludo</span></div>
  <div class="field"><span class="label">Round ID</span><span class="value">${escapeHtml(round.roundId)}</span></div>
  <div class="field"><span class="label">Lobby ID</span><span class="value">${escapeHtml(round.roomId)}</span></div>
  <div class="field"><span class="label">Lobby Code</span><span class="value">${escapeHtml(round.roomCode || "Not available")}</span></div>
  <div class="field"><span class="label">Status</span><span class="value">${escapeHtml(round.status)}</span></div>
  <div class="field"><span class="label">Entry Fee</span><span class="value">${escapeHtml(formatAmount(round.entryFee, round.currency))}</span></div>
  <div class="field"><span class="label">Total Pot</span><span class="value">${escapeHtml(formatAmount(round.totalPotAmount, round.currency))}</span></div>
  <div class="field"><span class="label">Winner</span><span class="value">${escapeHtml(winnerText)}</span></div>
  <div class="field"><span class="label">Started At</span><span class="value">${escapeHtml(formatDate(round.startedAt))}</span></div>
  <div class="field"><span class="label">Completed At</span><span class="value">${escapeHtml(formatDate(round.completedAt))}</span></div>
</section>
<h2>Ludo Statements</h2>
<ul class="statements">${statements}</ul>
<h2>Ludo Players</h2>
<table>
  <thead>
    <tr>
      <th>User ID</th>
      <th>Name</th>
      <th>Color</th>
      <th>Bot</th>
      <th>Bet Amount</th>
      <th>Winner</th>
      <th>Win Amount</th>
      <th>Ludo Statement</th>
    </tr>
  </thead>
  <tbody>${playerRows}</tbody>
</table>`,
  });
}

function calculateWinnerAmount(potAmount) {
  const rakeAmount = Math.floor(
    (potAmount * config.payoutRakeBasisPoints) / 10_000,
  );

  return potAmount - rakeAmount;
}

function indexRoomParticipants(room) {
  const seats = Array.isArray(room?.seats) ? room.seats : [];
  const reservations = Array.isArray(room?.walletReservations)
    ? room.walletReservations
    : [];
  const reservationsByUserId = new Map();

  for (const reservation of reservations) {
    if (reservation?.userId) {
      reservationsByUserId.set(reservation.userId, reservation);
    }
  }

  const realReservations = reservations.filter(
    (reservation) => !reservation?.synthetic,
  );
  const botReservations = reservations.filter(
    (reservation) => reservation?.synthetic,
  );
  let realReservationIndex = 0;
  let botReservationIndex = 0;
  const participantsByStoredUserId = new Map();

  for (const seat of seats) {
    const exactReservation = reservationsByUserId.get(seat.userId);
    const positionalReservation = seat.isBot
      ? botReservations[botReservationIndex++]
      : realReservations[realReservationIndex++];
    const reservation = exactReservation || positionalReservation;

    participantsByStoredUserId.set(seat.userId, {
      publicUserId: reservation?.userId || seat.userId,
      betAmount: safeAmount(reservation?.amount),
    });
  }

  return participantsByStoredUserId;
}

function buildRound(match, room) {
  const reservations = Array.isArray(room?.walletReservations)
    ? room.walletReservations
    : [];
  const betsByUserId = new Map();

  for (const reservation of reservations) {
    if (!reservation?.userId) {
      continue;
    }

    const previousAmount = betsByUserId.get(reservation.userId) || 0;
    betsByUserId.set(
      reservation.userId,
      previousAmount + safeAmount(reservation.amount),
    );
  }

  const storedPlayers = Array.isArray(match.players) && match.players.length > 0
    ? match.players
    : room?.seats || [];
  const roomParticipants = indexRoomParticipants(room);
  const reservationTotal = [...betsByUserId.values()]
    .reduce((total, amount) => total + amount, 0);
  const storedPotAmount = safeAmount(match.potAmount);
  const totalPotAmount = reservations.length > 0
    ? reservationTotal
    : storedPotAmount;
  const winnerAmount = match.winnerUserId
    ? calculateWinnerAmount(totalPotAmount)
    : 0;

  const players = storedPlayers.map((player) => {
    const isWinner = player.userId === match.winnerUserId;
    const isAbandoned = Boolean(
      player.isAbandoned || player.userId?.startsWith("abandoned_"),
    );
    const roomParticipant = roomParticipants.get(player.userId);
    const publicUserId = roomParticipant?.publicUserId || player.userId;
    const betAmount = roomParticipant?.betAmount
      ?? betsByUserId.get(player.userId)
      ?? 0;

    return {
      userId: publicUserId,
      displayName: player.displayName,
      color: player.color,
      isBot: Boolean(player.isBot),
      isAbandoned,
      betAmount,
      isWinner,
      winAmount: isWinner ? winnerAmount : 0,
    };
  });

  const winnerPlayer = players.find((player) => player.isWinner);

  return {
    game: "ludo",
    roundId: String(match._id),
    roomId: match.roomId || (room?._id ? String(room._id) : null),
    roomCode: match.roomCode || room?.code || null,
    mode: match.mode || room?.mode || null,
    status: match.status,
    startedAt: match.createdAt || null,
    completedAt: match.updatedAt || null,
    currency: config.walletCurrency,
    entryFee: safeAmount(match.entryFee ?? room?.entryFee),
    totalPotAmount,
    players,
    winner: match.winnerUserId
      ? {
          userId: winnerPlayer?.userId || match.winnerUserId,
          displayName: match.winnerDisplayName || winnerPlayer?.displayName || null,
          isBot: winnerPlayer?.isBot || false,
          betAmount: winnerPlayer?.betAmount
            ?? betsByUserId.get(match.winnerUserId)
            ?? 0,
          winAmount: winnerAmount,
        }
      : null,
  };
}

class RoundsService {
  constructor(database) {
    this.matches = database.collection("matches");
    this.rooms = database.collection("rooms");
  }

  async listLudoRounds({ page, limit }) {
    const filter = { status: "FINISHED" };
    const skip = (page - 1) * limit;

    const [totalItems, matches] = await Promise.all([
      this.matches.countDocuments(filter),
      this.matches
        .find(filter, { projection: MATCH_PROJECTION })
        .sort({ updatedAt: -1, _id: -1 })
        .skip(skip)
        .limit(limit)
        .toArray(),
    ]);

    const roomIds = [...new Set(
      matches.map((match) => match.roomId).filter(Boolean),
    )];
    const rooms = roomIds.length === 0
      ? []
      : await this.rooms
          .find({ _id: { $in: roomIds } }, { projection: ROOM_PROJECTION })
          .toArray();
    const roomsById = new Map(
      rooms.map((room) => [String(room._id), room]),
    );
    const totalPages = totalItems === 0 ? 0 : Math.ceil(totalItems / limit);

    return {
      data: matches.map((match) => buildRound(
        match,
        roomsById.get(String(match.roomId)),
      )),
      pagination: {
        page,
        limit,
        totalItems,
        totalPages,
        hasNextPage: page < totalPages,
        hasPreviousPage: page > 1 && totalPages > 0,
      },
    };
  }

  async getSingleLudoRoundHtml({ userId, operatorId, lobbyId }) {
    const room = await this.rooms.findOne(
      {
        _id: lobbyId,
        walletReservations: {
          $elemMatch: {
            userId,
            operatorId,
            synthetic: { $ne: true },
          },
        },
      },
      { projection: ROOM_PROJECTION },
    );

    if (!room) {
      throw new HttpError(
        404,
        "ROUND_NOT_FOUND",
        "Ludo round was not found for the supplied user, operator, and lobby.",
      );
    }

    const match = await this.matches.findOne(
      {
        roomId: lobbyId,
        status: "FINISHED",
      },
      {
        projection: MATCH_PROJECTION,
        sort: { updatedAt: -1, _id: -1 },
      },
    );

    if (!match) {
      throw new HttpError(
        404,
        "ROUND_NOT_FOUND",
        "Completed Ludo round was not found for the supplied lobby.",
      );
    }

    return renderSingleRoundHtml(buildRound(match, room));
  }
}

module.exports = { RoundsService, renderErrorHtml };
