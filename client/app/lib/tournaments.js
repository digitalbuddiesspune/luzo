import { toWebSocketUrl } from "./server-game";

const base = (process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080").replace(/\/$/, "");
export async function tournamentRequest(path, session, options = {}) {
  const response = await fetch(`${base}/api/${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...(session ? { "X-Session-Token": session.sessionToken } : {}), ...options.headers },
    cache: "no-store",
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || `Request failed (${response.status}). Please try again.`);
  }
  return response.json();
}
export function watchTournament(id, session, onUpdate) {
  const socket = new WebSocket(toWebSocketUrl(`/ws/tournaments/${encodeURIComponent(id)}?sessionToken=${encodeURIComponent(session.sessionToken)}`));
  socket.onmessage = ({ data }) => {
    try { const event = JSON.parse(data); if (event.tournament) onUpdate(event.tournament); } catch {}
  };
  return () => { socket.onmessage = null; socket.close(); };
}
export const label = (value) => String(value || "").toLowerCase().replaceAll("_", " ");
export const localTime = (value) => new Date(value).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
