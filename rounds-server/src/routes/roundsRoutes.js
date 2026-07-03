const express = require("express");

function createRoundsRouter(roundsController) {
  const router = express.Router();

  router.get("/ludo/single", roundsController.getSingleLudoRoundHtml);
  router.get("/ludo", roundsController.listLudoRounds);

  return router;
}

module.exports = { createRoundsRouter };
