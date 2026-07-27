const express = require("express");

function createPlatformSettingsRouter(platformSettingsController) {
  const router = express.Router();

  router.get("/", platformSettingsController.getSettings);
  router.put("/", platformSettingsController.updateSettings);

  return router;
}

module.exports = { createPlatformSettingsRouter };
