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
  HOME: "All tokens home",
  FORFEIT: "Opponent left (forfeit)",
  ABANDON_BOT: "Real players left — bot won",
  HOUSE: "Real players left — platform kept pot",
};

export function winnerReasonLabel(reason) {
  if (!reason) return null;
  return WINNER_REASON_LABELS[reason] || reason;
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
