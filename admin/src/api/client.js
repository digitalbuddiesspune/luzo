import { clearAdminToken, getAdminToken, setAdminToken } from "./authStorage";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "/api/v1/admin/profit-loss";
const SETTINGS_API_BASE = import.meta.env.VITE_SETTINGS_API_BASE_URL || "/api/v1/admin/settings";
const AUTH_API_BASE = import.meta.env.VITE_AUTH_API_BASE_URL || "/api/v1/admin/auth";

function authHeaders(extra = {}) {
  const token = getAdminToken();
  return {
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...extra,
  };
}

async function parseError(response) {
  let message = `Request failed with status ${response.status}`;
  try {
    const body = await response.json();
    message = body?.error?.message || message;
  } catch {
    // keep default
  }
  const error = new Error(message);
  error.status = response.status;
  return error;
}

async function request(path, params = {}, options = {}) {
  const url = new URL(`${API_BASE}${path}`, window.location.origin);

  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  }

  const response = await fetch(url, {
    ...options,
    headers: authHeaders(options.headers),
  });

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

async function settingsRequest(path = "", options = {}) {
  const url = new URL(`${SETTINGS_API_BASE}${path}`, window.location.origin);
  const response = await fetch(url, {
    ...options,
    headers: authHeaders({
      "Content-Type": "application/json",
      ...(options.headers || {}),
    }),
  });

  if (!response.ok) {
    throw await parseError(response);
  }

  return response.json();
}

async function authRequest(path, options = {}) {
  const url = new URL(`${AUTH_API_BASE}${path}`, window.location.origin);
  const response = await fetch(url, {
    ...options,
    headers: authHeaders({
      "Content-Type": "application/json",
      ...(options.headers || {}),
    }),
  });

  if (!response.ok) {
    throw await parseError(response);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

function withFilters(params, playerCount, operatorId, dateFrom, dateTo) {
  const next = { ...params };

  if (playerCount === 2 || playerCount === 4) {
    next.players = playerCount;
  }

  if (operatorId && operatorId !== "all") {
    next.operatorId = operatorId;
  }

  if (dateFrom) {
    next.dateFrom = dateFrom;
  }

  if (dateTo) {
    next.dateTo = dateTo;
  }

  return next;
}

export function fetchSummary(
  playerCount = "all",
  operatorId = "all",
  dateFrom = "",
  dateTo = "",
) {
  return request("/summary", withFilters({}, playerCount, operatorId, dateFrom, dateTo));
}

export function fetchGames(
  page = 1,
  limit = 20,
  playerCount = "all",
  operatorId = "all",
  dateFrom = "",
  dateTo = "",
) {
  return request("/games", withFilters({ page, limit }, playerCount, operatorId, dateFrom, dateTo));
}

export function deleteGame(roundId) {
  return request(`/games/${encodeURIComponent(roundId)}`, {}, { method: "DELETE" });
}

export function fetchUsers(
  page = 1,
  limit = 20,
  playerCount = "all",
  operatorId = "all",
  dateFrom = "",
  dateTo = "",
) {
  return request("/users", withFilters({ page, limit }, playerCount, operatorId, dateFrom, dateTo));
}

export function fetchPlatformSettings() {
  return settingsRequest("/");
}

export function updatePlatformSettings({ platformFeePerPlayer }) {
  return settingsRequest("/", {
    method: "PUT",
    body: JSON.stringify({ platformFeePerPlayer }),
  });
}

export async function loginAdmin({ email, password }) {
  const result = await authRequest("/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  setAdminToken(result.token);
  return result;
}

export async function logoutAdmin() {
  try {
    if (getAdminToken()) {
      await authRequest("/logout", { method: "POST" });
    }
  } finally {
    clearAdminToken();
  }
}

export async function fetchAdminSession() {
  if (!getAdminToken()) {
    return null;
  }

  try {
    return await authRequest("/me");
  } catch (error) {
    if (error.status === 401) {
      clearAdminToken();
      return null;
    }
    throw error;
  }
}
