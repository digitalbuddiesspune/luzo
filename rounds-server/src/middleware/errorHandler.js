const { HttpError } = require("../errors/httpError");

function notFoundHandler(request, response, next) {
  next(new HttpError(404, "NOT_FOUND", "Route not found."));
}

function errorHandler(error, request, response, next) {
  if (response.headersSent) {
    next(error);
    return;
  }

  const isHttpError = error instanceof HttpError;
  const status = isHttpError ? error.status : 500;
  const message = isHttpError ? error.message : "An unexpected error occurred.";

  if (!isHttpError) {
    console.error(error);
  }

  if (request.path === "/api/v1/rounds/ludo/single") {
    const { renderErrorHtml } = require("../services/roundsService");
    response.status(status).type("html").send(renderErrorHtml(status, message));
    return;
  }

  const body = {
    error: {
      code: isHttpError ? error.code : "INTERNAL_SERVER_ERROR",
      message,
    },
  };

  if (isHttpError && error.details !== undefined) {
    body.error.details = error.details;
  }

  response.status(status).json(body);
}

module.exports = { errorHandler, notFoundHandler };
