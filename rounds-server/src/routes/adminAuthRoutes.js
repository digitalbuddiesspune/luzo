const express = require("express");

function createAdminAuthRouter(adminAuthController, requireAdminAuth) {
  const router = express.Router();

  router.post("/login", adminAuthController.login);
  router.post("/logout", adminAuthController.logout);
  router.get("/me", requireAdminAuth, adminAuthController.me);

  return router;
}

module.exports = { createAdminAuthRouter };
