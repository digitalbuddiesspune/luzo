const { MongoClient } = require("mongodb");
const { config } = require("./env");

const client = new MongoClient(config.mongodbUri);
let database;

async function connectDatabase() {
  if (database) {
    return database;
  }

  await client.connect();
  database = client.db(config.mongodbDatabase);
  await database.command({ ping: 1 });
  return database;
}

function getDatabase() {
  if (!database) {
    throw new Error("MongoDB has not been connected.");
  }

  return database;
}

async function pingDatabase() {
  await getDatabase().command({ ping: 1 });
}

async function closeDatabase() {
  database = undefined;
  await client.close();
}

module.exports = {
  closeDatabase,
  connectDatabase,
  getDatabase,
  pingDatabase,
};
