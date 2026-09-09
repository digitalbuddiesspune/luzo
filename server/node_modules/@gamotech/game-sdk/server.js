import { ProviderSDKError } from "./client.js";

const DEFAULT_API_BASE = "https://api.dpbossking.com/api/v1";

/**
 * Optional Node server SDK — wallet operations (balance, debit, credit).
 * operatorId is resolved from sessionToken server-side — do not pass it.
 */
export class ProviderGameServerSDK {
  #apiBase;
  #gameServerKey;

  constructor(options = {}) {
    this.#apiBase = (options.apiBaseUrl ?? DEFAULT_API_BASE).replace(/\/+$/, "");
    this.#gameServerKey = options.gameServerKey ?? process.env.GAME_SERVER_API_KEY;

    if (!this.#gameServerKey) {
      throw new ProviderSDKError(
        "gameServerKey is required — set GAME_SERVER_API_KEY on your game server"
      );
    }
  }

  async getBalance({ sessionToken }) {
    this.#requireFields({ sessionToken }, ["sessionToken"]);
    return this.#walletCall("balance", { sessionToken });
  }

  async getPlayerProfile({ sessionToken }) {
    this.#requireFields({ sessionToken }, ["sessionToken"]);
    return this.#walletCall("player-profile", { sessionToken });
  }

  async debit({ sessionToken, amount, transactionId, roundId, tableId, ...extra }) {
    this.#requireFields(
      { sessionToken, amount, transactionId },
      ["sessionToken", "amount", "transactionId"]
    );

    return this.#walletCall("debit", {
      sessionToken,
      amount,
      transactionId,
      roundId,
      tableId,
      ...extra,
    });
  }

  async credit({ sessionToken, amount, transactionId, roundId, tableId, ...extra }) {
    this.#requireFields(
      { sessionToken, amount, transactionId },
      ["sessionToken", "amount", "transactionId"]
    );

    return this.#walletCall("credit", {
      sessionToken,
      amount,
      transactionId,
      roundId,
      tableId,
      ...extra,
    });
  }

  async #walletCall(operation, body) {
    const data = await this.#request(`/adapters/${operation}`, {
      method: "POST",
      body,
    });
    return data.data;
  }

  async #request(path, { method = "POST", body } = {}) {
    const url = `${this.#apiBase}${path.startsWith("/") ? path : `/${path}`}`;

    const headers = {
      "Content-Type": "application/json",
      "X-Game-Server-Key": this.#gameServerKey,
    };

    if (body?.sessionToken) {
      headers.Authorization = `Bearer ${body.sessionToken}`;
    }

    let response;
    try {
      response = await fetch(url, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined,
      });
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

  #requireFields(obj, fields) {
    for (const field of fields) {
      if (obj[field] === undefined || obj[field] === null || obj[field] === "") {
        throw new ProviderSDKError(`${field} is required`);
      }
    }
  }
}

export default ProviderGameServerSDK;
