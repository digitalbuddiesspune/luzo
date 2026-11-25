import { forwardRef, useCallback, useEffect, useImperativeHandle, useState } from "react";
import { fetchPlatformSettings, updatePlatformSettings } from "../api/client";
import { formatAmount } from "../utils/format";

const LEVEL_GRADIENTS = [
  "linear-gradient(135deg, #167a3a 0%, #37b864 100%)",
  "linear-gradient(135deg, #5d2ec4 0%, #9855ff 100%)",
  "linear-gradient(135deg, #b55a07 0%, #eb9428 100%)",
  "linear-gradient(135deg, #1b56b3 0%, #4b8ff7 100%)",
  "linear-gradient(135deg, #b83227 0%, #e8624d 100%)",
  "linear-gradient(135deg, #0d6e6e 0%, #27aba0 100%)",
  "linear-gradient(135deg, #9a7d0a 0%, #d4b029 100%)",
];

function computeAutoPrizePool(entryFee, players, platformFeePerPlayer) {
  const fee = Number(entryFee);
  if (!Number.isFinite(fee) || fee <= 0) return 0;
  return Math.max(0, fee * players - platformFeePerPlayer * players);
}

function parseOptionalPrizePool(raw) {
  const trimmed = String(raw ?? "").trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > 10_000_000) {
    return Number.NaN;
  }
  return parsed;
}

function makeSlug(name, fallbackIndex) {
  const base = String(name || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
  return base || `level_${fallbackIndex}`;
}

function validateDraft(draft) {
  const name = String(draft.name ?? "").trim();
  if (!name || name.length > 48) {
    return "Level name must be between 1 and 48 characters.";
  }
  const fee = Number(draft.entryFee);
  if (!Number.isInteger(fee) || fee < 1 || fee > 1_000_000) {
    return "Entry fee must be a whole number from 1 to 1,000,000.";
  }
  const p2 = parseOptionalPrizePool(draft.prizePool2p);
  if (Number.isNaN(p2)) {
    return "2P prize pool must be a whole number from 0 to 10,000,000.";
  }
  const p4 = parseOptionalPrizePool(draft.prizePool4p);
  if (Number.isNaN(p4)) {
    return "4P prize pool must be a whole number from 0 to 10,000,000.";
  }
  if (!Array.isArray(draft.playerModes) || draft.playerModes.length === 0) {
    return "Select at least 2 Player or 4 Player.";
  }
  return "";
}

function toPayload(levelsList) {
  return levelsList.map((lvl, index) => ({
    id: lvl.id,
    name: String(lvl.name).trim(),
    entryFee: Number(lvl.entryFee),
    prizePool2p: parseOptionalPrizePool(lvl.prizePool2p),
    prizePool4p: parseOptionalPrizePool(lvl.prizePool4p),
    playerModes: [...lvl.playerModes].sort((a, b) => a - b),
    enabled: lvl.enabled !== false,
    sortOrder: index + 1,
  }));
}

export const LevelsPage = forwardRef(function LevelsPage({ currency = "INR" }, ref) {
  const [levels, setLevels] = useState([]);
  const [platformFee, setPlatformFee] = useState(10);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  // Active level being edited
  const [editingId, setEditingId] = useState(null);
  const [editDraft, setEditDraft] = useState(null);

  // Quick-add form state
  const [newName, setNewName] = useState("");
  const [newEntryFee, setNewEntryFee] = useState("100");
  const [newPrize2p, setNewPrize2p] = useState("");
  const [newPrize4p, setNewPrize4p] = useState("");
  const [newModes, setNewModes] = useState([2, 4]);

  const applySettings = useCallback((settings) => {
    const incoming = Array.isArray(settings.gameLevels) ? settings.gameLevels : [];
    const normalized = incoming.map((lvl, idx) => ({
      id: lvl.id || `level_${idx + 1}`,
      name: lvl.name || `LEVEL ${idx + 1}`,
      entryFee: String(lvl.entryFee ?? 100),
      prizePool2p:
        lvl.prizePool2p !== null && lvl.prizePool2p !== undefined ? String(lvl.prizePool2p) : "",
      prizePool4p:
        lvl.prizePool4p !== null && lvl.prizePool4p !== undefined ? String(lvl.prizePool4p) : "",
      playerModes:
        Array.isArray(lvl.playerModes) && lvl.playerModes.length > 0 ? lvl.playerModes : [2, 4],
      enabled: lvl.enabled !== false,
    }));
    setLevels(normalized);
    setPlatformFee(settings.platformFeePerPlayer ?? 10);
    setUpdatedAt(settings.updatedAt || null);
  }, []);

  const loadSettings = useCallback(
    async ({ silent = false } = {}) => {
      if (!silent) {
        setLoading(true);
      }
      setError("");
      try {
        const settings = await fetchPlatformSettings();
        applySettings(settings);
      } catch (loadError) {
        setError(loadError.message || "Failed to load game levels.");
        throw loadError;
      } finally {
        if (!silent) {
          setLoading(false);
        }
      }
    },
    [applySettings],
  );

  useImperativeHandle(
    ref,
    () => ({
      refresh: () => loadSettings({ silent: true }),
    }),
    [loadSettings],
  );

  useEffect(() => {
    loadSettings().catch(() => {});
  }, [loadSettings]);

  const persistLevels = async (nextLevels, successText) => {
    setSaving(true);
    setError("");
    setMessage("");
    try {
      const settings = await updatePlatformSettings({
        gameLevels: toPayload(nextLevels),
      });
      applySettings(settings);
      setMessage(successText);
      return true;
    } catch (saveError) {
      setError(saveError.message || "Failed to save game levels.");
      return false;
    } finally {
      setSaving(false);
    }
  };

  const startEditing = (level) => {
    setError("");
    setMessage("");
    setEditingId(level.id);
    setEditDraft({
      ...level,
      playerModes: [...level.playerModes],
    });
  };

  const cancelEditing = () => {
    setEditingId(null);
    setEditDraft(null);
    setError("");
  };

  const handleSaveEdit = async (event) => {
    event.preventDefault();
    if (!editDraft || saving) return;

    const draftError = validateDraft(editDraft);
    if (draftError) {
      setError(draftError);
      return;
    }

    const nextLevels = levels.map((lvl) => (lvl.id === editingId ? { ...editDraft } : lvl));
    const ok = await persistLevels(nextLevels, `Updated "${editDraft.name.trim()}".`);
    if (ok) {
      setEditingId(null);
      setEditDraft(null);
    }
  };

  const handleToggleEnabled = async (level) => {
    if (saving) return;
    const nextLevels = levels.map((lvl) =>
      lvl.id === level.id ? { ...lvl, enabled: !lvl.enabled } : lvl,
    );
    await persistLevels(
      nextLevels,
      `"${level.name}" is now ${!level.enabled ? "active" : "hidden"}.`,
    );
  };

  const handleMoveLevel = async (index, direction) => {
    const target = index + direction;
    if (target < 0 || target >= levels.length || saving) return;
    const nextLevels = [...levels];
    const [moved] = nextLevels.splice(index, 1);
    nextLevels.splice(target, 0, moved);
    await persistLevels(nextLevels, "Level order updated.");
  };

  const handleRemoveLevel = async (level) => {
    if (levels.length <= 1 || saving) return;
    const nextLevels = levels.filter((lvl) => lvl.id !== level.id);
    const ok = await persistLevels(nextLevels, `Deleted "${level.name}".`);
    if (ok && editingId === level.id) {
      setEditingId(null);
      setEditDraft(null);
    }
  };

  const handleAddLevel = async (event) => {
    event.preventDefault();
    if (saving) return;

    const draft = {
      name: newName.trim() || `LEVEL ${levels.length + 1}`,
      entryFee: newEntryFee,
      prizePool2p: newPrize2p,
      prizePool4p: newPrize4p,
      playerModes: newModes,
    };
    const draftError = validateDraft(draft);
    if (draftError) {
      setError(draftError);
      return;
    }

    const id = `${makeSlug(draft.name, levels.length + 1)}_${Date.now().toString(36).slice(-4)}`;
    const nextLevels = [
      ...levels,
      {
        id,
        name: draft.name,
        entryFee: String(Number(draft.entryFee)),
        prizePool2p: draft.prizePool2p.trim(),
        prizePool4p: draft.prizePool4p.trim(),
        playerModes: [...newModes].sort((a, b) => a - b),
        enabled: true,
      },
    ];
    const ok = await persistLevels(nextLevels, `Added "${draft.name}".`);
    if (ok) {
      setNewName("");
      setNewPrize2p("");
      setNewPrize4p("");
    }
  };

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-[var(--color-line)] bg-white px-6 py-16 shadow-[var(--shadow-card)]">
        <div className="h-9 w-9 animate-spin rounded-full border-2 border-[var(--color-line)] border-t-[var(--accent)]" />
        <p className="text-sm font-medium text-[var(--color-muted)]">Loading game levels…</p>
      </div>
    );
  }

  const autoNew2p = computeAutoPrizePool(newEntryFee, 2, platformFee);
  const autoNew4p = computeAutoPrizePool(newEntryFee, 4, platformFee);

  return (
    <div className="mx-auto max-w-6xl space-y-6 animate-fade-up">
      {/* Header summary */}
      <section className="flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-[var(--color-line)] bg-white p-6 shadow-[var(--shadow-card)]">
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--accent)]">
            Matchmaking Tiers
          </p>
          <h2 className="mt-1 text-xl font-extrabold tracking-tight text-[var(--color-ink)]">
            Game Levels, Entry Fees &amp; Prize Pools
          </h2>
          <p className="mt-1.5 text-sm text-[var(--color-muted)]">
            Click <strong>Edit</strong> on any level to change its name, entry fee, 2-Player or
            4-Player prize pool, and mode availability. Blank prize pools auto-calculate using the{" "}
            <strong className="text-[var(--color-ink)]">
              {formatAmount(platformFee, currency)}
            </strong>{" "}
            per-seat platform fee.
          </p>
        </div>

        {updatedAt ? (
          <span className="text-xs text-[var(--color-muted)]">
            Last updated {new Date(updatedAt).toLocaleString("en-IN")}
          </span>
        ) : null}
      </section>

      {message ? (
        <p className="rounded-xl border border-[var(--accent)]/25 bg-[var(--accent-soft)] px-4 py-3 text-sm font-medium text-[var(--accent)]">
          {message}
        </p>
      ) : null}
      {error ? (
        <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700">
          {error}
        </p>
      ) : null}

      {/* Add new level form */}
      <form
        onSubmit={handleAddLevel}
        className="rounded-2xl border border-[var(--color-line)] bg-white p-5 shadow-[var(--shadow-card)]"
      >
        <p className="text-xs font-bold uppercase tracking-[0.12em] text-[var(--color-muted)]">
          Add new level
        </p>
        <div className="mt-3 grid gap-3 sm:grid-cols-12 sm:items-end">
          <label className="block sm:col-span-3">
            <span className="text-xs font-semibold text-[var(--color-muted)]">Level name</span>
            <input
              type="text"
              maxLength={48}
              placeholder={`LEVEL ${levels.length + 1}`}
              value={newName}
              onChange={(event) => setNewName(event.target.value)}
              className="mt-1.5 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2.5 text-sm font-semibold text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          <label className="block sm:col-span-2">
            <span className="text-xs font-semibold text-[var(--color-muted)]">
              Entry ({currency})
            </span>
            <input
              type="number"
              min={1}
              max={1_000_000}
              step={1}
              value={newEntryFee}
              onChange={(event) => setNewEntryFee(event.target.value)}
              className="mt-1.5 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2.5 text-sm font-semibold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          <label className="block sm:col-span-2">
            <span className="text-xs font-semibold text-[var(--color-muted)]">
              2P Prize ({currency})
            </span>
            <input
              type="number"
              min={0}
              max={10_000_000}
              step={1}
              placeholder={`Auto: ${autoNew2p}`}
              value={newPrize2p}
              onChange={(event) => setNewPrize2p(event.target.value)}
              disabled={!newModes.includes(2)}
              className="mt-1.5 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2.5 text-sm font-semibold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2 disabled:opacity-45"
            />
          </label>

          <label className="block sm:col-span-2">
            <span className="text-xs font-semibold text-[var(--color-muted)]">
              4P Prize ({currency})
            </span>
            <input
              type="number"
              min={0}
              max={10_000_000}
              step={1}
              placeholder={`Auto: ${autoNew4p}`}
              value={newPrize4p}
              onChange={(event) => setNewPrize4p(event.target.value)}
              disabled={!newModes.includes(4)}
              className="mt-1.5 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2.5 text-sm font-semibold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2 disabled:opacity-45"
            />
          </label>

          <div className="sm:col-span-2">
            <span className="block text-xs font-semibold text-[var(--color-muted)]">Modes</span>
            <div className="mt-1.5 flex gap-1.5">
              {[2, 4].map((mode) => {
                const active = newModes.includes(mode);
                return (
                  <button
                    key={mode}
                    type="button"
                    onClick={() =>
                      setNewModes((prev) =>
                        prev.includes(mode)
                          ? prev.filter((m) => m !== mode)
                          : [...prev, mode].sort((a, b) => a - b),
                      )
                    }
                    className={`flex-1 rounded-xl border px-2.5 py-2 text-xs font-bold transition ${
                      active
                        ? "border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]"
                        : "border-[var(--color-line)] bg-white text-[var(--color-muted)]"
                    }`}
                  >
                    {mode}P
                  </button>
                );
              })}
            </div>
          </div>

          <div className="sm:col-span-1">
            <button
              type="submit"
              disabled={saving}
              className="w-full rounded-xl bg-[var(--accent)] px-3 py-2.5 text-sm font-bold text-white shadow-sm transition hover:brightness-105 disabled:opacity-50"
            >
              + Add
            </button>
          </div>
        </div>
      </form>

      {/* Levels list */}
      <section className="space-y-3">
        {levels.map((level, index) => {
          const gradient = LEVEL_GRADIENTS[index % LEVEL_GRADIENTS.length];
          const isEditing = editingId === level.id && editDraft !== null;
          const auto2p = computeAutoPrizePool(
            isEditing ? editDraft.entryFee : level.entryFee,
            2,
            platformFee,
          );
          const auto4p = computeAutoPrizePool(
            isEditing ? editDraft.entryFee : level.entryFee,
            4,
            platformFee,
          );
          const effective2p = level.prizePool2p !== "" ? Number(level.prizePool2p) : auto2p;
          const effective4p = level.prizePool4p !== "" ? Number(level.prizePool4p) : auto4p;
          const has2p = level.playerModes.includes(2);
          const has4p = level.playerModes.includes(4);

          if (isEditing) {
            const draftHas2p = editDraft.playerModes.includes(2);
            const draftHas4p = editDraft.playerModes.includes(4);

            return (
              <form
                key={level.id}
                onSubmit={handleSaveEdit}
                className="rounded-2xl border-2 border-[var(--accent)] bg-white p-5 shadow-[var(--shadow-card)]"
              >
                <div className="mb-3 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2.5">
                    <div
                      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl text-xs font-black text-white shadow-sm"
                      style={{ background: gradient }}
                      aria-hidden
                    >
                      {index + 1}
                    </div>
                    <p className="text-sm font-extrabold text-[var(--color-ink)]">
                      Editing {level.name}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={cancelEditing}
                      disabled={saving}
                      className="rounded-xl border border-[var(--color-line)] bg-white px-3.5 py-2 text-xs font-bold text-[var(--color-muted)] transition hover:bg-[#f4f7f5]"
                    >
                      Cancel
                    </button>
                    <button
                      type="submit"
                      disabled={saving}
                      className="rounded-xl bg-[var(--accent)] px-4 py-2 text-xs font-bold text-white shadow-sm transition hover:brightness-105 disabled:opacity-50"
                    >
                      {saving ? "Saving…" : "Save changes"}
                    </button>
                  </div>
                </div>

                <div className="grid gap-3 sm:grid-cols-12 sm:items-end">
                  <label className="block sm:col-span-3">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Level Name
                    </span>
                    <input
                      type="text"
                      maxLength={48}
                      value={editDraft.name}
                      onChange={(event) =>
                        setEditDraft((prev) => ({ ...prev, name: event.target.value }))
                      }
                      className="mt-1 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2 text-sm font-bold text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
                    />
                  </label>

                  <label className="block sm:col-span-2">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Entry ({currency})
                    </span>
                    <input
                      type="number"
                      min={1}
                      max={1_000_000}
                      step={1}
                      value={editDraft.entryFee}
                      onChange={(event) =>
                        setEditDraft((prev) => ({ ...prev, entryFee: event.target.value }))
                      }
                      className="mt-1 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2 text-sm font-bold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
                    />
                  </label>

                  <label className="block sm:col-span-2">
                    <span className="flex items-center justify-between text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      <span>2P Prize</span>
                      {editDraft.prizePool2p !== "" ? (
                        <button
                          type="button"
                          onClick={() =>
                            setEditDraft((prev) => ({ ...prev, prizePool2p: "" }))
                          }
                          className="text-[10px] font-semibold text-[var(--accent)] hover:underline"
                        >
                          Auto
                        </button>
                      ) : null}
                    </span>
                    <input
                      type="number"
                      min={0}
                      max={10_000_000}
                      step={1}
                      placeholder={`Auto: ${auto2p}`}
                      value={editDraft.prizePool2p}
                      disabled={!draftHas2p}
                      onChange={(event) =>
                        setEditDraft((prev) => ({ ...prev, prizePool2p: event.target.value }))
                      }
                      className="mt-1 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2 text-sm font-bold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2 disabled:opacity-45"
                    />
                  </label>

                  <label className="block sm:col-span-2">
                    <span className="flex items-center justify-between text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      <span>4P Prize</span>
                      {editDraft.prizePool4p !== "" ? (
                        <button
                          type="button"
                          onClick={() =>
                            setEditDraft((prev) => ({ ...prev, prizePool4p: "" }))
                          }
                          className="text-[10px] font-semibold text-[var(--accent)] hover:underline"
                        >
                          Auto
                        </button>
                      ) : null}
                    </span>
                    <input
                      type="number"
                      min={0}
                      max={10_000_000}
                      step={1}
                      placeholder={`Auto: ${auto4p}`}
                      value={editDraft.prizePool4p}
                      disabled={!draftHas4p}
                      onChange={(event) =>
                        setEditDraft((prev) => ({ ...prev, prizePool4p: event.target.value }))
                      }
                      className="mt-1 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2 text-sm font-bold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2 disabled:opacity-45"
                    />
                  </label>

                  <div className="sm:col-span-3">
                    <span className="block text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Modes
                    </span>
                    <div className="mt-1 flex gap-1.5">
                      {[2, 4].map((mode) => {
                        const active = editDraft.playerModes.includes(mode);
                        return (
                          <button
                            key={mode}
                            type="button"
                            onClick={() =>
                              setEditDraft((prev) => {
                                const has = prev.playerModes.includes(mode);
                                const nextModes = has
                                  ? prev.playerModes.filter((m) => m !== mode)
                                  : [...prev.playerModes, mode].sort((a, b) => a - b);
                                return { ...prev, playerModes: nextModes };
                              })
                            }
                            className={`flex-1 rounded-lg border px-2 py-2 text-xs font-bold transition ${
                              active
                                ? "border-[var(--accent)] bg-[var(--accent-soft)] text-[var(--accent)]"
                                : "border-[var(--color-line)] bg-white text-[var(--color-muted)]"
                            }`}
                          >
                            {mode}P
                          </button>
                        );
                      })}
                    </div>
                  </div>
                </div>
              </form>
            );
          }

          return (
            <div
              key={level.id}
              className={`rounded-2xl border bg-white p-4 shadow-[var(--shadow-card)] transition ${
                level.enabled
                  ? "border-[var(--color-line)]"
                  : "border-dashed border-slate-300 opacity-70"
              }`}
            >
              <div className="flex flex-col gap-4 lg:flex-row lg:items-center">
                {/* Color swatch + order */}
                <div className="flex items-center gap-3">
                  <div
                    className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl text-sm font-black text-white shadow-sm"
                    style={{ background: gradient }}
                    aria-hidden
                  >
                    {index + 1}
                  </div>
                  <div className="flex flex-col gap-1">
                    <button
                      type="button"
                      onClick={() => handleMoveLevel(index, -1)}
                      disabled={index === 0 || saving}
                      className="rounded-md border border-[var(--color-line)] px-2 py-0.5 text-[10px] font-bold text-[var(--color-muted)] hover:bg-[#f4f7f5] disabled:opacity-30"
                      aria-label={`Move ${level.name} up`}
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      onClick={() => handleMoveLevel(index, 1)}
                      disabled={index === levels.length - 1 || saving}
                      className="rounded-md border border-[var(--color-line)] px-2 py-0.5 text-[10px] font-bold text-[var(--color-muted)] hover:bg-[#f4f7f5] disabled:opacity-30"
                      aria-label={`Move ${level.name} down`}
                    >
                      ▼
                    </button>
                  </div>
                </div>

                {/* Read-only level summary */}
                <div className="grid flex-1 gap-3 sm:grid-cols-12 sm:items-center">
                  <div className="sm:col-span-3">
                    <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Level
                    </p>
                    <p className="mt-0.5 text-base font-extrabold text-[var(--color-ink)]">
                      {level.name}
                    </p>
                  </div>

                  <div className="sm:col-span-2">
                    <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Entry Fee
                    </p>
                    <p className="mt-0.5 text-sm font-bold tabular-nums text-[var(--color-ink)]">
                      {formatAmount(Number(level.entryFee), currency)}
                    </p>
                  </div>

                  <div className="sm:col-span-2">
                    <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      2P Prize Pool
                    </p>
                    <p
                      className={`mt-0.5 text-sm font-bold tabular-nums ${
                        has2p ? "text-[var(--color-ink)]" : "text-[var(--color-muted)] line-through"
                      }`}
                    >
                      {formatAmount(effective2p, currency)}
                      {level.prizePool2p !== "" ? (
                        <span className="ml-1.5 rounded bg-[var(--accent-soft)] px-1.5 py-0.5 text-[10px] font-bold text-[var(--accent)]">
                          Custom
                        </span>
                      ) : null}
                    </p>
                  </div>

                  <div className="sm:col-span-2">
                    <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      4P Prize Pool
                    </p>
                    <p
                      className={`mt-0.5 text-sm font-bold tabular-nums ${
                        has4p ? "text-[var(--color-ink)]" : "text-[var(--color-muted)] line-through"
                      }`}
                    >
                      {formatAmount(effective4p, currency)}
                      {level.prizePool4p !== "" ? (
                        <span className="ml-1.5 rounded bg-[var(--accent-soft)] px-1.5 py-0.5 text-[10px] font-bold text-[var(--accent)]">
                          Custom
                        </span>
                      ) : null}
                    </p>
                  </div>

                  <div className="sm:col-span-3">
                    <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Modes
                    </p>
                    <div className="mt-1 flex gap-1.5">
                      {[2, 4].map((mode) => {
                        const active = level.playerModes.includes(mode);
                        return (
                          <span
                            key={mode}
                            className={`rounded-lg px-2.5 py-1 text-xs font-bold ${
                              active
                                ? "bg-[var(--accent-soft)] text-[var(--accent)]"
                                : "bg-slate-100 text-slate-400"
                            }`}
                          >
                            {mode}P
                          </span>
                        );
                      })}
                    </div>
                  </div>
                </div>

                {/* Actions: Edit, Active/Hidden, Delete */}
                <div className="flex items-center justify-end gap-2 border-t border-[var(--color-line)]/70 pt-3 lg:border-t-0 lg:pt-0">
                  <button
                    type="button"
                    onClick={() => startEditing(level)}
                    disabled={saving}
                    className="rounded-xl border border-[var(--accent)] bg-white px-3.5 py-2 text-xs font-bold text-[var(--accent)] transition hover:bg-[var(--accent-soft)] disabled:opacity-40"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    onClick={() => handleToggleEnabled(level)}
                    disabled={saving}
                    className={`rounded-xl px-3 py-2 text-xs font-bold transition disabled:opacity-40 ${
                      level.enabled
                        ? "bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200"
                        : "bg-slate-100 text-slate-600 ring-1 ring-slate-200"
                    }`}
                  >
                    {level.enabled ? "Active" : "Hidden"}
                  </button>
                  <button
                    type="button"
                    onClick={() => handleRemoveLevel(level)}
                    disabled={levels.length <= 1 || saving}
                    className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-bold text-rose-700 transition hover:bg-rose-100 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Delete
                  </button>
                </div>
              </div>
            </div>
          );
        })}
      </section>
    </div>
  );
});
