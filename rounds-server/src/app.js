const express = require("express");
const { config } = require("./config/env");
const {
  createAdminAuthController,
  createRequireAdminAuth,
} = require("./controllers/adminAuthController");
const { createPlatformSettingsController } = require("./controllers/platformSettingsController");
const { createProfitLossController } = require("./controllers/profitLossController");
const { createRoundsController } = require("./controllers/roundsController");
const { createCorsMiddleware } = require("./middleware/cors");
const { errorHandler, notFoundHandler } = require("./middleware/errorHandler");
const { healthRouter } = require("./routes/healthRoutes");
const { createAdminAuthRouter } = require("./routes/adminAuthRoutes");
const { createPlatformSettingsRouter } = require("./routes/platformSettingsRoutes");
const { createProfitLossRouter } = require("./routes/profitLossRoutes");
const { createRoundsRouter } = require("./routes/roundsRoutes");
const { AdminAuthService } = require("./services/adminAuthService");
const { PlatformSettingsService } = require("./services/platformSettingsService");
const { ProfitLossService } = require("./services/profitLossService");
const { RoundsService } = require("./services/roundsService");

function createApp(database) {
  const app = express();
  const adminAuthService = new AdminAuthService(database);
  const platformSettingsService = new PlatformSettingsService(database);
  const roundsService = new RoundsService(database, platformSettingsService);
  const profitLossService = new ProfitLossService(database, platformSettingsService);
  const roundsController = createRoundsController(roundsService);
  const profitLossController = createProfitLossController(profitLossService);
  const platformSettingsController = createPlatformSettingsController(platformSettingsService);
  const adminAuthController = createAdminAuthController(adminAuthService);
  const requireAdminAuth = createRequireAdminAuth(adminAuthService);

  app.disable("x-powered-by");
  app.use(createCorsMiddleware(config.corsOrigins));
  app.use(express.json({ limit: "32kb" }));

  app.use("/health", healthRouter);
  app.use("/api/v1/rounds", createRoundsRouter(roundsController));
  app.use("/api/v1/admin/auth", createAdminAuthRouter(adminAuthController, requireAdminAuth));
  app.use(
    "/api/v1/admin/profit-loss",
    requireAdminAuth,
    createProfitLossRouter(profitLossController),
  );
  app.use(
    "/api/v1/admin/settings",
    requireAdminAuth,
    createPlatformSettingsRouter(platformSettingsController),
  );

  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

async function prepareAdminAuth(database) {
  const adminAuthService = new AdminAuthService(database);
  await adminAuthService.ensureIndexes();
  const seedResult = await adminAuthService.seedDefaultAdmin();
  if (seedResult.seeded) {
    console.log(`Seeded default admin account ${seedResult.email} into admin_accounts.`);
  }
  return adminAuthService;
}

module.exports = { createApp, prepareAdminAuth };
