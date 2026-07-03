const { createServer } = require("node:http");
const { createApp } = require("./app");
const { config } = require("./config/env");
const {
  closeDatabase,
  connectDatabase,
} = require("./config/database");

async function startServer() {
  const database = await connectDatabase();
  const server = createServer(createApp(database));

  server.listen(config.port, () => {
    console.log(`Ludo rounds API listening on port ${config.port}.`);
  });

  async function shutdown(signal) {
    console.log(`${signal} received. Shutting down.`);

    server.close(async (error) => {
      try {
        await closeDatabase();
      } finally {
        process.exit(error ? 1 : 0);
      }
    });
  }

  process.once("SIGINT", () => shutdown("SIGINT"));
  process.once("SIGTERM", () => shutdown("SIGTERM"));
}

startServer().catch((error) => {
  console.error("Failed to start Ludo rounds API.", error);
  process.exit(1);
});
