const path = require("node:path");
const dotenv = require("dotenv");

dotenv.config({ path: path.resolve(__dirname, "../../.env") });

function integerFromEnv(name, defaultValue, { min, max }) {
  const rawValue = process.env[name];
  const value = rawValue === undefined || rawValue === ""
    ? defaultValue
    : Number(rawValue);

  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}.`);
  }

  return value;
}

const mongodbUri = process.env.MONGODB_URI?.trim();

if (!mongodbUri) {
  throw new Error("MONGODB_URI is required.");
}

const config = Object.freeze({
  port: integerFromEnv("PORT", 8083, { min: 1, max: 65_535 }),
  mongodbUri,
  mongodbDatabase: process.env.MONGODB_DATABASE?.trim() || "Ludo",
  walletCurrency: process.env.APP_WALLET_CURRENCY?.trim().toUpperCase() || "INR",
  payoutRakeBasisPoints: integerFromEnv(
    "APP_WALLET_PAYOUT_RAKE_BASIS_POINTS",
    0,
    { min: 0, max: 10_000 },
  ),
});

module.exports = { config };
