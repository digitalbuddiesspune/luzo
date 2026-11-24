import { forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useState } from "react";
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

function computePrizePool(entryFee, players, platformFeePerPlayer) {
  const fee = Number(entryFee);
  if (!Number.isFinite(fee) || fee <= 0) return 0;
  return Math.max(0, fee * players - platformFeePerPlayer * players);
}

function makeSlug(name, fallbackIndex) {
  const base = String(name || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "");
  return base || `level_${fallbackIndex}`;
}

export const LevelsPage = forwardRef(function LevelsPage({ currency = "INR" }, ref) {
  const [levels, setLevels] = useState([]);
  const [savedSnapshot, setSavedSnapshot] = useState("[]");
  const [platformFee, setPlatformFee] = useState(10);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  // Quick-add form state
  const [newName, setNewName] = useState("");
  const [newEntryFee, setNewEntryFee] = useState("100");
  const [newModes, setNewModes] = useState([2, 4]);

  const applySettings = useCallback((settings) => {
    const incoming = Array.isArray(settings.gameLevels) ? settings.gameLevels : [];
    const normalized = incoming.map((lvl, idx) => ({
      id: lvl.id || `level_${idx + 1}`,
      name: lvl.name || `LEVEL ${idx + 1}`,
      entryFee: String(lvl.entryFee ?? 100),
      playerModes: Array.isArray(lvl.playerModes) && lvl.playerModes.length > 0 ? lvl.playerModes : [2, 4],
      enabled: lvl.enabled !== false,
    }));
    setLevels(normalized);
    setSavedSnapshot(JSON.stringify(normalized));
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

  const validationError = useMemo(() => {
    if (levels.length === 0) {
      return "At least one level is required.";
    }
    for (let i = 0; i < levels.length; i += 1) {
      const lvl = levels[i];
      if (!String(lvl.name || "").trim()) {
        return `Level #${i + 1} needs a name.`;
      }
      const fee = Number(lvl.entryFee);
      if (!Number.isInteger(fee) || fee < 1 || fee > 1_000_000) {
        return `"${lvl.name}" entry fee must be a whole number from 1 to 1,000,000.`;
      }
      if (!Array.isArray(lvl.playerModes) || lvl.playerModes.length === 0) {
        return `"${lvl.name}" must be enabled for 2 Player, 4 Player, or both.`;
      }
    }
    return "";
  }, [levels]);

  const dirty = JSON.stringify(levels) !== savedSnapshot;

  const updateLevelField = (index, patch) => {
    setMessage("");
    setLevels((prev) => prev.map((item, idx) => (idx === index ? { ...item, ...patch } : item)));
  };

  const toggleLevelMode = (index, mode) => {
    setMessage("");
    setLevels((prev) =>
      prev.map((item, idx) => {
        if (idx !== index) return item;
        const has = item.playerModes.includes(mode);
        const nextModes = has
          ? item.playerModes.filter((m) => m !== mode)
          : [...item.playerModes, mode].sort((a, b) => a - b);
        return { ...item, playerModes: nextModes };
      }),
    );
  };

  const moveLevel = (index, direction) => {
    const target = index + direction;
    if (target < 0 || target >= levels.length) return;
    setMessage("");
    setLevels((prev) => {
      const copy = [...prev];
      const [moved] = copy.splice(index, 1);
      copy.splice(target, 0, moved);
      return copy;
    });
  };

  const removeLevel = (index) => {
    setMessage("");
    setLevels((prev) => prev.filter((_, idx) => idx !== index));
  };

  const handleAddLevel = (event) => {
    event.preventDefault();
    const trimmedName = newName.trim() || `LEVEL ${levels.length + 1}`;
    const fee = Number(newEntryFee);
    if (!Number.isInteger(fee) || fee < 1 || fee > 1_000_000) {
      setError("New level entry fee must be a whole number from 1 to 1,000,000.");
      return;
    }
    if (newModes.length === 0) {
      setError("Select at least 2 Player or 4 Player for the new level.");
      return;
    }

    setError("");
    setMessage("");
    const id = `${makeSlug(trimmedName, levels.length + 1)}_${Date.now().toString(36).slice(-4)}`;
    setLevels((prev) => [
      ...prev,
      {
        id,
        name: trimmedName,
        entryFee: String(fee),
        playerModes: [...newModes].sort((a, b) => a - b),
        enabled: true,
      },
    ]);
    setNewName("");
    setNewEntryFee(String(fee));
  };

  const handleSave = async () => {
    if (validationError || saving) return;

    setSaving(true);
    setMessage("");
    setError("");
    try {
      const payload = levels.map((lvl, index) => ({
        id: lvl.id,
        name: String(lvl.name).trim(),
        entryFee: Number(lvl.entryFee),
        playerModes: lvl.playerModes,
        enabled: lvl.enabled !== false,
        sortOrder: index + 1,
      }));
      const settings = await updatePlatformSettings({ gameLevels: payload });
      applySettings(settings);
      setMessage("Game levels saved. Players will see the updated levels immediately.");
    } catch (saveError) {
      setError(saveError.message || "Failed to save game levels.");
    } finally {
      setSaving(false);
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

  return (
    <div className="mx-auto max-w-5xl space-y-6 animate-fade-up">
      {/* Header summary + save bar */}
      <section className="flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-[var(--color-line)] bg-white p-6 shadow-[var(--shadow-card)]">
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--accent)]">
            Matchmaking Tiers
          </p>
          <h2 className="mt-1 text-xl font-extrabold tracking-tight text-[var(--color-ink)]">
            Game Levels &amp; Entry Fees
          </h2>
          <p className="mt-1.5 text-sm text-[var(--color-muted)]">
            Configure the levels players choose from in 2-Player and 4-Player online matches. Prize
            pools automatically deduct the current platform fee of{" "}
            <strong className="text-[var(--color-ink)]">
              {formatAmount(platformFee, currency)}
            </strong>{" "}
            per seat.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          {updatedAt ? (
            <span className="text-xs text-[var(--color-muted)]">
              Saved {new Date(updatedAt).toLocaleString("en-IN")}
            </span>
          ) : null}
          <button
            type="button"
            onClick={handleSave}
            disabled={!dirty || Boolean(validationError) || saving}
            className="rounded-xl bg-[var(--accent)] px-5 py-2.5 text-sm font-bold text-white shadow-sm transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {saving ? "Saving…" : dirty ? "Save levels" : "All saved"}
          </button>
        </div>
      </section>

      {message ? (
        <p className="rounded-xl border border-[var(--accent)]/25 bg-[var(--accent-soft)] px-4 py-3 text-sm font-medium text-[var(--accent)]">
          {message}
        </p>
      ) : null}
      {error || validationError ? (
        <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700">
          {error || validationError}
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
          <label className="block sm:col-span-4">
            <span className="text-xs font-semibold text-[var(--color-muted)]">Level name</span>
            <input
              type="text"
              maxLength={48}
              placeholder={`LEVEL ${levels.length + 1}`}
              value={newName}
              onChange={(event) => setNewName(event.target.value)}
              className="mt-1.5 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3.5 py-2.5 text-sm font-semibold text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          <label className="block sm:col-span-3">
            <span className="text-xs font-semibold text-[var(--color-muted)]">
              Entry fee ({currency})
            </span>
            <input
              type="number"
              min={1}
              max={1_000_000}
              step={1}
              value={newEntryFee}
              onChange={(event) => setNewEntryFee(event.target.value)}
              className="mt-1.5 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3.5 py-2.5 text-sm font-semibold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          <div className="sm:col-span-3">
            <span className="block text-xs font-semibold text-[var(--color-muted)]">
              Player modes
            </span>
            <div className="mt-1.5 flex gap-2">
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
                    className={`flex-1 rounded-xl border px-3 py-2 text-xs font-bold transition ${
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

          <div className="sm:col-span-2">
            <button
              type="submit"
              className="w-full rounded-xl border border-[var(--accent)] bg-white px-4 py-2.5 text-sm font-bold text-[var(--accent)] transition hover:bg-[var(--accent-soft)]"
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
          const prize2p = computePrizePool(level.entryFee, 2, platformFee);
          const prize4p = computePrizePool(level.entryFee, 4, platformFee);
          const has2p = level.playerModes.includes(2);
          const has4p = level.playerModes.includes(4);

          return (
            <div
              key={level.id}
              className={`rounded-2xl border bg-white p-4 shadow-[var(--shadow-card)] transition ${
                level.enabled ? "border-[var(--color-line)]" : "border-dashed border-slate-300 opacity-70"
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
                      onClick={() => moveLevel(index, -1)}
                      disabled={index === 0}
                      className="rounded-md border border-[var(--color-line)] px-2 py-0.5 text-[10px] font-bold text-[var(--color-muted)] hover:bg-[#f4f7f5] disabled:opacity-30"
                      aria-label={`Move ${level.name} up`}
                    >
                      ▲
                    </button>
                    <button
                      type="button"
                      onClick={() => moveLevel(index, 1)}
                      disabled={index === levels.length - 1}
                      className="rounded-md border border-[var(--color-line)] px-2 py-0.5 text-[10px] font-bold text-[var(--color-muted)] hover:bg-[#f4f7f5] disabled:opacity-30"
                      aria-label={`Move ${level.name} down`}
                    >
                      ▼
                    </button>
                  </div>
                </div>

                {/* Editable fields */}
                <div className="grid flex-1 gap-3 sm:grid-cols-12 sm:items-center">
                  <label className="block sm:col-span-4">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Level Name
                    </span>
                    <input
                      type="text"
                      maxLength={48}
                      value={level.name}
                      onChange={(event) => updateLevelField(index, { name: event.target.value })}
                      className="mt-1 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2 text-sm font-bold text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
                    />
                  </label>

                  <label className="block sm:col-span-3">
                    <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Entry Fee ({currency})
                    </span>
                    <input
                      type="number"
                      min={1}
                      max={1_000_000}
                      step={1}
                      value={level.entryFee}
                      onChange={(event) => updateLevelField(index, { entryFee: event.target.value })}
                      className="mt-1 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-3 py-2 text-sm font-bold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
                    />
                  </label>

                  <div className="sm:col-span-2">
                    <span className="block text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Modes
                    </span>
                    <div className="mt-1 flex gap-1.5">
                      {[2, 4].map((mode) => {
                        const active = level.playerModes.includes(mode);
                        return (
                          <button
                            key={mode}
                            type="button"
                            onClick={() => toggleLevelMode(index, mode)}
                            className={`flex-1 rounded-lg border px-2 py-1.5 text-xs font-bold transition ${
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

                  <div className="sm:col-span-3">
                    <span className="block text-[10px] font-bold uppercase tracking-wider text-[var(--color-muted)]">
                      Prize Pool Preview
                    </span>
                    <div className="mt-1 space-y-0.5 text-xs font-semibold tabular-nums text-[var(--color-ink)]">
                      <p className={has2p ? "" : "text-[var(--color-muted)] line-through"}>
                        2P: {formatAmount(prize2p, currency)}
                      </p>
                      <p className={has4p ? "" : "text-[var(--color-muted)] line-through"}>
                        4P: {formatAmount(prize4p, currency)}
                      </p>
                    </div>
                  </div>
                </div>

                {/* Enable + Delete */}
                <div className="flex items-center justify-end gap-2 border-t border-[var(--color-line)]/70 pt-3 lg:border-t-0 lg:pt-0">
                  <button
                    type="button"
                    onClick={() => updateLevelField(index, { enabled: !level.enabled })}
                    className={`rounded-xl px-3 py-2 text-xs font-bold transition ${
                      level.enabled
                        ? "bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200"
                        : "bg-slate-100 text-slate-600 ring-1 ring-slate-200"
                    }`}
                  >
                    {level.enabled ? "Active" : "Hidden"}
                  </button>
                  <button
                    type="button"
                    onClick={() => removeLevel(index)}
                    disabled={levels.length <= 1}
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
