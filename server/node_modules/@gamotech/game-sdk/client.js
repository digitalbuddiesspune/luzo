const DEFAULT_API_BASE = "https://api.dpbossking.com/api/v1";

export class ProviderSDKError extends Error {
  constructor(message, { status, code, data } = {}) {
    super(message);
    this.name = "ProviderSDKError";
    this.status = status ?? null;
    this.code = code ?? null;
    this.data = data ?? null;
  }
}

/**
 * Browser/client SDK — session validate + lifecycle events only.
 * Wallet operations must use ProviderGameServerSDK on your game server.
 */
export class ProviderGameSDK {
  #apiBase;
  #sessionToken = null;
  #session = null;

  constructor(options = {}) {
    this.#apiBase = (options.apiBaseUrl ?? DEFAULT_API_BASE).replace(/\/+$/, "");
  }

  get sessionToken() {
    return this.#sessionToken;
  }

  get session() {
    return this.#session;
  }

  get operatorId() {
    return this.#session?.operatorId ?? null;
  }

  get playerId() {
    return this.#session?.playerId ?? null;
  }

  get playerUsername() {
    return this.#session?.playerUsername ?? null;
  }

  get gameCode() {
    return this.#session?.gameCode ?? null;
  }

  get currency() {
    return this.#session?.currency ?? null;
  }

  async initFromUrl(search = typeof window !== "undefined" ? window.location.search : "") {
    const sessionToken = new URLSearchParams(search).get("sessionToken");
    if (!sessionToken) {
      throw new ProviderSDKError("Missing sessionToken in URL");
    }
    return this.init(sessionToken);
  }

  async init(sessionToken) {
    this.#sessionToken = sessionToken;
    this.#session = await this.#validateSession(sessionToken);
    return this.#session;
  }

  async createTable({ tableId, ...payload } = {}) {
    const id = tableId ?? `table_${Date.now()}`;
    await this.#sendEvent("TABLE_CREATED", { tableId: id, payload });
    return id;
  }

  async createRound({ roundId, ...payload } = {}) {
    const id = roundId ?? `round_${Date.now()}`;
    await this.#sendEvent("ROUND_CREATED", { roundId: id, payload });
    return id;
  }

  async startRound({ roundId, ...payload }) {
    this.#requireFields({ roundId }, ["roundId"]);
    await this.#sendEvent("ROUND_STARTED", { roundId, payload });
    return roundId;
  }

  async endRound({ roundId, ...payload }) {
    this.#requireFields({ roundId }, ["roundId"]);
    await this.#sendEvent("ROUND_ENDED", { roundId, payload });
    return roundId;
  }

  async endSession(payload = { reason: "PLAYER_LEFT" }) {
    this.#requireSession();
    await this.#sendEvent("SESSION_ENDED", { payload });
    this.#session = { ...this.#session, status: "ENDED" };
  }

  async sendEvent(event, { tableId, roundId, payload } = {}) {
    return this.#sendEvent(event, { tableId, roundId, payload });
  }

  attachUnloadHandler() {
    if (typeof window === "undefined") return;

    window.addEventListener("beforeunload", () => {
      if (!this.#sessionToken || this.#session?.status === "ENDED") return;

      const body = JSON.stringify({
        sessionToken: this.#sessionToken,
        event: "SESSION_ENDED",
        payload: { reason: "PLAYER_LEFT" },
      });

      navigator.sendBeacon(`${this.#apiBase}/sessions/events`, body);
    });
  }

  async #validateSession(sessionToken) {
    const data = await this.#request("/sessions/validate", {
      method: "POST",
      body: { sessionToken },
    });
    return data.session;
  }

  async #sendEvent(event, { tableId, roundId, payload } = {}) {
    this.#requireSession();

    const data = await this.#request("/sessions/events", {
      method: "POST",
      body: {
        sessionToken: this.#sessionToken,
        event,
        tableId,
        roundId,
        payload,
      },
    });

    if (data.session) {
      this.#session = data.session;
    }

    return data;
  }

  async #request(path, { method = "GET", body } = {}) {
    const url = `${this.#apiBase}${path.startsWith("/") ? path : `/${path}`}`;

    const options = {
      method,
      headers: { "Content-Type": "application/json" },
    };

    if (body && method !== "GET") {
      options.body = JSON.stringify(body);
    }

    let response;
    try {
      response = await fetch(url, options);
    } catch {
      throw new ProviderSDKError("Network error — unable to reach Provider API");
    }

    let data;
    try {
      data = await response.json();
    } catch {
      throw new ProviderSDKError("Invalid JSON response from Provider API", {
        status: response.status,
      });
    }

    if (!response.ok || data.success === false) {
      throw new ProviderSDKError(data.message || "Provider API request failed", {
        status: response.status,
        data,
      });
    }

    return data;
  }

  #requireSession() {
    if (!this.#sessionToken || !this.#session) {
      throw new ProviderSDKError("SDK not initialized — call init() or initFromUrl() first");
    }
    if (this.#session.status === "ENDED") {
      throw new ProviderSDKError("Session has ended");
    }
  }

  #requireFields(obj, fields) {
    for (const field of fields) {
      if (obj[field] === undefined || obj[field] === null || obj[field] === "") {
        throw new ProviderSDKError(`${field} is required`);
      }
    }
  }
}

export default ProviderGameSDK;
