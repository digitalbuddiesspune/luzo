const { config } = require("../config/env");

const SETTINGS_ID = "global";
const COLLECTION = "platform_settings";

const DEFAULT_GAME_LEVELS = [
  { id: "level_1", name: "LEVEL 1", entryFee: 100, playerModes: [2, 4], enabled: true, sortOrder: 1 },
  { id: "level_2", name: "LEVEL 2", entryFee: 200, playerModes: [2, 4], enabled: true, sortOrder: 2 },
  { id: "level_3", name: "LEVEL 3", entryFee: 500, playerModes: [2, 4], enabled: true, sortOrder: 3 },
  { id: "level_4", name: "LEVEL 4", entryFee: 1000, playerModes: [2, 4], enabled: true, sortOrder: 4 },
  { id: "level_5", name: "LEVEL 5", entryFee: 2000, playerModes: [2, 4], enabled: true, sortOrder: 5 },
  { id: "level_6", name: "LEVEL 6", entryFee: 3000, playerModes: [2, 4], enabled: true, sortOrder: 6 },
  { id: "level_7", name: "LEVEL 7", entryFee: 5000, playerModes: [2, 4], enabled: true, sortOrder: 7 },
];

function normalizeFee(value, fallback = config.platformFeePerPlayer) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    return fallback;
  }
  return parsed;
}

function normalizePlayerModes(modes) {
  if (!Array.isArray(modes)) {
    return [2, 4];
  }
  const unique = [...new Set(modes.map((m) => Number(m)).filter((m) => m === 2 || m === 4))].sort(
    (a, b) => a - b,
  );
  return unique.length > 0 ? unique : [2, 4];
}

function cloneDefaultGameLevels() {
  return DEFAULT_GAME_LEVELS.map((level) => ({
    ...level,
    playerModes: [...level.playerModes],
  }));
}

function normalizeStoredLevels(levels) {
  if (!Array.isArray(levels) || levels.length === 0) {
    return cloneDefaultGameLevels();
  }

  return levels
    .map((level, index) => {
      const entryFee = Number(level?.entryFee);
      const safeEntryFee = Number.isInteger(entryFee) && entryFee > 0 ? entryFee : 100;
      const name = String(level?.name || `LEVEL ${index + 1}`).trim() || `LEVEL ${index + 1}`;
      const id = String(level?.id || `level_${index + 1}`).trim() || `level_${index + 1}`;
      const sortOrder = Number.isInteger(Number(level?.sortOrder))
        ? Number(level.sortOrder)
        : index + 1;
      return {
        id,
        name,
        entryFee: safeEntryFee,
        playerModes: normalizePlayerModes(level?.playerModes),
        enabled: level?.enabled !== false,
        sortOrder,
      };
    })
    .sort((a, b) => a.sortOrder - b.sortOrder);
}

function validateGameLevels(levels) {
  if (!Array.isArray(levels) || levels.length === 0) {
    const error = new Error("At least one game level is required.");
    error.status = 400;
    error.code = "INVALID_GAME_LEVELS";
    throw error;
  }

  if (levels.length > 50) {
    const error = new Error("Maximum 50 game levels are allowed.");
    error.status = 400;
    error.code = "INVALID_GAME_LEVELS";
    throw error;
  }

  const seenIds = new Set();
  return levels.map((raw, index) => {
    const name = String(raw?.name ?? "").trim();
    if (!name || name.length > 48) {
      const error = new Error(`Level #${index + 1} must have a name between 1 and 48 characters.`);
      error.status = 400;
      error.code = "INVALID_GAME_LEVELS";
      throw error;
    }

    const entryFee = Number(raw?.entryFee);
    if (!Number.isInteger(entryFee) || entryFee < 1 || entryFee > 1_000_000) {
      const error = new Error(
        `Level "${name}" entryFee must be an integer between 1 and 1000000.`,
      );
      error.status = 400;
      error.code = "INVALID_GAME_LEVELS";
      throw error;
    }

    const rawModes = Array.isArray(raw?.playerModes) ? raw.playerModes : [2, 4];
    const playerModes = [
      ...new Set(rawModes.map((m) => Number(m)).filter((m) => m === 2 || m === 4)),
    ].sort((a, b) => a - b);
    if (playerModes.length === 0) {
      const error = new Error(`Level "${name}" must enable 2-player, 4-player, or both.`);
      error.status = 400;
      error.code = "INVALID_GAME_LEVELS";
      throw error;
    }

    let id = String(raw?.id ?? "")
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9_-]+/g, "_")
      .replace(/^_+|_+$/g, "");
    if (!id) {
      id = `level_${index + 1}`;
    }
    let uniqueId = id;
    let suffix = 2;
    while (seenIds.has(uniqueId)) {
      uniqueId = `${id}_${suffix}`;
      suffix += 1;
    }
    seenIds.add(uniqueId);

    return {
      id: uniqueId,
      name,
      entryFee,
      playerModes,
      enabled: raw?.enabled !== false,
      sortOrder: index + 1,
    };
  });
}

function toSettingsResponse(doc) {
  return {
    platformFeePerPlayer: normalizeFee(doc.platformFeePerPlayer),
    gameLevels: normalizeStoredLevels(doc.gameLevels),
    updatedAt: doc.updatedAt || null,
  };
}

class PlatformSettingsService {
  constructor(database) {
    this.collection = database.collection(COLLECTION);
  }

  async getSettings() {
    const existing = await this.collection.findOne({ _id: SETTINGS_ID });
    if (existing) {
      return toSettingsResponse(existing);
    }

    const created = {
      _id: SETTINGS_ID,
      platformFeePerPlayer: config.platformFeePerPlayer,
      gameLevels: cloneDefaultGameLevels(),
      updatedAt: new Date(),
    };
    await this.collection.insertOne(created);
    return toSettingsResponse(created);
  }

  async updateSettings({ platformFeePerPlayer, gameLevels }) {
    const current = await this.getSettings();
    const nextFields = {};

    if (platformFeePerPlayer !== undefined) {
      const fee = Number(platformFeePerPlayer);
      if (!Number.isInteger(fee) || fee < 0 || fee > 1_000_000) {
        const error = new Error("platformFeePerPlayer must be an integer between 0 and 1000000.");
        error.status = 400;
        error.code = "INVALID_PLATFORM_FEE";
        throw error;
      }
      nextFields.platformFeePerPlayer = fee;
    } else {
      nextFields.platformFeePerPlayer = current.platformFeePerPlayer;
    }

    if (gameLevels !== undefined) {
      nextFields.gameLevels = validateGameLevels(gameLevels);
    } else {
      nextFields.gameLevels = current.gameLevels;
    }

    const updatedAt = new Date();
    nextFields.updatedAt = updatedAt;

    await this.collection.updateOne(
      { _id: SETTINGS_ID },
      {
        $set: nextFields,
        $setOnInsert: {
          _id: SETTINGS_ID,
        },
      },
      { upsert: true },
    );

    return {
      platformFeePerPlayer: nextFields.platformFeePerPlayer,
      gameLevels: nextFields.gameLevels,
      updatedAt,
    };
  }
}

module.exports = {
  PlatformSettingsService,
  normalizeFee,
  DEFAULT_GAME_LEVELS,
  SETTINGS_ID,
};
