const express = require("express");

function createProfitLossRouter(profitLossController) {
  const router = express.Router();

  router.get("/summary", profitLossController.getSummary);
  router.get("/games", profitLossController.listGames);
  router.delete("/games/:roundId", profitLossController.deleteGame);
  router.get("/users", profitLossController.listUsers);

  return router;
}

module.exports = { createProfitLossRouter };
