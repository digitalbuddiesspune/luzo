const API_BASE = import.meta.env.VITE_API_BASE_URL || "/api/v1/admin/profit-loss";

async function request(path, params = {}) {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);

  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  }

  const response = await fetch(url);

  if (!response.ok) {
    const message = await response.text();
    throw new Error(message || `Request failed with status ${response.status}`);
  }

  return response.json();
}

function withFilters(params, playerCount, operatorId) {
  const next = { ...params };

  if (playerCount === 2 || playerCount === 4) {
    next.players = playerCount;
  }

  if (operatorId && operatorId !== "all") {
    next.operatorId = operatorId;
  }

  return next;
}

export function fetchSummary(playerCount = "all", operatorId = "all") {
  return request("/summary", withFilters({}, playerCount, operatorId));
}

export function fetchGames(page = 1, limit = 20, playerCount = "all", operatorId = "all") {
  return request("/games", withFilters({ page, limit }, playerCount, operatorId));
}

export function fetchUsers(page = 1, limit = 20, playerCount = "all", operatorId = "all") {
  return request("/users", withFilters({ page, limit }, playerCount, operatorId));
}
