const express = require("express");
const { createProfitLossController } = require("./controllers/profitLossController");
const { createRoundsController } = require("./controllers/roundsController");
const { errorHandler, notFoundHandler } = require("./middleware/errorHandler");
const { healthRouter } = require("./routes/healthRoutes");
const { createProfitLossRouter } = require("./routes/profitLossRoutes");
const { createRoundsRouter } = require("./routes/roundsRoutes");
const { ProfitLossService } = require("./services/profitLossService");
const { RoundsService } = require("./services/roundsService");

function createApp(database) {
  const app = express();
  const roundsService = new RoundsService(database);
  const profitLossService = new ProfitLossService(database);
  const roundsController = createRoundsController(roundsService);
  const profitLossController = createProfitLossController(profitLossService);

  app.disable("x-powered-by");
  app.use(express.json({ limit: "32kb" }));

  app.use("/health", healthRouter);
  app.use("/api/v1/rounds", createRoundsRouter(roundsController));
  app.use("/api/v1/admin/profit-loss", createProfitLossRouter(profitLossController));

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

module.exports = { createApp };
