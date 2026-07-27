const { config } = require("../config/env");

const SETTINGS_ID = "global";
const COLLECTION = "platform_settings";

function normalizeFee(value, fallback = config.platformFeePerPlayer) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    return fallback;
  }
  return parsed;
}

class PlatformSettingsService {
  constructor(database) {
    this.collection = database.collection(COLLECTION);
  }

  async getSettings() {
    const existing = await this.collection.findOne({ _id: SETTINGS_ID });
    if (existing) {
      return {
        platformFeePerPlayer: normalizeFee(existing.platformFeePerPlayer),
        updatedAt: existing.updatedAt || null,
      };
    }

    const created = {
      _id: SETTINGS_ID,
      platformFeePerPlayer: config.platformFeePerPlayer,
      updatedAt: new Date(),
    };
    await this.collection.insertOne(created);
    return {
      platformFeePerPlayer: created.platformFeePerPlayer,
      updatedAt: created.updatedAt,
    };
  }

  async updateSettings({ platformFeePerPlayer }) {
    const fee = Number(platformFeePerPlayer);
    if (!Number.isInteger(fee) || fee < 0 || fee > 1_000_000) {
      const error = new Error("platformFeePerPlayer must be an integer between 0 and 1000000.");
      error.status = 400;
      error.code = "INVALID_PLATFORM_FEE";
      throw error;
    }

    const updatedAt = new Date();
    await this.collection.updateOne(
      { _id: SETTINGS_ID },
      {
        $set: {
          platformFeePerPlayer: fee,
          updatedAt,
        },
        $setOnInsert: {
          _id: SETTINGS_ID,
        },
      },
      { upsert: true },
    );

    return {
      platformFeePerPlayer: fee,
      updatedAt,
    };
  }
}

module.exports = {
  PlatformSettingsService,
  normalizeFee,
  SETTINGS_ID,
};
