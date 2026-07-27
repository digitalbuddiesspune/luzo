const { HttpError } = require("../errors/httpError");
const { normalizeOperatorId } = require("../services/profitLossService");

function parsePositiveInteger(value, name, defaultValue, maximum) {
  if (value === undefined) {
    return defaultValue;
  }

  if (typeof value !== "string" || !/^\d+$/.test(value)) {
    throw new HttpError(
      400,
      "INVALID_PAGINATION",
      `${name} must be a positive integer.`,
    );
  }

  const parsedValue = Number(value);

  if (!Number.isSafeInteger(parsedValue) || parsedValue < 1) {
    throw new HttpError(
      400,
      "INVALID_PAGINATION",
      `${name} must be a positive integer.`,
    );
  }

  if (maximum !== undefined && parsedValue > maximum) {
    throw new HttpError(
      400,
      "INVALID_PAGINATION",
      `${name} must be no greater than ${maximum}.`,
    );
  }

  return parsedValue;
}

function parsePlayerCount(value) {
  if (value === undefined || value === null || value === "" || value === "all") {
    return null;
  }

  if (typeof value !== "string" || !/^\d+$/.test(value)) {
    throw new HttpError(
      400,
      "INVALID_PLAYER_COUNT",
      "players must be 2, 4, or all.",
    );
  }

  const parsedValue = Number(value);

  if (parsedValue !== 2 && parsedValue !== 4) {
    throw new HttpError(
      400,
      "INVALID_PLAYER_COUNT",
      "players must be 2, 4, or all.",
    );
  }

  return parsedValue;
}

function parseOperatorId(value) {
  if (value === undefined || value === null || value === "" || value === "all") {
    return null;
  }

  if (typeof value !== "string") {
    throw new HttpError(
      400,
      "INVALID_OPERATOR",
      "operatorId must be a string, guest, or all.",
    );
  }

  return normalizeOperatorId(value);
}

function createProfitLossController(profitLossService) {
  return {
    getSummary: async (request, response) => {
      const playerCount = parsePlayerCount(request.query.players);
      const operatorId = parseOperatorId(request.query.operatorId);
      const summary = await profitLossService.getSummary({ playerCount, operatorId });
      response.json(summary);
    },

    listGames: async (request, response) => {
      const page = parsePositiveInteger(request.query.page, "page", 1);
      const limit = parsePositiveInteger(request.query.limit, "limit", 20, 100);
      const playerCount = parsePlayerCount(request.query.players);
      const operatorId = parseOperatorId(request.query.operatorId);
      const result = await profitLossService.listGames({
        page,
        limit,
        playerCount,
        operatorId,
      });
      response.json(result);
    },

    listUsers: async (request, response) => {
      const page = parsePositiveInteger(request.query.page, "page", 1);
      const limit = parsePositiveInteger(request.query.limit, "limit", 20, 100);
      const playerCount = parsePlayerCount(request.query.players);
      const operatorId = parseOperatorId(request.query.operatorId);
      const result = await profitLossService.listUsers({
        page,
        limit,
        playerCount,
        operatorId,
      });
      response.json(result);
    },
  };
}

module.exports = { createProfitLossController };
