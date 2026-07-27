const { HttpError } = require("../errors/httpError");

function createPlatformSettingsController(platformSettingsService) {
  return {
    async getSettings(_req, res, next) {
      try {
        const settings = await platformSettingsService.getSettings();
        res.json(settings);
      } catch (error) {
        next(error);
      }
    },

    async updateSettings(req, res, next) {
      try {
        const settings = await platformSettingsService.updateSettings({
          platformFeePerPlayer: req.body?.platformFeePerPlayer,
        });
        res.json(settings);
      } catch (error) {
        if (error?.code === "INVALID_PLATFORM_FEE") {
          next(new HttpError(400, error.code, error.message));
          return;
        }
        next(error);
      }
    },
  };
}

module.exports = { createPlatformSettingsController };
