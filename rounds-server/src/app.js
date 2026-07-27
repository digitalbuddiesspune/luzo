const express = require("express");
const { createPlatformSettingsController } = require("./controllers/platformSettingsController");
const { createProfitLossController } = require("./controllers/profitLossController");
const { createRoundsController } = require("./controllers/roundsController");
const { errorHandler, notFoundHandler } = require("./middleware/errorHandler");
const { healthRouter } = require("./routes/healthRoutes");
const { createPlatformSettingsRouter } = require("./routes/platformSettingsRoutes");
const { createProfitLossRouter } = require("./routes/profitLossRoutes");
const { createRoundsRouter } = require("./routes/roundsRoutes");
const { PlatformSettingsService } = require("./services/platformSettingsService");
const { ProfitLossService } = require("./services/profitLossService");
const { RoundsService } = require("./services/roundsService");

function createApp(database) {
  const app = express();
  const platformSettingsService = new PlatformSettingsService(database);
  const roundsService = new RoundsService(database, platformSettingsService);
  const profitLossService = new ProfitLossService(database, platformSettingsService);
  const roundsController = createRoundsController(roundsService);
  const profitLossController = createProfitLossController(profitLossService);
  const platformSettingsController = createPlatformSettingsController(platformSettingsService);

  app.disable("x-powered-by");
  app.use(express.json({ limit: "32kb" }));

  app.use("/health", healthRouter);
  app.use("/api/v1/rounds", createRoundsRouter(roundsController));
  app.use("/api/v1/admin/profit-loss", createProfitLossRouter(profitLossController));
  app.use("/api/v1/admin/settings", createPlatformSettingsRouter(platformSettingsController));

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

module.exports = { createApp };
