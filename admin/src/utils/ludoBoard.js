const BOARD_PATH = [
  [6, 1], [6, 2], [6, 3], [6, 4], [6, 5],
  [5, 6], [4, 6], [3, 6], [2, 6], [1, 6], [0, 6],
  [0, 7], [0, 8],
  [1, 8], [2, 8], [3, 8], [4, 8], [5, 8],
  [6, 9], [6, 10], [6, 11], [6, 12], [6, 13], [6, 14],
  [7, 14], [8, 14],
  [8, 13], [8, 12], [8, 11], [8, 10], [8, 9],
  [9, 8], [10, 8], [11, 8], [12, 8], [13, 8], [14, 8],
  [14, 7], [14, 6],
  [13, 6], [12, 6], [11, 6], [10, 6], [9, 6],
  [8, 5], [8, 4], [8, 3], [8, 2], [8, 1], [8, 0],
  [7, 0], [6, 0],
];

const START_OFFSETS = { red: 0, green: 13, yellow: 26, blue: 39 };

const YARD_POSITIONS = {
  red: [[1, 1], [1, 4], [4, 1], [4, 4]],
  green: [[1, 10], [1, 13], [4, 10], [4, 13]],
  yellow: [[10, 10], [10, 13], [13, 10], [13, 13]],
  blue: [[10, 1], [10, 4], [13, 1], [13, 4]],
};

const HOME_LANES = {
  red: [[7, 1], [7, 2], [7, 3], [7, 4], [7, 5]],
  green: [[1, 7], [2, 7], [3, 7], [4, 7], [5, 7]],
  yellow: [[7, 13], [7, 12], [7, 11], [7, 10], [7, 9]],
  blue: [[13, 7], [12, 7], [11, 7], [10, 7], [9, 7]],
};

/** Arrow / entry cells just before home stretch */
const ARROW_CELLS = {
  "7-0": "red",
  "0-7": "green",
  "7-14": "yellow",
  "14-7": "blue",
};

const START_CELLS = {
  "6-1": "red",
  "1-8": "green",
  "8-13": "yellow",
  "13-6": "blue",
};

const STAR_CELLS = new Set(["2-6", "6-12", "12-8", "8-2"]);

const COLOR_HEX = {
  red: "#e53935",
  green: "#43a047",
  yellow: "#fbc02d",
  blue: "#1e88e5",
};

export const BOARD_PATH_SET = new Set(BOARD_PATH.map(([r, c]) => `${r}-${c}`));

export const HOME_LANE_LOOKUP = new Map(
  Object.entries(HOME_LANES).flatMap(([color, cells]) =>
    cells.map(([row, col]) => [`${row}-${col}`, color]),
  ),
);

export const WINNER_REASON_LABELS = {
  HOME: "Finished normally — all tokens home",
  FORFEIT: "Won by forfeit — opponent left",
  ABANDON_BOT: "Bot won after real players left",
  HOUSE: "Platform kept the pot — no real players left",
};

export function winnerReasonLabel(reason) {
  if (!reason) return null;
  return WINNER_REASON_LABELS[reason] || reason;
}

function formatPlayerName(player, { house = false } = {}) {
  if (!player) return "Unknown";
  const name = player.displayName || "Unknown";
  if (house || player.isHouse) return `${name} (Platform)`;
  if (player.isBot) return `${name} (Bot)`;
  return name;
}

function summarizeTokenPositions(tokens) {
  if (!Array.isArray(tokens) || tokens.length === 0) return null;

  const counts = { finished: 0, home: 0, path: 0, yard: 0, unknown: 0 };
  for (const progress of tokens) {
    const label = tokenProgressLabel(progress);
    if (label === "Finished") counts.finished += 1;
    else if (label === "Yard") counts.yard += 1;
    else if (label.startsWith("Home lane")) counts.home += 1;
    else if (label.startsWith("Path")) counts.path += 1;
    else counts.unknown += 1;
  }

  const parts = [];
  if (counts.finished) parts.push(`${counts.finished} finished`);
  if (counts.home) parts.push(`${counts.home} in home lane`);
  if (counts.path) parts.push(`${counts.path} on path`);
  if (counts.yard) parts.push(`${counts.yard} in yard`);
  if (counts.unknown) parts.push(`${counts.unknown} unknown`);
  return parts.join(", ");
}

function listNames(players) {
  return players.map((player) => formatPlayerName(player)).join(", ");
}

function inferWinnerReason(game) {
  if (game?.winnerReason) return game.winnerReason;

  const players = game?.players || [];
  const leavers = players.filter((player) => player.isAbandoned);
  const winnerPlayer = players.find((player) => player.isWinner);

  if (game?.winner?.isHouse) return "HOUSE";
  if (game?.winner?.isBot && leavers.length > 0) return "ABANDON_BOT";

  const activePlayers = players.filter((player) => !player.isAbandoned);
  if (leavers.length === 1 && activePlayers.length === 1 && game?.winner) {
    return "FORFEIT";
  }

  const winnerTokens = winnerPlayer?.tokens;
  if (
    Array.isArray(winnerTokens) &&
    winnerTokens.length > 0 &&
    winnerTokens.every((progress) => Number(progress) >= 56)
  ) {
    return "HOME";
  }

  return null;
}

/**
 * Build a detailed "How it ended" narrative for the admin game modal.
 * @returns {{ headline: string, details: string[], reasonCode: string|null, inferred: boolean }}
 */
export function buildHowItEndedSummary(game) {
  const players = game?.players || [];
  const leavers = players.filter((player) => player.isAbandoned);
  const reasonCode = inferWinnerReason(game);
  const inferred = Boolean(!game?.winnerReason && reasonCode);
  const winnerName = game?.winner
    ? formatPlayerName(game.winner, { house: game.winner.isHouse })
    : null;
  const winnerPlayer =
    players.find((player) => player.isWinner) ||
    players.find((player) => player.userId === game?.winner?.userId);

  const details = [];
  let headline = winnerReasonLabel(reasonCode);

  switch (reasonCode) {
    case "HOME":
      details.push(
        winnerName
          ? `${winnerName} won by getting all four tokens home.`
          : "A player won by getting all four tokens home.",
      );
      break;
    case "FORFEIT": {
      const leaverText = leavers.length
        ? listNames(leavers)
        : "the opponent";
      details.push(
        winnerName
          ? `${winnerName} won because ${leaverText} left the 2-player match.`
          : `Match ended after ${leaverText} left the 2-player match.`,
      );
      break;
    }
    case "ABANDON_BOT":
      details.push(
        winnerName
          ? `${winnerName} won because no real players remained.`
          : "A bot won because no real players remained.",
      );
      if (leavers.length) {
        details.push(`Players who left: ${listNames(leavers)}.`);
      }
      details.push("No real-player payout — the platform kept the real pot.");
      break;
    case "HOUSE":
      details.push(
        "Match ended because no real players remained. Entry fees went to the platform.",
      );
      if (leavers.length) {
        details.push(`Players who left: ${listNames(leavers)}.`);
      }
      break;
    default:
      if (winnerName) {
        headline = "Result recorded";
        details.push(`Winner: ${winnerName}.`);
        if (leavers.length) {
          details.push(`Players who left: ${listNames(leavers)}.`);
        }
        details.push("Exact end reason was not stored for this match.");
      } else {
        headline = "No winner recorded";
        if (leavers.length) {
          details.push(`Players who left: ${listNames(leavers)}.`);
        } else {
          details.push("This match has no winner or leave details saved.");
        }
      }
  }

  if (game?.endMessage) {
    const alreadyCovered = details.some(
      (line) => line === game.endMessage || line.includes(game.endMessage),
    );
    if (!alreadyCovered) {
      details.unshift(game.endMessage);
    }
  }

  if (winnerPlayer) {
    const winnerTokens = summarizeTokenPositions(winnerPlayer.tokens);
    if (winnerTokens) {
      details.push(`Winner tokens at end: ${winnerTokens}.`);
    } else if (reasonCode && reasonCode !== "HOUSE") {
      details.push("Winner token positions were not saved.");
    }
  }

  for (const leaver of leavers) {
    if (winnerPlayer && leaver.userId === winnerPlayer.userId) continue;
    const leaverTokens = summarizeTokenPositions(leaver.tokens);
    if (leaverTokens) {
      details.push(`${formatPlayerName(leaver)} left with tokens: ${leaverTokens}.`);
    } else {
      details.push(`${formatPlayerName(leaver)} left (token positions not saved).`);
    }
  }

  if (inferred) {
    details.push("End reason inferred from final player state (not stamped on the match).");
  }

  return {
    headline: headline || "No winner recorded",
    details,
    reasonCode,
    inferred,
  };
}

export function tokenProgressLabel(progress) {
  if (progress == null || Number.isNaN(Number(progress))) return "Unknown";
  const value = Number(progress);
  if (value < 0) return "Yard";
  if (value >= 56) return "Finished";
  if (value >= 51) return `Home lane ${value - 50}`;
  return `Path ${value + 1}`;
}

export function resolveTokenCell(color, progress, tokenIndex) {
  const key = String(color || "").toLowerCase();
  if (progress == null || Number.isNaN(Number(progress))) return null;
  const value = Number(progress);

  if (value < 0) {
    return YARD_POSITIONS[key]?.[tokenIndex] || null;
  }
  if (value >= 0 && value <= 50) {
    const offset = START_OFFSETS[key];
    if (offset == null) return null;
    return BOARD_PATH[(offset + value) % 52];
  }
  if (value >= 51 && value <= 55) {
    return HOME_LANES[key]?.[value - 51] || null;
  }
  return [7, 7];
}

export function colorHex(color) {
  return COLOR_HEX[String(color || "").toLowerCase()] || "#64748b";
}

export function getCellMeta(row, col) {
  const key = `${row}-${col}`;
  const isCenter = row >= 6 && row <= 8 && col >= 6 && col <= 8;
  const homeLane = HOME_LANE_LOOKUP.get(key) || null;
  const startColor = START_CELLS[key] || null;
  const arrowColor = ARROW_CELLS[key] || null;
  const isPath = BOARD_PATH_SET.has(key);
  const isStar = STAR_CELLS.has(key);

  return {
    key,
    isCenter,
    isPath,
    isStar,
    homeLane,
    startColor,
    arrowColor,
  };
}

export { YARD_POSITIONS, HOME_LANES, START_CELLS, ARROW_CELLS, COLOR_HEX };
