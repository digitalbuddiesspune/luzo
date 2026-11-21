import { getAdminToken } from "./authStorage";

const base = import.meta.env.VITE_TOURNAMENT_API_BASE_URL || "/api/admin/tournaments";

export async function tournamentRequest(path = "", method = "GET", body) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${getAdminToken()}`,
      "Content-Type": "application/json",
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const result = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(result.message || `Request failed (${response.status})`);
  return result;
}

export const formatTournamentLabel = (value) =>
  String(value || "").replaceAll("_", " ").toLowerCase();

export function toLocalDateTimeInput(date) {
  const d = new Date(date);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

export function initialTournamentConfig() {
  const now = Date.now();
  return {
    name: "",
    description: "",
    bannerUrl: "",
    entryType: "FREE",
    gameFormat: 2,
    maxParticipants: 16,
    entryFee: 0,
    prizePoints: 100,
    registrationStart: toLocalDateTimeInput(now),
    registrationEnd: toLocalDateTimeInput(now + 3600000),
    startAt: toLocalDateTimeInput(now + 3900000),
    qualification: "",
    joinTimeoutSeconds: 120,
    matchDurationSeconds: 3600,
    reconnectSeconds: 60,
    rules:
      "No-shows forfeit after the check-in deadline. Disconnected players must return before their turn timer expires. Byes advance without elimination. Timed-out matches are reviewed by an administrator. Rewards are non-cash tournament points.",
  };
}

export function draftToForm(tournament) {
  if (!tournament) return initialTournamentConfig();
  return {
    ...tournament.config,
    registrationStart: toLocalDateTimeInput(tournament.config.registrationStart),
    registrationEnd: toLocalDateTimeInput(tournament.config.registrationEnd),
    startAt: toLocalDateTimeInput(tournament.config.startAt),
    qualification: (tournament.config.qualifiersPerRound || []).join(","),
  };
}

export function toTournamentPayload(form) {
  return {
    ...form,
    gameFormat: +form.gameFormat,
    maxParticipants: +form.maxParticipants,
    entryFee: form.entryType === "FREE" ? 0 : +form.entryFee,
    prizePoints: +form.prizePoints,
    joinTimeoutSeconds: +form.joinTimeoutSeconds,
    matchDurationSeconds: +form.matchDurationSeconds,
    reconnectSeconds: +form.reconnectSeconds,
    registrationStart: new Date(form.registrationStart).toISOString(),
    registrationEnd: new Date(form.registrationEnd).toISOString(),
    startAt: new Date(form.startAt).toISOString(),
    qualifiersPerRound: form.qualification.trim()
      ? form.qualification.split(",").map((v) => Number(v.trim()))
      : [],
  };
}
