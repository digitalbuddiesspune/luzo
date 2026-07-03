const express = require("express");
const { createRoundsController } = require("./controllers/roundsController");
const { errorHandler, notFoundHandler } = require("./middleware/errorHandler");
const { healthRouter } = require("./routes/healthRoutes");
const { createRoundsRouter } = require("./routes/roundsRoutes");
const { RoundsService } = require("./services/roundsService");

function createApp(database) {
  const app = express();
  const roundsService = new RoundsService(database);
  const roundsController = createRoundsController(roundsService);

  app.disable("x-powered-by");
  app.use(express.json({ limit: "32kb" }));

  app.use("/health", healthRouter);
  app.use("/api/v1/rounds", createRoundsRouter(roundsController));

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

module.exports = { createApp };
