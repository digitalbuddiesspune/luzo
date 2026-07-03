const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ??
  "http://127.0.0.1:8082";
const OPERATOR_PLATFORM_ENABLED = ["1", "true", "enabled", "yes"].includes(
  (process.env.NEXT_PUBLIC_OPERATOR_PLATFORM_ENABLED ?? "").toLowerCase(),
);
const CORS_DEBUG_ENABLED = ["1", "true", "enabled", "yes"].includes(
  (process.env.NEXT_PUBLIC_CORS_DEBUG ?? "true").toLowerCase(),
);

const SESSION_STORAGE_KEY = "ludo.guest-session";
export const OPERATOR_PLATFORM_ACCESS_MESSAGE = "This page is not accessible.";

const WALLET_TRANSACTION_SIGNS = {
  GUEST_STARTING_BALANCE: 1,
  ADMIN_CREDIT: 1,
  ROOM_RESERVATION: -1,
  ROOM_REFUND: 1,
  MATCH_PAYOUT: 1,
  HOUSE_RAKE: 1,
};

const WALLET_HISTORY_OUTCOMES = {
  GUEST_STARTING_BALANCE: "Credit",
  ADMIN_CREDIT: "Credit",
  ROOM_RESERVATION: "Entry Fee",
  ROOM_REFUND: "Refund",
  MATCH_PAYOUT: "Won",
  HOUSE_RAKE: "Rake",
};

function buildHeaders(sessionToken, hasBody = false) {
  return {
    ...(sessionToken ? { "X-Session-Token": sessionToken } : {}),
    ...(hasBody ? { "Content-Type": "application/json" } : {}),
  };
}

function logFrontendEnv() {
  if (!CORS_DEBUG_ENABLED) {
    return;
  }

  const runtime = typeof window === "undefined" ? "server" : "browser";
  const frontendOrigin =
    typeof window === "undefined" ? "(server-side render)" : window.location.origin;

  console.info("[Ludo env diagnostics]", {
    runtime,
    frontendOrigin,
    apiBaseUrl: API_BASE_URL,
    operatorPlatformEnabled: OPERATOR_PLATFORM_ENABLED,
    nextPublicApiBaseUrl: process.env.NEXT_PUBLIC_API_BASE_URL ?? "(not set)",
    nextPublicCorsDebug: process.env.NEXT_PUBLIC_CORS_DEBUG ?? "(not set)",
  });
}

function logCorsRequest(path, method) {
  if (!CORS_DEBUG_ENABLED || typeof window === "undefined") {
    return;
  }

  console.info("[Ludo CORS diagnostics] request", {
    method,
    path,
    frontendOrigin: window.location.origin,
    apiBaseUrl: API_BASE_URL,
    crossOrigin: new URL(API_BASE_URL, window.location.href).origin !== window.location.origin,
  });
}

function logCorsResponse(path, method, response) {
  if (!CORS_DEBUG_ENABLED || typeof window === "undefined") {
    return;
  }

  console.info("[Ludo CORS diagnostics] response", {
    method,
    path,
    status: response.status,
    ok: response.ok,
    type: response.type,
    url: response.url,
  });
}

function logCorsFailure(path, method, error) {
  if (!CORS_DEBUG_ENABLED || typeof window === "undefined") {
    return;
  }

  console.error("[Ludo CORS diagnostics] request failed", {
    method,
    path,
    frontendOrigin: window.location.origin,
    apiBaseUrl: API_BASE_URL,
    likelyCorsOrNetworkBlock: error instanceof TypeError,
    message: error?.message,
  });
}

logFrontendEnv();

class ApiRequestError extends Error {
  constructor(message, details) {
    super(message);
    this.name = "ApiRequestError";
    this.details = details;
  }
}

async function request(path, { method = "GET", sessionToken, body } = {}) {
  logCorsRequest(path, method);

  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers: buildHeaders(sessionToken, Boolean(body)),
      body: body ? JSON.stringify(body) : undefined,
      cache: "no-store",
    });

    logCorsResponse(path, method, response);
    return response;
  } catch (error) {
    logCorsFailure(path, method, error);
    throw error;
  }
}

async function requestJson(path, { method = "GET", sessionToken, body } = {}) {
  const response = await request(path, {
    method,
    sessionToken,
    body,
  });

  if (!response.ok) {
    let message = `Request failed with status ${response.status}.`;
    let errorBody = null;

    try {
      errorBody = await response.json();
      message = errorBody.message ?? message;
    } catch {}

    const details = {
      method,
      path,
      status: response.status,
      statusText: response.statusText,
      message,
      error: errorBody,
    };

    if (typeof window !== "undefined") {
      console.error("[Ludo API error]", details);
    }

    throw new ApiRequestError(message, details);
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

function readStoredSession() {
  if (typeof window === "undefined") {
    return null;
  }

  try {
    const raw = window.localStorage.getItem(SESSION_STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function storeSession(session) {
  if (typeof window === "undefined") {
    return;
  }

  window.localStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
}

function formatTransactionAge(createdAt) {
  const timestamp = createdAt ? new Date(createdAt).getTime() : Number.NaN;

  if (!Number.isFinite(timestamp)) {
    return "Just now";
  }

  const elapsedSeconds = Math.max(0, Math.floor((Date.now() - timestamp) / 1000));

  if (elapsedSeconds < 60) {
    return "Just now";
  }

  const elapsedMinutes = Math.floor(elapsedSeconds / 60);
  if (elapsedMinutes < 60) {
    return `${elapsedMinutes} min ago`;
  }

  const elapsedHours = Math.floor(elapsedMinutes / 60);
  if (elapsedHours < 24) {
    return `${elapsedHours} hr ago`;
  }

  const elapsedDays = Math.floor(elapsedHours / 24);
  if (elapsedDays < 7) {
    return `${elapsedDays} day${elapsedDays === 1 ? "" : "s"} ago`;
  }

  return new Intl.DateTimeFormat("en-IN", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(timestamp));
}

function normalizeWalletTransaction(transaction) {
  const signedAmount =
    transaction.amount * (WALLET_TRANSACTION_SIGNS[transaction.type] ?? 1);

  return {
    id: transaction.id,
    label: transaction.description,
    meta: transaction.referenceId,
    amount: signedAmount,
    type: transaction.type,
    createdAt: transaction.createdAt,
  };
}

function normalizeWalletHistoryItem(transaction) {
  const normalizedTransaction = normalizeWalletTransaction(transaction);

  return {
    id: normalizedTransaction.id,
    room: normalizedTransaction.label,
    outcome: WALLET_HISTORY_OUTCOMES[normalizedTransaction.type] ?? "Wallet",
    delta: normalizedTransaction.amount,
    when: formatTransactionAge(normalizedTransaction.createdAt),
  };
}

function readOperatorLaunchParams() {
  if (typeof window === "undefined") {
    return null;
  }

  const params = new URLSearchParams(window.location.search);
  const id = params.get("id")?.trim();
  const rawGameId = params.get("game_id") ?? params.get("gameId");
  const gameId = Number(rawGameId ?? 2);

  if (!id) {
    return null;
  }

  return {
    id,
    gameId: Number.isFinite(gameId) && gameId > 0 ? gameId : 2,
  };
}

export function isOperatorPlatformEnabled() {
  return OPERATOR_PLATFORM_ENABLED;
}

function normalizeSession(sessionResponse, extra = {}) {
  return {
    userId: sessionResponse.userId,
    sessionToken: sessionResponse.sessionToken,
    displayName: sessionResponse.displayName,
    expiresAt: sessionResponse.expiresAt,
    isOperatorSession: Boolean(sessionResponse.isOperatorSession),
    ...extra,
  };
}

export async function ensureGuestSession(defaultDisplayName) {
  const operatorLaunch = readOperatorLaunchParams();
  if (OPERATOR_PLATFORM_ENABLED && !operatorLaunch) {
    throw new Error(OPERATOR_PLATFORM_ACCESS_MESSAGE);
  }

  if (operatorLaunch) {
    let operatorSession;
    try {
      operatorSession = await requestJson("/api/v1/identity/operator/session", {
        method: "POST",
        body: operatorLaunch,
      });
    } catch (error) {
      if (OPERATOR_PLATFORM_ENABLED) {
        throw new Error(OPERATOR_PLATFORM_ACCESS_MESSAGE);
      }
      throw error;
    }

    const normalizedSession = normalizeSession(operatorSession, {
      operatorGameId: operatorLaunch.gameId,
    });

    storeSession(normalizedSession);
    return normalizedSession;
  }

  const storedSession = readStoredSession();

  if (storedSession?.sessionToken) {
    try {
      const currentSession = await requestJson("/api/v1/identity/me", {
        sessionToken: storedSession.sessionToken,
      });

      const refreshedSession = normalizeSession(currentSession, {
        operatorGameId: storedSession.operatorGameId,
      });

      storeSession(refreshedSession);
      return refreshedSession;
    } catch {}
  }

  const createdSession = await requestJson("/api/v1/identity/guest", {
    method: "POST",
    body: {
      displayName: defaultDisplayName,
    },
  });

  const normalizedSession = normalizeSession(createdSession);

  storeSession(normalizedSession);
  return normalizedSession;
}

export function normalizeWalletResponse(walletResponse) {
  const transactions = walletResponse.transactions.map(normalizeWalletTransaction);

  return {
    currency: walletResponse.currency,
    availableBalance: walletResponse.availableBalance,
    reservedBalance: walletResponse.reservedBalance,
    totalWinnings: transactions
      .filter((transaction) => transaction.type === "MATCH_PAYOUT")
      .reduce((sum, transaction) => sum + transaction.amount, 0),
    winRate: 0,
    transactions,
    history: walletResponse.transactions.map(normalizeWalletHistoryItem),
  };
}

export function normalizeMatchSnapshot(snapshot) {
  return {
    id: snapshot.matchId,
    roomId: snapshot.roomId,
    roomTitle: snapshot.roomCode,
    entryFee: snapshot.entryFee,
    pot: snapshot.potAmount,
    currentTurn: snapshot.currentTurnDisplayName,
    currentTurnUserId: snapshot.currentTurnUserId,
    currentPlayerIndex: snapshot.currentPlayerIndex,
    dice: snapshot.dice,
    lastRollUserId: snapshot.lastRollUserId,
    lastRollDisplayName: snapshot.lastRollDisplayName,
    lastRollDice: snapshot.lastRollDice,
    mode:
      snapshot.mode === "ONLINE_PUBLIC"
        ? "Online Match"
        : snapshot.mode === "PRIVATE_FRIENDS"
          ? "Private Room"
          : snapshot.mode,
    sequence: snapshot.sequence,
    phase: snapshot.phase?.toLowerCase() ?? "rolling",
    turnDeadlineAt: snapshot.turnDeadlineAt,
    turnTimeoutSeconds: snapshot.turnTimeoutSeconds,
    selectableTokenIndexes: snapshot.selectableTokenIndexes ?? [],
    winnerId: snapshot.winnerUserId,
    winnerDisplayName: snapshot.winnerDisplayName,
    players: snapshot.players.map((player) => {
      const isBot = player.isBot ?? player.bot ?? false;
      const tokens = player.tokens ?? [];
      const isAbandoned =
        player.isAbandoned ??
        player.abandoned ??
        (!isBot &&
          (player.userId?.startsWith("abandoned_") || tokens.length === 0));

      return {
        id: player.userId,
        name: player.displayName,
        color: player.color,
        isBot,
        isAbandoned,
        tokens,
      };
    }),
    events: snapshot.events.map((event) => ({
      id: event.id,
      actor: event.actor,
      detail: event.detail,
    })),
  };
}

export async function fetchWalletOverview(sessionToken) {
  return requestJson("/api/v1/wallet", {
    sessionToken,
  });
}

export function subscribeOperatorGatewayLogs(sessionToken, onEvent) {
  if (
    !sessionToken ||
    typeof window === "undefined" ||
    typeof window.EventSource === "undefined"
  ) {
    return () => {};
  }

  const params = new URLSearchParams({ sessionToken });
  const source = new window.EventSource(
    `${API_BASE_URL}/api/v1/operator-gateway/logs?${params.toString()}`,
  );

  console.info("[Ludo operator gateway log] connecting", {
    apiBaseUrl: API_BASE_URL,
  });

  source.onopen = () => {
    console.info("[Ludo operator gateway log] connected");
  };

  source.addEventListener("operator_gateway_connected", (event) => {
    try {
      console.info("[Ludo operator gateway log] connected session", JSON.parse(event.data));
    } catch {
      console.info("[Ludo operator gateway log] connected session");
    }
  });

  source.addEventListener("operator_gateway_log", (event) => {
    try {
      onEvent(JSON.parse(event.data));
    } catch (error) {
      console.error("[Ludo operator gateway log] invalid event", {
        message: error?.message,
        data: event.data,
      });
    }
  });

  source.onerror = () => {
    console.warn("[Ludo operator gateway log] stream interrupted", {
      readyState: source.readyState,
    });
  };

  return () => {
    source.close();
  };
}

export async function joinOnlineMatch(sessionToken, maxPlayers = 4) {
  return requestJson("/api/v1/lobby/online/join", {
    method: "POST",
    sessionToken,
    body: { maxPlayers },
  });
}

export async function leaveOnlineLobby(sessionToken) {
  return requestJson("/api/v1/lobby/online/leave", {
    method: "POST",
    sessionToken,
  });
}

export async function leaveOnlineRoom(sessionToken) {
  return leaveOnlineLobby(sessionToken);
}

export async function fetchPrivateRoomState(sessionToken) {
  const response = await request("/api/v1/lobby/private/current", {
    sessionToken,
  });

  if (response.status === 404) {
    return null;
  }

  if (!response.ok) {
    let message = `Request failed with status ${response.status}.`;

    try {
      const error = await response.json();
      message = error.message ?? message;
    } catch {}

    throw new Error(message);
  }

  return response.json();
}

export async function createPrivateRoom(sessionToken, payload) {
  return requestJson("/api/v1/lobby/private/create", {
    method: "POST",
    sessionToken,
    body: payload,
  });
}

export async function joinPrivateRoom(sessionToken, payload) {
  return requestJson("/api/v1/lobby/private/join", {
    method: "POST",
    sessionToken,
    body: payload,
  });
}

export async function transferPrivateRoomHost(sessionToken, targetUserId) {
  return requestJson("/api/v1/lobby/private/host", {
    method: "POST",
    sessionToken,
    body: {
      targetUserId,
    },
  });
}

export async function startPrivateRoom(sessionToken) {
  return requestJson("/api/v1/lobby/private/start", {
    method: "POST",
    sessionToken,
  });
}

export async function leavePrivateRoom(sessionToken) {
  return requestJson("/api/v1/lobby/private/leave", {
    method: "POST",
    sessionToken,
  });
}

export async function fetchMatchSnapshot(sessionToken, matchId) {
  return requestJson(`/api/v1/matches/${matchId}`, {
    sessionToken,
  });
}

export async function submitMatchMove(sessionToken, matchId, tokenIndex) {
  return requestJson(`/api/v1/matches/${matchId}/moves`, {
    method: "POST",
    sessionToken,
    body: {
      tokenIndex,
    },
  });
}

export async function rollMatchDice(sessionToken, matchId) {
  return requestJson(`/api/v1/matches/${matchId}/roll`, {
    method: "POST",
    sessionToken,
  });
}

export function toWebSocketUrl(path) {
  if (path.startsWith("ws://") || path.startsWith("wss://")) {
    return path;
  }

  const baseUrl = API_BASE_URL.replace(/^http:/i, "ws:").replace(/^https:/i, "wss:");
  return `${baseUrl}${path}`;
}
