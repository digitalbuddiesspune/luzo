const { pingDatabase } = require("../config/database");

async function getHealth(request, response) {
  await pingDatabase();

  response.json({
    status: "ok",
    service: "ludo-rounds-server",
    database: "connected",
    timestamp: new Date().toISOString(),
  });
}

module.exports = { getHealth };
