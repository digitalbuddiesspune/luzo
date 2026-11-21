"use client";
/* eslint-disable @next/next/no-img-element */
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ensureGuestSession } from "../lib/server-game";
import { tournamentRequest, watchTournament, label, localTime } from "../lib/tournaments";
import "./tournaments.css";

function useSession() {
  const [session, setSession] = useState(null);
  const [error, setError] = useState("");
  useEffect(() => { let active = true; ensureGuestSession("Tournament Player").then(s => { if (active) setSession(s); }).catch(e => { if (active) setError(e.message); }); return () => { active = false; }; }, []);
  return { session, error };
}
export function TournamentFrame({ children, back = "/", title = "Tournaments" }) {
  return <main className="viewport-shell"><section className="portrait-device tournament-shell"><header className="tournament-header"><Link href={back} aria-label="Go back">‹</Link><div><span className="tournament-eyebrow">POTLUDO ARENA</span><h1>{title}</h1></div><svg className="tournament-cup" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" aria-hidden="true"><path d="M7 3h10v6a5 5 0 0 1-10 0V3Zm0 2H3v3a4 4 0 0 0 4 4m10-7h4v3a4 4 0 0 1-4 4M12 14v6m-4 1h8"/></svg></header>{children}</section></main>;
}
function ErrorNotice({ message }) { return message ? <p className="tournament-error" role="alert">{message}</p> : null; }
function Status({ value }) { return <span className={`tournament-status status-${value?.toLowerCase()}`}>{label(value)}</span>; }
const categories = ["Registration open", "Upcoming", "Live", "My tournaments", "Completed", "History"];
export function TournamentHome() {
  const { session, error: sessionError } = useSession();
  const [category, setCategory] = useState(categories[0]);
  const [items, setItems] = useState(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true, timer;
    async function load() {
      try {
        const mine = ["My tournaments", "History"].includes(category);
        if (mine && !session) return;
        const result = await tournamentRequest(mine ? "tournaments/my" : "tournaments", session);
        if (active) { setItems(result); setError(""); }
      } catch (e) { if (active) setError(e.message); }
      finally { if (active) timer = setTimeout(load, 5000); }
    }
    load(); return () => { active = false; clearTimeout(timer); };
  }, [category, session]);
  const filtered = items?.filter(t => {
    if (category === "My tournaments") return true;
    if (category === "History") return ["COMPLETED", "CANCELLED", "FAILED"].includes(t.status);
    if (category === "Live") return ["LIVE", "UNDER_REVIEW", "BRACKET_GENERATED"].includes(t.status);
    return t.status === ({ "Registration open": "REGISTRATION_OPEN", Upcoming: "UPCOMING", Completed: "COMPLETED" })[category];
  });
  return <TournamentFrame><div className="tournament-intro"><span className="tournament-eyebrow">ONE BRACKET. ONE CHAMPION.</span><h2>Play your way to the top.</h2><p>Join a free knockout cup. Win your match, qualify, and make the final.</p></div><nav className="tournament-tabs" aria-label="Tournament categories">{categories.map(c => <button key={c} aria-pressed={category === c} onClick={() => { setCategory(c); setItems(null); }}>{c}</button>)}</nav><ErrorNotice message={error || sessionError} /><div className="tournament-list">{!items && !error ? <p role="status">Loading tournaments…</p> : null}{filtered?.length === 0 ? <div className="tournament-empty"><h2>No tournaments here yet</h2><p>Check another category or come back for the next cup.</p></div> : null}{filtered?.map(t => <Link key={t.id} href={`/tournaments/details?id=${encodeURIComponent(t.id)}`} className="tournament-card"><div className="tournament-card-top"><Status value={t.status} /><span>FREE ENTRY</span></div><h2>{t.config.name}</h2><p>{t.config.gameFormat}-player knockout · {t.config.maxParticipants} places</p><div className="tournament-prize">{t.config.prizePoints.toLocaleString()} <small>tournament points</small></div><progress max={t.config.maxParticipants} value={t.registeredParticipants} aria-label="Registration capacity"/><div className="tournament-card-footer"><span>{t.registeredParticipants}/{t.config.maxParticipants} joined</span><span>View cup →</span></div><time dateTime={t.config.startAt}>{localTime(t.config.startAt)}</time></Link>)}</div></TournamentFrame>;
}
export function TournamentDetails({ id }) {
  const { session, error: sessionError } = useSession();
  const [t, setT] = useState(null);
  const [preview, setPreview] = useState([]);
  const [tab, setTab] = useState("Overview");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const update = useCallback(next => setT(previous => !previous || previous.id !== next.id || (next.version ?? 0) >= (previous.version ?? 0) ? next : previous), []);
  useEffect(() => {
    let active = true, timer;
    async function load() {
      try { const result = await tournamentRequest(`tournaments/${id}`); if (active) { update(result); setError(""); } }
      catch (e) { if (active) setError(e.message); }
      finally { if (active) timer = setTimeout(load, 5000); }
    }
    load();
    tournamentRequest(`tournaments/${id}/bracket`).then(result => { if (active) setPreview(result.preview); }).catch(() => {});
    const stop = session ? watchTournament(id, session, update) : () => {};
    return () => { active = false; clearTimeout(timer); stop(); };
  }, [id, session, update]);
  async function action(path, message) {
    setPending(true); setError("");
    try { update(await tournamentRequest(path, session, { method: "POST" })); setNotice(message); setConfirm(false); }
    catch (e) { setError(e.message); } finally { setPending(false); }
  }
  const player = t?.participants.find(p => p.userId === session?.userId);
  const assigned = t?.matches.find(m => m.assignedPlayerIds.includes(session?.userId) && ["READY", "IN_PROGRESS", "DISPUTED"].includes(m.status));
  const name = userId => t?.participants.find(p => p.userId === userId)?.displayName || userId;
  return <TournamentFrame back="/tournaments" title={t?.config.name || "Tournament"}><ErrorNotice message={error || sessionError}/>{notice ? <p className="tournament-notice" role="status">{notice}</p> : null}{!t ? <p role="status">Loading tournament…</p> : <>
    {t.config.bannerUrl ? <img className="tournament-banner" src={t.config.bannerUrl} alt="" referrerPolicy="no-referrer" /> : null}
    <div className="tournament-detail-heading"><Status value={t.status}/><h2>{t.config.gameFormat}-player knockout</h2><p>{t.registeredParticipants} of {t.config.maxParticipants} places filled · Free entry</p>{player ? <p className="tournament-notice">Your status: {label(player.status)}</p> : null}</div>
    {assigned ? <section className="tournament-assignment"><span className="tournament-eyebrow">ROUND {assigned.roundNumber} · MATCH {assigned.matchNumber}</span><h2>{assigned.status === "DISPUTED" ? "Match under review" : "Your match is ready"}</h2><p>{assigned.assignedPlayerIds.map(name).join(" · ")}</p>{assigned.status === "READY" ? <><p>Check in by {localTime(assigned.joinDeadline)}. Missing check-in forfeits your place.</p><button className="tournament-primary" disabled={pending || assigned.joinedPlayerIds.includes(session?.userId)} onClick={() => action(`tournament-matches/${encodeURIComponent(assigned.id)}/join`, "Checked in. Waiting for the other players.")}>{assigned.joinedPlayerIds.includes(session?.userId) ? "Checked in — waiting for players" : "Check in to match"}</button><p>{assigned.joinedPlayerIds.length}/{assigned.assignedPlayerIds.length} checked in</p></> : null}{assigned.gameSessionId && assigned.status === "IN_PROGRESS" ? <Link className="tournament-primary" href={`/tournaments/match?id=${encodeURIComponent(assigned.id)}`}>Enter / reconnect to game →</Link> : null}</section> : null}
    {t.status === "COMPLETED" ? <section className="tournament-winner"><span className="tournament-eyebrow">TOURNAMENT CHAMPION</span><h2>{name(t.championId)}</h2><p>{t.config.prizePoints.toLocaleString()} tournament points</p>{t.rewards.map(r => <p key={r.id}>Reward {label(r.status)} · {localTime(r.settledAt)}</p>)}</section> : null}
    <nav className="tournament-tabs" aria-label="Tournament details">{["Overview", "Bracket", "Players", "Results"].map(v => <button key={v} aria-pressed={tab === v} onClick={() => setTab(v)}>{v}</button>)}</nav>
    {tab === "Overview" ? <section className="tournament-panel"><p>{t.config.description}</p><dl><dt>Registration closes</dt><dd>{localTime(t.config.registrationEnd)}</dd><dt>Starts</dt><dd>{localTime(t.config.startAt)}</dd><dt>Champion reward</dt><dd>{t.config.prizePoints} non-cash points</dd><dt>Check-in window</dt><dd>{t.config.joinTimeoutSeconds} seconds</dd><dt>Reconnect / turn timeout</dt><dd>{t.config.reconnectSeconds} seconds</dd></dl><h3>Published rules</h3><p className="tournament-rules">{t.config.rules}</p><p>Qualifications: {t.config.qualifiersPerRound.length ? t.config.qualifiersPerRound.join(", ") + " per match by round" : "First place advances, with additional places when needed to fill the next round. See the bracket preview."}</p>{t.status === "REGISTRATION_OPEN" && (!player || player.status === "WITHDRAWN") ? <button className="tournament-primary" disabled={!session || pending || t.registeredParticipants >= t.config.maxParticipants} onClick={() => setConfirm(true)}>{t.registeredParticipants >= t.config.maxParticipants ? "Tournament full" : "Join free tournament"}</button> : null}{player?.status === "REGISTERED" && ["REGISTRATION_OPEN", "REGISTRATION_CLOSED"].includes(t.status) ? <button className="tournament-secondary" disabled={pending} onClick={() => action(`tournaments/${id}/cancel-registration`, "Registration cancelled.")}>Cancel registration</button> : null}</section> : null}
    {tab === "Bracket" ? <section className="tournament-bracket">{t.rounds.length === 0 ? <><h2>Bracket preview</h2><p>Based on full capacity. Final seeding is generated once registration closes; a smaller field may change qualifiers.</p>{preview.map(r => <div className="tournament-panel" key={r.number}><h3>Round {r.number}</h3><p>{r.players} players · {r.qualifiers.length} matches · {r.byes} byes</p><p>Qualifiers per match: {r.qualifiers.join(", ")} → {r.advancing} advance</p></div>)}</> : t.rounds.map(r => <section className="tournament-round" key={r.id}><h2>{r.number === t.rounds.length ? "Final" : `Round ${r.number}`}</h2><Status value={r.status}/>{t.matches.filter(m => m.roundNumber === r.number).map(m => <article className="tournament-panel" key={m.id}><h3>Match {m.matchNumber}</h3><Status value={m.status}/><ul>{m.assignedPlayerIds.length ? m.assignedPlayerIds.map(uid => <li key={uid}>{name(uid)} {m.qualifiedPlayerIds.includes(uid) ? "— qualified" : ""}</li>) : m.sources.map((source, i) => <li key={source}>Qualifier {i + 1} from previous round</li>)}</ul><p>Top {m.qualifierCount} advance</p></article>)}{r.byeSources.length ? <p>{r.byeSources.length} bye(s): advance without a match.</p> : null}</section>)}</section> : null}
    {tab === "Players" || tab === "Results" ? <section className="tournament-panel"><h2>{tab === "Results" ? "Standings" : "Participants"}</h2>{tab === "Results" && t.status !== "COMPLETED" ? <p>Standings are provisional until tournament completion. Players eliminated in the same round share a rank.</p> : null}<ol className="tournament-players">{[...t.participants].filter(p => p.status !== "WITHDRAWN").sort((a,b) => (a.finalRank ?? 999) - (b.finalRank ?? 999)).map(p => <li key={p.id}><span>{p.finalRank ? `#${p.finalRank} ` : ""}{p.displayName}</span><Status value={p.status}/></li>)}</ol></section> : null}
    {confirm ? <div className="tournament-panel tournament-confirm" role="region" aria-label="Confirm registration"><h2>Confirm your place</h2><p>Entry is free. By joining, you accept the published rules and check-in deadline.</p><button className="tournament-primary" disabled={pending} onClick={() => action(`tournaments/${id}/register`, "Registration confirmed. Your match assignment will appear here when the tournament starts.")}>{pending ? "Registering…" : "Confirm free registration"}</button><button className="tournament-secondary" onClick={() => setConfirm(false)} disabled={pending}>Not now</button></div> : null}
  </>}</TournamentFrame>;
}
