import { useCallback, useEffect, useState } from "react";
import { formatTournamentLabel as label, tournamentRequest as request } from "../api/tournaments";
import "./tournaments.css";

function exportCsv(name, rows) {
  if (!rows.length) return;
  const keys = Object.keys(rows[0]);
  const cell = (value) => '"' + String(value ?? "").replace(/^[=+@-]/, "'$&").replaceAll('"', '""') + '"';
  const content = [
    keys,
    ...rows.map((row) => keys.map((k) => (typeof row[k] === "object" ? JSON.stringify(row[k]) : row[k]))),
  ]
    .map((row) => row.map(cell).join(","))
    .join("\r\n");
  const url = URL.createObjectURL(new Blob([content], { type: "text/csv;charset=utf-8" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function TournamentsPage({ initialSelectedId = null, onCreateNew, onEditDraft }) {
  const [items, setItems] = useState([]);
  const [selectedId, setSelectedId] = useState(initialSelectedId);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);
  const [search, setSearch] = useState("");
  const [tab, setTab] = useState("Matches");
  const [audit, setAudit] = useState([]);
  const [intervention, setIntervention] = useState(null);
  const [reason, setReason] = useState("");

  useEffect(() => {
    if (initialSelectedId) setSelectedId(initialSelectedId);
  }, [initialSelectedId]);

  const selected = items.find((t) => t.id === selectedId);
  const load = useCallback(async () => {
    setItems(await request());
  }, []);

  useEffect(() => {
    let active = true;
    let timer;
    async function refresh() {
      try {
        const data = await request();
        if (active) {
          setItems(data);
          setError("");
        }
      } catch (e) {
        if (active) setError(e.message);
      } finally {
        if (active) timer = setTimeout(refresh, 5000);
      }
    }
    refresh();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, []);

  useEffect(() => {
    if (tab !== "Audit" || !selectedId) return;
    let active = true;
    request(`/${selectedId}/audit`)
      .then((data) => {
        if (active) setAudit(data);
      })
      .catch((e) => {
        if (active) setError(e.message);
      });
    return () => {
      active = false;
    };
  }, [tab, selectedId, selected?.version]);

  async function perform(path, body) {
    setPending(true);
    setError("");
    try {
      await request(path, "POST", body);
      await load();
      setIntervention(null);
      setReason("");
    } catch (e) {
      setError(e.message);
    } finally {
      setPending(false);
    }
  }

  async function remove(t) {
    if (!window.confirm(`Delete tournament "${t.config.name}"? This cannot be undone.`)) return;
    setPending(true);
    setError("");
    try {
      await request(`/${t.id}`, "DELETE");
      await load();
      if (selectedId === t.id) setSelectedId(null);
      setIntervention(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setPending(false);
    }
  }

  const count = (status) => items.filter((t) => t.status === status).length;
  const name = (uid) => selected?.participants.find((p) => p.userId === uid)?.displayName || uid;

  return (
    <div className="tadmin">
      <div className="tadmin-heading">
        <div>
          <p className="tadmin-eyebrow">TOURNAMENT OPERATIONS</p>
          <h2>Manage tournaments.</h2>
          <p>Review registrations, brackets, matches, rewards, and audit logs.</p>
        </div>
        <button className="tadmin-primary" onClick={() => onCreateNew?.()}>
          Create tournament
        </button>
      </div>

      <div className="tadmin-metrics">
        {[
          ["Total cups", items.length],
          ["Upcoming", count("UPCOMING") + count("REGISTRATION_OPEN")],
          ["Live", count("LIVE")],
          ["Completed", count("COMPLETED")],
          ["Participants", items.reduce((n, t) => n + t.registeredParticipants, 0)],
          ["Disputed", items.reduce((n, t) => n + t.matches.filter((m) => m.status === "DISPUTED").length, 0)],
          ["Entry collections", items.reduce((n, t) => n + t.registrations.filter((r) => r.paymentStatus === "PAID").reduce((s, r) => s + (r.entryFee || 0), 0), 0)],
          ["Pending rewards", items.reduce((n, t) => n + t.rewards.filter((r) => r.status !== "SETTLED").length, 0)],
        ].map(([title, value]) => (
          <div key={title}>
            <span>{title}</span>
            <strong>{value}</strong>
          </div>
        ))}
      </div>

      {error ? (
        <p role="alert" className="tadmin-error">
          {error}
        </p>
      ) : null}

      <div className="tadmin-layout">
        <aside className="tadmin-panel">
          <h3>Tournaments</h3>
          {!items.length ? (
            <p>No cups yet. Create your first tournament.</p>
          ) : (
            items.map((t) => (
              <button
                className={`tadmin-cup ${selectedId === t.id ? "selected" : ""}`}
                key={t.id}
                onClick={() => {
                  setSelectedId(t.id);
                  setIntervention(null);
                }}
              >
                <strong>{t.config.name}</strong>
                <span>
                  {label(t.status)} · {t.config.gameFormat} player
                </span>
                <small>
                  {t.registeredParticipants}/{t.config.maxParticipants} joined
                </small>
              </button>
            ))
          )}
        </aside>

        {selected ? (
          <section className="tadmin-panel">
            <div className="tadmin-heading">
              <div>
                <h3>{selected.config.name}</h3>
                <p>
                  {label(selected.status)} · {selected.id}
                </p>
              </div>
              <div className="tadmin-actions">
                {selected.status === "DRAFT" ? (
                  <>
                    <button onClick={() => onEditDraft?.(selected)}>Edit</button>
                    <button
                      className="tadmin-primary"
                      disabled={pending}
                      onClick={() => perform(`/${selected.id}/publish`)}
                    >
                      Publish
                    </button>
                  </>
                ) : null}
                {selected.status === "REGISTRATION_CLOSED" ? (
                  <button disabled={pending} onClick={() => perform(`/${selected.id}/start`)}>
                    Start
                  </button>
                ) : null}
                {!["COMPLETED", "CANCELLED"].includes(selected.status) ? (
                  <button onClick={() => setIntervention({ action: "cancel", title: "Cancel tournament" })}>
                    Cancel cup
                  </button>
                ) : null}
                <button className="tadmin-danger" disabled={pending} onClick={() => remove(selected)}>
                  Delete
                </button>
              </div>
            </div>

            <div className="tadmin-tabs">
              {["Matches", "Participants", "Rewards", "Audit"].map((v) => (
                <button key={v} aria-pressed={tab === v} onClick={() => setTab(v)}>
                  {v}
                </button>
              ))}
            </div>

            {tab === "Matches" ? (
              <>
                {!selected.rounds.length ? (
                  <p>The bracket is generated once registration closes. Preview it while editing the draft.</p>
                ) : (
                  selected.rounds.map((r) => (
                    <section key={r.id}>
                      <h4>
                        {r.number === selected.rounds.length ? "Final" : `Round ${r.number}`} · {label(r.status)}
                      </h4>
                      <div className="tadmin-matches">
                        {selected.matches
                          .filter((m) => m.roundNumber === r.number)
                          .map((m) => (
                            <article key={m.id}>
                              <strong>Match {m.matchNumber}</strong>
                              <p>
                                {label(m.status)} · Top {m.qualifierCount} advance
                              </p>
                              <ul>
                                {m.assignedPlayerIds.map((uid) => (
                                  <li key={uid}>
                                    {name(uid)} {m.qualifiedPlayerIds.includes(uid) ? "✓" : ""}
                                  </li>
                                ))}
                              </ul>
                              {!m.assignedPlayerIds.length ? <p>Waiting for earlier qualifiers</p> : null}
                              <small>Game: {m.gameSessionId || "not started"}</small>
                              {m.finishingOrder.length ? (
                                <p>Finish order: {m.finishingOrder.map(name).join(" → ")}</p>
                              ) : null}
                              {m.status === "DISPUTED" ? (
                                <div className="tadmin-actions">
                                  <button
                                    onClick={() =>
                                      setIntervention({
                                        action: "restart-match",
                                        matchId: m.id,
                                        title: "Restart failed match",
                                      })
                                    }
                                  >
                                    Restart
                                  </button>
                                  <button
                                    onClick={() =>
                                      setIntervention({
                                        action: "resolve-match",
                                        matchId: m.id,
                                        title: "Recheck authoritative result",
                                      })
                                    }
                                  >
                                    Verify result
                                  </button>
                                </div>
                              ) : null}
                            </article>
                          ))}
                      </div>
                      {r.byeSources.length ? <p>{r.byeSources.length} automatic byes</p> : null}
                    </section>
                  ))
                )}
              </>
            ) : null}

            {tab === "Participants" ? (
              <>
                <div className="tadmin-actions">
                  <label>
                    Search players
                    <input
                      value={search}
                      onChange={(e) => setSearch(e.target.value)}
                      placeholder="Name or user ID"
                    />
                  </label>
                  <button onClick={() => exportCsv(`${selected.id}-participants.csv`, selected.participants)}>
                    Export participants
                  </button>
                </div>
                <div className="tadmin-table">
                  <table>
                    <thead>
                      <tr>
                        <th>Player</th>
                        <th>Status</th>
                        <th>Registration</th>
                        <th>Payment</th>
                        <th>Round / rank</th>
                        <th>Action</th>
                      </tr>
                    </thead>
                    <tbody>
                      {selected.participants
                        .filter((p) => `${p.displayName} ${p.userId}`.toLowerCase().includes(search.toLowerCase()))
                        .map((p) => {
                          const reg = selected.registrations.find((r) => r.userId === p.userId);
                          return (
                            <tr key={p.id}>
                              <td>
                                {p.displayName}
                                <small>{p.userId}</small>
                              </td>
                              <td>
                                {label(p.status)}
                                {p.eliminationReason ? <small>{p.eliminationReason}</small> : null}
                              </td>
                              <td>{reg ? new Date(reg.registeredAt).toLocaleString() : "—"}</td>
                              <td>{label(reg?.paymentStatus)}</td>
                              <td>
                                Round {p.currentRound}
                                {p.finalRank ? ` / #${p.finalRank}` : ""}
                              </td>
                              <td>
                                <button
                                  disabled={
                                    ["COMPLETED", "CANCELLED"].includes(selected.status) ||
                                    p.status === "DISQUALIFIED"
                                  }
                                  onClick={() =>
                                    setIntervention({
                                      action: "disqualify",
                                      userId: p.userId,
                                      title: `Disqualify ${p.displayName}`,
                                    })
                                  }
                                >
                                  Disqualify
                                </button>
                              </td>
                            </tr>
                          );
                        })}
                    </tbody>
                  </table>
                </div>
              </>
            ) : null}

            {tab === "Rewards" ? (
              <>
                <h4>
                  {selected.config.entryType === "PAID"
                    ? `Entry fee: ₹${selected.config.entryFee} · Champion reward: ${selected.config.prizePoints} points`
                    : `Champion reward: ${selected.config.prizePoints} points`}
                </h4>
                <p>
                  Verified entry collections: ₹
                  {selected.registrations
                    .filter((r) => r.paymentStatus === "PAID")
                    .reduce((s, r) => s + (r.entryFee || 0), 0)}
                </p>
                <button
                  onClick={() => exportCsv(`${selected.id}-rewards.csv`, selected.rewards)}
                  disabled={!selected.rewards.length}
                >
                  Export settlements
                </button>
                {selected.rewards.map((r) => (
                  <article key={r.id}>
                    <p>
                      {name(r.userId)} · {r.amount} {r.rewardType} · {label(r.status)}
                    </p>
                    <small>
                      {new Date(r.settledAt).toLocaleString()} · {r.id}
                    </small>
                  </article>
                ))}
                {!selected.rewards.length ? <p>Rewards settle atomically after verified completion.</p> : null}
              </>
            ) : null}

            {tab === "Audit" ? (
              <ol className="tadmin-audit">
                {audit.map((a) => (
                  <li key={a.id}>
                    <strong>{label(a.action)}</strong>
                    <p>{a.reason}</p>
                    <small>
                      {a.actorId} · {new Date(a.createdAt).toLocaleString()}
                    </small>
                  </li>
                ))}
              </ol>
            ) : null}

            {intervention ? (
              <form
                className="tadmin-intervention"
                onSubmit={(e) => {
                  e.preventDefault();
                  perform(`/${selected.id}/${intervention.action}`, {
                    reason,
                    userId: intervention.userId,
                    matchId: intervention.matchId,
                  });
                }}
              >
                <h4>{intervention.title}</h4>
                <p>This action is recorded in the tournament audit log.</p>
                <label>
                  Reason
                  <textarea
                    required
                    minLength={5}
                    maxLength={1000}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <div className="tadmin-actions">
                  <button type="button" onClick={() => setIntervention(null)}>
                    Back
                  </button>
                  <button className="tadmin-primary" disabled={pending}>
                    Confirm action
                  </button>
                </div>
              </form>
            ) : null}
          </section>
        ) : (
          <section className="tadmin-panel">
            <h3>Select a tournament</h3>
            <p>Review registrations, match assignments, results and audit history.</p>
          </section>
        )}
      </div>
    </div>
  );
}
