const { HttpError } = require("../errors/httpError");

function extractBearerToken(request) {
  const header = request.headers.authorization || "";
  if (header.toLowerCase().startsWith("bearer ")) {
    return header.slice(7).trim();
  }
  return null;
}

function createRequireAdminAuth(adminAuthService) {
  return async function requireAdminAuth(request, response, next) {
    try {
      const token = extractBearerToken(request);
      const session = await adminAuthService.requireSession(token);
      request.admin = session.admin;
      request.adminToken = session.token;
      next();
    } catch (error) {
      next(error);
    }
  };
}

function createAdminAuthController(adminAuthService) {
  return {
    async login(request, response, next) {
      try {
        const result = await adminAuthService.login({
          email: request.body?.email,
          password: request.body?.password,
        });
        response.json(result);
      } catch (error) {
        next(error);
      }
    },

    async logout(request, response, next) {
      try {
        const token = extractBearerToken(request);
        await adminAuthService.logout(token);
        response.json({ ok: true });
      } catch (error) {
        next(error);
      }
    },

    async me(request, response, next) {
      try {
        const token = extractBearerToken(request);
        const session = await adminAuthService.resolveSession(token);
        if (!session) {
          throw new HttpError(401, "UNAUTHORIZED", "Admin login required.");
        }
        response.json({
          admin: session.admin,
          expiresAt: session.expiresAt,
        });
      } catch (error) {
        next(error);
      }
    },
  };
}

module.exports = {
  createAdminAuthController,
  createRequireAdminAuth,
  extractBearerToken,
};
