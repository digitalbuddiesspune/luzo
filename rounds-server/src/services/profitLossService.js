const { config } = require("../config/env");
const { HttpError } = require("../errors/httpError");
const { buildRound } = require("./roundsService");

const GUEST_OPERATOR_ID = "guest";
const GUEST_OPERATOR_LABEL = "Direct / Guest";

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
  "players.operatorId": 1,
  "players.operatorUserId": 1,
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
  "seats.operatorId": 1,
  "seats.operatorUserId": 1,
  "walletReservations.userId": 1,
  "walletReservations.amount": 1,
  "walletReservations.synthetic": 1,
  "walletReservations.operatorId": 1,
  "walletReservations.operatorUserId": 1,
};

function calculatePlatformFee(amount, platformFeePerPlayer = config.platformFeePerPlayer) {
  if (!amount || amount <= 0) {
    return 0;
  }

  return Math.min(amount, Math.max(0, platformFeePerPlayer));
}

function calculateRakeOnPot(potAmount, seatCount, platformFeePerPlayer = config.platformFeePerPlayer) {
  return Math.min(
    potAmount,
    Math.max(0, seatCount) * Math.max(0, platformFeePerPlayer),
  );
}

function normalizeOperatorId(operatorId) {
  if (operatorId === undefined || operatorId === null || String(operatorId).trim() === "") {
    return GUEST_OPERATOR_ID;
  }

  return String(operatorId).trim();
}

function operatorLabel(operatorId) {
  const normalized = normalizeOperatorId(operatorId);
  return normalized === GUEST_OPERATOR_ID ? GUEST_OPERATOR_LABEL : normalized;
}

function buildGameProfitLoss(round) {
  const platformFeePerPlayer = round.platformFeePerPlayer ?? config.platformFeePerPlayer;
  const realPlayers = round.players.filter((player) => !player.isBot);
  const botPlayers = round.players.filter((player) => player.isBot);
  const totalRealIncome = realPlayers.reduce(
    (total, player) => total + player.betAmount,
    0,
  );
  const displayPotRake = calculateRakeOnPot(
    round.totalPotAmount,
    round.players.length,
    platformFeePerPlayer,
  );
  const realPotRake = calculateRakeOnPot(
    totalRealIncome,
    realPlayers.length,
    platformFeePerPlayer,
  );
  const winnerIsHouse = Boolean(round.winner?.isHouse);
  const winnerIsReal = Boolean(round.winner) && !round.winner.isBot && !winnerIsHouse;
  const winnerPayout = winnerIsReal ? round.winner.winAmount : 0;
  const platformProfit = totalRealIncome - winnerPayout;

  const players = round.players.map((player) => {
    const platformFee = calculatePlatformFee(player.betAmount, platformFeePerPlayer);
    const profitLoss = player.isBot
      ? null
      : player.winAmount - player.betAmount;
    const operatorId = player.isBot ? null : normalizeOperatorId(player.operatorId);

    return {
      ...player,
      operatorId,
      operatorLabel: player.isBot ? null : operatorLabel(operatorId),
      platformFee,
      profitLoss,
    };
  });

  const operatorIds = [
    ...new Set(
      players
        .filter((player) => !player.isBot)
        .map((player) => player.operatorId),
    ),
  ];

  return {
    game: round.game,
    roundId: round.roundId,
    roomId: round.roomId,
    roomCode: round.roomCode,
    mode: round.mode,
    status: round.status,
    currency: round.currency,
    entryFee: round.entryFee,
    platformFeePerPlayer,
    playerCount: round.players.length,
    realPlayerCount: realPlayers.length,
    botPlayerCount: botPlayers.length,
    displayPotAmount: round.totalPotAmount,
    totalRealIncome,
    displayPotRake,
    realPotRake,
    platformProfit,
    winnerPayout,
    operatorIds,
    winner: round.winner
      ? {
          ...round.winner,
          isReal: winnerIsReal,
          isHouse: winnerIsHouse,
        }
      : null,
    players,
    startedAt: round.startedAt,
    completedAt: round.completedAt,
  };
}

function gameMatchesOperator(game, operatorId) {
  if (!operatorId) {
    return true;
  }

  return game.operatorIds.includes(normalizeOperatorId(operatorId));
}

function accumulateUserStats(userStats, game) {
  for (const player of game.players) {
    if (player.isBot) {
      continue;
    }

    const operatorId = normalizeOperatorId(player.operatorId);
    const key = `${operatorId}::${player.userId}`;
    const existing = userStats.get(key) || {
      userId: player.userId,
      displayName: player.displayName || "Player",
      operatorId,
      operatorLabel: operatorLabel(operatorId),
      gamesPlayed: 0,
      wins: 0,
      losses: 0,
      totalBet: 0,
      totalWin: 0,
      totalPlatformFee: 0,
      profitLoss: 0,
    };

    existing.gamesPlayed += 1;
    existing.totalBet += player.betAmount;
    existing.totalWin += player.winAmount;
    existing.totalPlatformFee += player.platformFee;
    existing.profitLoss += player.profitLoss ?? 0;

    if (player.isWinner) {
      existing.wins += 1;
    } else {
      existing.losses += 1;
    }

    if (player.displayName) {
      existing.displayName = player.displayName;
    }

    userStats.set(key, existing);
  }
}

function emptyOperatorStats(operatorId) {
  return {
    operatorId,
    label: operatorLabel(operatorId),
    totalGames: 0,
    uniqueUsers: 0,
    totalSeats: 0,
    totalRealIncome: 0,
    totalPlatformProfit: 0,
    totalWinnerPayout: 0,
    totalPlatformFee: 0,
    _userIds: new Set(),
  };
}

function buildOperatorBreakdown(games) {
  const byOperator = new Map();

  for (const game of games) {
    const realPlayers = game.players.filter((player) => !player.isBot);
    if (realPlayers.length === 0) {
      continue;
    }

    const incomeByOperator = new Map();
    const seatsByOperator = new Map();
    const usersByOperator = new Map();
    const feeByOperator = new Map();
    const payoutByOperator = new Map();

    for (const player of realPlayers) {
      const operatorId = normalizeOperatorId(player.operatorId);
      incomeByOperator.set(
        operatorId,
        (incomeByOperator.get(operatorId) || 0) + player.betAmount,
      );
      seatsByOperator.set(operatorId, (seatsByOperator.get(operatorId) || 0) + 1);
      feeByOperator.set(
        operatorId,
        (feeByOperator.get(operatorId) || 0) + (player.platformFee || 0),
      );
      payoutByOperator.set(
        operatorId,
        (payoutByOperator.get(operatorId) || 0) + (player.winAmount || 0),
      );

      if (!usersByOperator.has(operatorId)) {
        usersByOperator.set(operatorId, new Set());
      }
      usersByOperator.get(operatorId).add(player.userId);
    }

    for (const operatorId of incomeByOperator.keys()) {
      const existing = byOperator.get(operatorId) || emptyOperatorStats(operatorId);
      const income = incomeByOperator.get(operatorId) || 0;
      const share = game.totalRealIncome > 0 ? income / game.totalRealIncome : 0;
      const attributedProfit = Math.round(game.platformProfit * share);

      existing.totalGames += 1;
      existing.totalSeats += seatsByOperator.get(operatorId) || 0;
      existing.totalRealIncome += income;
      existing.totalPlatformProfit += attributedProfit;
      existing.totalWinnerPayout += payoutByOperator.get(operatorId) || 0;
      existing.totalPlatformFee += feeByOperator.get(operatorId) || 0;

      for (const userId of usersByOperator.get(operatorId) || []) {
        existing._userIds.add(userId);
      }

      byOperator.set(operatorId, existing);
    }
  }

  return [...byOperator.values()]
    .map((entry) => {
      const { _userIds, ...rest } = entry;
      return {
        ...rest,
        uniqueUsers: _userIds.size,
      };
    })
    .sort((left, right) => right.totalPlatformProfit - left.totalPlatformProfit);
}

function buildSummary(games) {
  const totals = games.reduce(
    (summary, game) => ({
      totalGames: summary.totalGames + 1,
      totalRealIncome: summary.totalRealIncome + game.totalRealIncome,
      totalPlatformProfit: summary.totalPlatformProfit + game.platformProfit,
      totalWinnerPayout: summary.totalWinnerPayout + game.winnerPayout,
      totalRealPlayers: summary.totalRealPlayers + game.realPlayerCount,
      totalBotPlayers: summary.totalBotPlayers + game.botPlayerCount,
    }),
    {
      totalGames: 0,
      totalRealIncome: 0,
      totalPlatformProfit: 0,
      totalWinnerPayout: 0,
      totalRealPlayers: 0,
      totalBotPlayers: 0,
    },
  );

  return {
    currency: config.walletCurrency,
    platformFeePerPlayer: games[0]?.platformFeePerPlayer ?? config.platformFeePerPlayer,
    ...totals,
    byOperator: buildOperatorBreakdown(games),
  };
}

function buildMatchFilter(playerCount, dateFrom, dateTo) {
  const filter = { status: "FINISHED" };

  if (playerCount === 2 || playerCount === 4) {
    filter.$expr = { $eq: [{ $size: { $ifNull: ["$players", []] } }, playerCount] };
  }

  if (dateFrom || dateTo) {
    filter.updatedAt = {};

    if (dateFrom) {
      filter.updatedAt.$gte = dateFrom;
    }

    if (dateTo) {
      filter.updatedAt.$lte = dateTo;
    }
  }

  return filter;
}

function formatDateFilter(date) {
  if (!date) {
    return null;
  }

  return date.toISOString().slice(0, 10);
}

function buildFilterMetadata({ playerCount, operatorId, dateFrom, dateTo }) {
  return {
    playerCount: playerCount || null,
    operatorId: operatorId ? normalizeOperatorId(operatorId) : null,
    dateFrom: formatDateFilter(dateFrom),
    dateTo: formatDateFilter(dateTo),
  };
}

function buildPagination({ page, limit, totalItems }) {
  const totalPages = totalItems === 0 ? 0 : Math.ceil(totalItems / limit);

  return {
    page,
    limit,
    totalItems,
    totalPages,
    hasNextPage: page < totalPages,
    hasPreviousPage: page > 1 && totalPages > 0,
  };
}

class ProfitLossService {
  constructor(database, platformSettingsService = null) {
    this.matches = database.collection("matches");
    this.rooms = database.collection("rooms");
    this.platformSettingsService = platformSettingsService;
  }

  async resolvePlatformFeePerPlayer() {
    if (!this.platformSettingsService) {
      return config.platformFeePerPlayer;
    }
    const settings = await this.platformSettingsService.getSettings();
    return settings.platformFeePerPlayer;
  }

  async attachRooms(matches) {
    const platformFeePerPlayer = await this.resolvePlatformFeePerPlayer();
    const roomIds = [...new Set(matches.map((match) => match.roomId).filter(Boolean))];
    const rooms = roomIds.length === 0
      ? []
      : await this.rooms
          .find({ _id: { $in: roomIds } }, { projection: ROOM_PROJECTION })
          .toArray();
    const roomsById = new Map(rooms.map((room) => [String(room._id), room]));

    return matches.map((match) => buildGameProfitLoss(
      buildRound(match, roomsById.get(String(match.roomId)), platformFeePerPlayer),
    ));
  }

  async loadFinishedGames({ limit = 0, playerCount, operatorId, dateFrom, dateTo } = {}) {
    const cursor = this.matches
      .find(buildMatchFilter(playerCount, dateFrom, dateTo), { projection: MATCH_PROJECTION })
      .sort({ updatedAt: -1, _id: -1 });

    const matches = limit > 0 ? await cursor.limit(limit).toArray() : await cursor.toArray();
    const games = await this.attachRooms(matches);

    if (!operatorId) {
      return games;
    }

    const normalized = normalizeOperatorId(operatorId);
    return games.filter((game) => gameMatchesOperator(game, normalized));
  }

  async listGames({ page, limit, playerCount, operatorId, dateFrom, dateTo }) {
    const games = await this.loadFinishedGames({
      playerCount,
      operatorId,
      dateFrom,
      dateTo,
    });
    const totalItems = games.length;
    const skip = (page - 1) * limit;

    return {
      data: games.slice(skip, skip + limit),
      pagination: buildPagination({ page, limit, totalItems }),
      filters: buildFilterMetadata({ playerCount, operatorId, dateFrom, dateTo }),
    };
  }

  async getSummary({ playerCount, operatorId, dateFrom, dateTo } = {}) {
    const allGames = await this.loadFinishedGames({ playerCount, dateFrom, dateTo });
    const byOperator = buildOperatorBreakdown(allGames);

    if (!operatorId) {
      return {
        ...buildSummary(allGames),
        filters: buildFilterMetadata({ playerCount, operatorId, dateFrom, dateTo }),
      };
    }

    const normalized = normalizeOperatorId(operatorId);
    const operatorStats = byOperator.find((entry) => entry.operatorId === normalized)
      || emptyOperatorStats(normalized);
    const { _userIds, ...safeStats } = operatorStats;
    const filteredGames = allGames.filter((game) => gameMatchesOperator(game, normalized));

    return {
      currency: config.walletCurrency,
      platformFeePerPlayer: filteredGames[0]?.platformFeePerPlayer ?? config.platformFeePerPlayer,
      totalGames: safeStats.totalGames ?? 0,
      totalRealIncome: safeStats.totalRealIncome ?? 0,
      totalPlatformProfit: safeStats.totalPlatformProfit ?? 0,
      totalWinnerPayout: safeStats.totalWinnerPayout ?? 0,
      totalRealPlayers: safeStats.totalSeats ?? 0,
      totalBotPlayers: filteredGames.reduce((sum, game) => sum + game.botPlayerCount, 0),
      byOperator,
      selectedOperator: {
        ...safeStats,
        uniqueUsers: safeStats.uniqueUsers ?? (_userIds ? _userIds.size : 0),
      },
      filters: buildFilterMetadata({
        playerCount,
        operatorId: normalized,
        dateFrom,
        dateTo,
      }),
    };
  }

  async deleteGame(roundId) {
    const normalizedRoundId = typeof roundId === "string" ? roundId.trim() : "";

    if (!normalizedRoundId) {
      throw new HttpError(
        400,
        "INVALID_ROUND_ID",
        "roundId is required.",
      );
    }

    const result = await this.matches.deleteOne({
      _id: normalizedRoundId,
      status: "FINISHED",
    });

    if (result.deletedCount === 0) {
      throw new HttpError(
        404,
        "GAME_NOT_FOUND",
        "Finished game was not found.",
      );
    }

    return {
      roundId: normalizedRoundId,
      deleted: true,
    };
  }

  async listUsers({ page, limit, playerCount, operatorId, dateFrom, dateTo }) {
    const games = await this.loadFinishedGames({
      playerCount,
      operatorId,
      dateFrom,
      dateTo,
    });
    const userStats = new Map();

    for (const game of games) {
      accumulateUserStats(userStats, game);
    }

    let users = [...userStats.values()];

    if (operatorId) {
      const normalized = normalizeOperatorId(operatorId);
      users = users.filter((user) => user.operatorId === normalized);
    }

    users.sort((left, right) => right.gamesPlayed - left.gamesPlayed);
    const totalItems = users.length;
    const skip = (page - 1) * limit;

    return {
      data: users.slice(skip, skip + limit),
      pagination: buildPagination({ page, limit, totalItems }),
      filters: buildFilterMetadata({ playerCount, operatorId, dateFrom, dateTo }),
    };
  }
}

module.exports = {
  ProfitLossService,
  buildGameProfitLoss,
  calculatePlatformFee,
  normalizeOperatorId,
  GUEST_OPERATOR_ID,
};
