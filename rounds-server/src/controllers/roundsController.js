const { HttpError } = require("../errors/httpError");

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

function parseRequiredString(value, name) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new HttpError(
      400,
      "INVALID_ROUND_LOOKUP",
      `${name} is required.`,
    );
  }

  return value.trim();
}

function createRoundsController(roundsService) {
  return {
    getSingleLudoRoundHtml: async (request, response) => {
      const userId = parseRequiredString(request.query.user_id, "user_id");
      const operatorId = parseRequiredString(
        request.query.operator_id,
        "operator_id",
      );
      const lobbyId = parseRequiredString(request.query.lobby_id, "lobby_id");
      const html = await roundsService.getSingleLudoRoundHtml({
        userId,
        operatorId,
        lobbyId,
      });

      response.type("html").send(html);
    },

    listLudoRounds: async (request, response) => {
      const page = parsePositiveInteger(request.query.page, "page", 1);
      const limit = parsePositiveInteger(request.query.limit, "limit", 20, 100);
      const result = await roundsService.listLudoRounds({ page, limit });

      response.json(result);
    },
  };
}

module.exports = { createRoundsController };
