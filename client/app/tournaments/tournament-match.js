"use client";
import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { TournamentGameBoard } from "../components/ludo-shell";
import { cloneMockBootState } from "../lib/mock-state";
import { ensureGuestSession, fetchWalletOverview, normalizeWalletResponse, fetchMatchSnapshot, normalizeMatchSnapshot, rollMatchDice, submitMatchMove, toWebSocketUrl } from "../lib/server-game";
import { tournamentRequest } from "../lib/tournaments";
import { TournamentFrame } from "./tournament-ui";

export function TournamentMatchScreen({ id }) {
  const router = useRouter();
  const [session, setSession] = useState(null);
  const [assignment, setAssignment] = useState(null);
  const [wallet, setWallet] = useState(null);
  const [match, setMatch] = useState(null);
  const [error, setError] = useState("");
  const [connected, setConnected] = useState(false);
  const [clock, setClock] = useState(() => Date.now());
  const [rolling, setRolling] = useState(null);
  const busy = useRef(false);
  const accept = snapshot => setMatch(previous => {
    const next = normalizeMatchSnapshot(snapshot);
    return !previous || next.sequence >= previous.sequence ? next : previous;
  });
  useEffect(() => {
    let active = true, timer, socket;
    async function boot() {
      try {
        const s = await ensureGuestSession("Tournament Player");
        const [m, walletOverview] = await Promise.all([
          tournamentRequest(`tournament-matches/${encodeURIComponent(id)}`, s),
          fetchWalletOverview(s.sessionToken),
        ]);
        if (!active) return;
        setSession(s); setAssignment(m); setWallet(normalizeWalletResponse(walletOverview));
        if (!m.gameSessionId || m.status !== "IN_PROGRESS") return;
        async function sync() {
          try {
            const [snapshot, nextAssignment] = await Promise.all([fetchMatchSnapshot(s.sessionToken, m.gameSessionId), tournamentRequest(`tournament-matches/${encodeURIComponent(id)}`, s)]);
            if (active) { accept(snapshot); setAssignment(nextAssignment); setError(""); }
          } catch (e) { if (active) setError(e.message); }
          finally { if (active) timer = setTimeout(sync, 2500); }
        }
        sync();
        socket = new WebSocket(toWebSocketUrl(`/ws/matches/${m.gameSessionId}?sessionToken=${encodeURIComponent(s.sessionToken)}`));
        socket.onopen = () => { if (active) setConnected(true); };
        socket.onclose = () => { if (active) setConnected(false); };
        socket.onmessage = ({ data }) => { try { const event = JSON.parse(data); if (active && event.match) accept(event.match); } catch {} };
      } catch (e) { if (active) setError(e.message); }
    }
    boot();
    const tick = setInterval(() => setClock(Date.now()), 500);
    return () => { active = false; clearTimeout(timer); clearInterval(tick); if (socket) { socket.onmessage = null; socket.close(); } };
  }, [id]);
  async function act(kind, tokenIndex) {
    if (busy.current || !session || !match || match.currentTurnUserId !== session.userId) return;
    busy.current = true; setError("");
    if (kind === "roll") setRolling(session.userId);
    try { accept(await (kind === "roll" ? rollMatchDice(session.sessionToken, match.id) : submitMatchMove(session.sessionToken, match.id, tokenIndex))); }
    catch (e) { setError(e.message); try { accept(await fetchMatchSnapshot(session.sessionToken, match.id)); } catch {} }
    finally { busy.current = false; setRolling(null); }
  }
  const back = assignment ? `/tournaments/details?id=${encodeURIComponent(assignment.tournamentId)}` : "/tournaments";
  if (!match || assignment?.status !== "IN_PROGRESS" || match.phase === "finished") return <TournamentFrame back={back} title="Tournament match">{error ? <p role="alert" className="tournament-error">{error}</p> : null}<section className="tournament-panel"><h2>{match?.phase === "finished" || assignment?.resultVerified ? "Match finished" : assignment?.status === "DISPUTED" ? "Match under review" : "Preparing your match"}</h2><p>{assignment?.resultVerified ? "Your result has been verified. Check the bracket for qualification and your next assignment." : "The tournament lobby has your check-in, match status, and next steps."}</p><Link className="tournament-primary" href={back}>Back to tournament</Link></section></TournamentFrame>;
  const appState = cloneMockBootState();
  appState.wallet = wallet;
  appState.history = wallet?.history ?? [];
  appState.profile = { ...appState.profile, id: session.userId, displayName: session.displayName };
  return <><TournamentGameBoard appState={appState} match={match} userPlayerId={session.userId} turnProgress={match.turnDeadlineAt ? Math.max(0, Math.min(1, (new Date(match.turnDeadlineAt).getTime() - clock) / (match.turnTimeoutSeconds * 1000))) : 1} rollingDiceUserId={rolling} statusMessage={error || (connected ? "Tournament match" : "Reconnecting — syncing match state")} onSelectToken={index => act("move", index)} onRollDice={() => act("roll")} onLeave={() => router.push(back)} onOpenUtilities={() => router.push(back)} onOpenHistory={() => router.push(back)} onPotIntroComplete={() => {}}/>{error ? <p className="tournament-game-status" role="alert">{error}</p> : null}</>;
}
