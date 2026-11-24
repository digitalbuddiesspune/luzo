import { useEffect, useState } from "react";
import {
  draftToForm,
  toTournamentPayload,
  tournamentRequest,
} from "../api/tournaments";
import "./tournaments.css";

export function CreateTournamentPage({ editingTournament, onDone, onCancel }) {
  const [editingId, setEditingId] = useState(editingTournament?.id || null);
  const [form, setForm] = useState(() => draftToForm(editingTournament));
  const [preview, setPreview] = useState(null);
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  useEffect(() => {
    setEditingId(editingTournament?.id || null);
    setForm(draftToForm(editingTournament));
    setPreview(null);
    setError("");
  }, [editingTournament]);

  async function save(event) {
    event.preventDefault();
    setPending(true);
    setError("");
    const shouldPublish = event.nativeEvent?.submitter?.dataset?.action === "publish";

    try {
      const saved = await tournamentRequest(
        editingId ? `/${editingId}` : "",
        editingId ? "PUT" : "POST",
        toTournamentPayload(form),
      );
      if (shouldPublish) {
        try {
          await tournamentRequest(`/${saved.id}/publish`, "POST");
        } catch (publishError) {
          setEditingId(saved.id);
          throw publishError;
        }
      }
      onDone?.(saved.id);
    } catch (e) {
      setError(e.message);
    } finally {
      setPending(false);
    }
  }

  const field = (key, text, type = "text", extras = {}) => (
    <label>
      {text}
      <input
        type={type}
        value={form[key]}
        onChange={(e) => {
          setForm({ ...form, [key]: e.target.value });
          setPreview(null);
        }}
        {...extras}
      />
    </label>
  );

  return (
    <div className="tadmin">
      <div className="tadmin-heading">
        <div>
          <p className="tadmin-eyebrow">TOURNAMENT OPERATIONS</p>
          <h2>{editingId ? "Edit draft tournament" : "Create a new tournament"}</h2>
          <p>Configure format, schedule, and rules, preview the bracket, then save as draft or publish.</p>
        </div>
        <button type="button" onClick={onCancel}>
          ← Back to tournaments
        </button>
      </div>

      <p className="tadmin-info">
        Free tournaments award non-cash points. Paid tournaments deduct the entry fee from each player&apos;s wallet on registration, refund on cancellation, and settle the prize pool to the champion on completion.
      </p>

      {error ? (
        <p role="alert" className="tadmin-error">
          {error}
        </p>
      ) : null}

      <form className="tadmin-panel" onSubmit={save}>
        <div className="tadmin-form">
          {field("name", "Tournament name", "text", { required: true, minLength: 3, maxLength: 100 })}
          {field("bannerUrl", "Banner URL (optional)", "url")}
          <label>
            Description
            <textarea
              value={form.description}
              maxLength={4000}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
            />
          </label>
          <label>
            Entry type
            <select
              value={form.entryType}
              onChange={(e) => setForm({ ...form, entryType: e.target.value })}
            >
              <option value="FREE">Free</option>
              <option value="PAID">Paid</option>
            </select>
          </label>
          <label>
            Game format
            <select
              value={form.gameFormat}
              onChange={(e) => {
                setForm({ ...form, gameFormat: +e.target.value });
                setPreview(null);
              }}
            >
              <option value={2}>2 player</option>
              <option value={4}>4 player</option>
            </select>
          </label>
          {field("maxParticipants", "Maximum participants", "number", {
            required: true,
            min: +form.gameFormat,
            max: 512,
          })}
          {field("entryFee", "Entry fee", "number", {
            min: 0,
            disabled: form.entryType === "FREE",
          })}
          {field("prizePoints", "Champion reward (non-cash points)", "number", {
            min: 0,
            max: 1000000,
            required: true,
          })}
          {field("registrationStart", "Registration opens (local time)", "datetime-local", {
            required: true,
          })}
          {field("registrationEnd", "Registration closes (local time)", "datetime-local", {
            required: true,
          })}
          {field("startAt", "Tournament starts (local time)", "datetime-local", {
            required: true,
          })}
          {field("qualification", "Qualifiers per round (comma-separated; blank = automatic)")}
          {field("joinTimeoutSeconds", "Check-in timeout (seconds)", "number", {
            min: 30,
            max: 1800,
            required: true,
          })}
          {field("matchDurationSeconds", "Maximum match duration (seconds)", "number", {
            min: 300,
            max: 14400,
            required: true,
          })}
          {field("reconnectSeconds", "Reconnect / turn timeout (seconds)", "number", {
            min: 15,
            max: 300,
            required: true,
          })}
          <label className="tadmin-wide">
            Published rules
            <textarea
              required
              minLength={20}
              maxLength={8000}
              value={form.rules}
              onChange={(e) => setForm({ ...form, rules: e.target.value })}
            />
          </label>
        </div>

        <div className="tadmin-actions">
          <button
            type="button"
            disabled={pending}
            onClick={async () => {
              setError("");
              try {
                setPreview(await tournamentRequest("/preview", "POST", toTournamentPayload(form)));
              } catch (e) {
                setError(e.message);
              }
            }}
          >
            Preview bracket
          </button>
          <button type="submit" data-action="draft" disabled={pending}>
            {pending ? "Saving…" : "Save draft"}
          </button>
          <button
            type="submit"
            data-action="publish"
            className="tadmin-primary"
            disabled={pending}
          >
            {pending ? "Publishing…" : "Publish"}
          </button>
        </div>

        {preview ? (
          <div className="tadmin-preview">
            {preview.map((r) => (
              <p key={r.number}>
                Round {r.number}: {r.players} players → {r.qualifiers.length} matches + {r.byes} byes → {r.advancing} qualifiers ({r.qualifiers.join(", ")} per match)
              </p>
            ))}
          </div>
        ) : null}
      </form>
    </div>
  );
}
