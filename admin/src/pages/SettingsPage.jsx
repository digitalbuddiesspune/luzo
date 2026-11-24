import { forwardRef, useCallback, useEffect, useImperativeHandle, useState } from "react";
import { fetchPlatformSettings, updatePlatformSettings } from "../api/client";
import { formatAmount } from "../utils/format";

function normalizeLevelsForState(levels) {
  if (!Array.isArray(levels) || levels.length === 0) {
    return [];
  }
  return levels.map((level, index) => ({
    id: String(level.id || `level_${index + 1}`),
    name: String(level.name || `LEVEL ${index + 1}`),
    entryFee: String(level.entryFee ?? 100),
    playerModes: Array.isArray(level.playerModes) && level.playerModes.length > 0
      ? [...new Set(level.playerModes.map(Number).filter((m) => m === 2 || m === 4))].sort((a, b) => a - b)
      : [2, 4],
    enabled: level.enabled !== false,
  }));
}

function serializeLevels(levels) {
  return levels.map((level, index) => ({
    id: level.id,
    name: String(level.name || "").trim(),
    entryFee: Number(level.entryFee),
    playerModes: level.playerModes,
    enabled: level.enabled !== false,
    sortOrder: index + 1,
  }));
}

function validateLevels(levels) {
  if (!levels.length) return "At least one game level is required.";
  for (let i = 0; i < levels.length; i += 1) {
    const level = levels[i];
    const name = String(level.name || "").trim();
    if (!name || name.length > 48) {
      return `Level #${i + 1} needs a name (1–48 characters).`;
    }
    const fee = Number(level.entryFee);
    if (!Number.isInteger(fee) || fee < 1 || fee > 1_000_000) {
      return `Level "${name}" price must be a whole number from 1 to 1,000,000.`;
    }
    if (!Array.isArray(level.playerModes) || level.playerModes.length === 0) {
      return `Level "${name}" must enable 2 Player, 4 Player, or both.`;
    }
  }
  return "";
}

function prizePool(entryFee, players, platformFee) {
  const pot = entryFee * players;
  const rake = Math.min(Math.max(platformFee, 0) * players, pot);
  return Math.max(0, pot - rake);
}

export const SettingsPage = forwardRef(function SettingsPage({ currency = "INR" }, ref) {
  const [fee, setFee] = useState("10");
  const [savedFee, setSavedFee] = useState(10);
  const [levels, setLevels] = useState([]);
  const [savedLevelsJson, setSavedLevelsJson] = useState("[]");
  const [updatedAt, setUpdatedAt] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const applySettings = useCallback((settings) => {
    const nextFee = settings.platformFeePerPlayer ?? 10;
    const nextLevels = normalizeLevelsForState(settings.gameLevels);
    setFee(String(nextFee));
    setSavedFee(nextFee);
    setLevels(nextLevels);
    setSavedLevelsJson(JSON.stringify(serializeLevels(nextLevels)));
    setUpdatedAt(settings.updatedAt || null);
  }, []);

  const loadSettings = useCallback(async ({ silent = false } = {}) => {
    if (!silent) {
      setLoading(true);
    }
    setError("");
    try {
      const settings = await fetchPlatformSettings();
      applySettings(settings);
    } catch (loadError) {
      setError(loadError.message || "Failed to load settings.");
      throw loadError;
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, [applySettings]);

  useImperativeHandle(ref, () => ({
    refresh: () => loadSettings({ silent: true }),
  }), [loadSettings]);

  useEffect(() => {
    loadSettings().catch(() => {});
  }, [loadSettings]);

  const parsedFee = Number(fee);
  const feeValid = Number.isInteger(parsedFee) && parsedFee >= 0 && parsedFee <= 1_000_000;
  const levelsValidationError = validateLevels(levels);
  const levelsValid = !levelsValidationError;
  const currentLevelsJson = JSON.stringify(serializeLevels(levels));
  const dirty = (feeValid && parsedFee !== savedFee) || currentLevelsJson !== savedLevelsJson;

  const updateLevel = (index, patch) => {
    setLevels((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));
    setMessage("");
  };

  const toggleMode = (index, mode) => {
    setLevels((prev) =>
      prev.map((item, i) => {
        if (i !== index) return item;
        const has = item.playerModes.includes(mode);
        const nextModes = has
          ? item.playerModes.filter((m) => m !== mode)
          : [...item.playerModes, mode].sort((a, b) => a - b);
        return { ...item, playerModes: nextModes };
      }),
    );
    setMessage("");
  };

  const addLevel = () => {
    const nextNum = levels.length + 1;
    const lastFee = Number(levels[levels.length - 1]?.entryFee) || 100;
    setLevels((prev) => [
      ...prev,
      {
        id: `level_${Date.now().toString(36)}`,
        name: `LEVEL ${nextNum}`,
        entryFee: String(lastFee),
        playerModes: [2, 4],
        enabled: true,
      },
    ]);
    setMessage("");
  };

  const removeLevel = (index) => {
    if (levels.length <= 1) return;
    setLevels((prev) => prev.filter((_, i) => i !== index));
    setMessage("");
  };

  const moveLevel = (index, delta) => {
    const target = index + delta;
    if (target < 0 || target >= levels.length) return;
    setLevels((prev) => {
      const next = [...prev];
      const [moved] = next.splice(index, 1);
      next.splice(target, 0, moved);
      return next;
    });
    setMessage("");
  };

  const handleSave = async (event) => {
    event.preventDefault();
    if (!feeValid || !levelsValid || saving) return;

    setSaving(true);
    setMessage("");
    setError("");

    try {
      const settings = await updatePlatformSettings({
        platformFeePerPlayer: parsedFee,
        gameLevels: serializeLevels(levels),
      });
      applySettings(settings);
      setMessage("Settings and game levels saved.");
    } catch (saveError) {
      setError(saveError.message || "Failed to save settings.");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-[var(--color-line)] bg-white px-6 py-16 shadow-[var(--shadow-card)]">
        <div className="h-9 w-9 animate-spin rounded-full border-2 border-[var(--color-line)] border-t-[var(--accent)]" />
        <p className="text-sm font-medium text-[var(--color-muted)]">Loading settings…</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl space-y-5 animate-fade-up">
      <form className="space-y-5" onSubmit={handleSave}>
        <section className="rounded-2xl border border-[var(--color-line)] bg-white p-6 shadow-[var(--shadow-card)]">
          <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--accent)]">
            Monetization
          </p>
          <h2 className="mt-1 text-xl font-extrabold tracking-tight text-[var(--color-ink)]">
            Platform fee per seat
          </h2>
          <p className="mt-2 text-sm leading-relaxed text-[var(--color-muted)]">
            Cut this amount from every seat in the pot (real players and bots) before paying the winner.
            Example with entry {formatAmount(100, currency)} and fee {formatAmount(feeValid ? parsedFee : savedFee, currency)}:
            2 seats → winner gets {formatAmount(prizePool(100, 2, feeValid ? parsedFee : savedFee), currency)}; 4 seats → winner gets{" "}
            {formatAmount(prizePool(100, 4, feeValid ? parsedFee : savedFee), currency)}.
          </p>

          <label className="mt-6 block max-w-xs">
            <span className="text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
              Fee amount ({currency})
            </span>
            <input
              type="number"
              min={0}
              max={1_000_000}
              step={1}
              value={fee}
              onChange={(event) => setFee(event.target.value)}
              className="mt-2 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-4 py-3 text-base font-semibold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          {!feeValid ? (
            <p className="mt-2 text-sm text-rose-600">Enter a whole number from 0 to 1,000,000.</p>
          ) : null}
        </section>

        <section className="rounded-2xl border border-[var(--color-line)] bg-white p-6 shadow-[var(--shadow-card)]">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--accent)]">
                Game Modes
              </p>
              <h2 className="mt-1 text-xl font-extrabold tracking-tight text-[var(--color-ink)]">
                Game levels &amp; entry prices
              </h2>
              <p className="mt-1 text-sm leading-relaxed text-[var(--color-muted)]">
                Create and price levels for 2-Player and 4-Player online modes. Players pick a level before entering matchmaking.
              </p>
            </div>
            <button
              type="button"
              onClick={addLevel}
              disabled={levels.length >= 50}
              className="rounded-xl border border-[var(--accent)] bg-[var(--accent-soft)] px-4 py-2 text-xs font-bold text-[var(--accent)] transition hover:bg-[var(--accent)] hover:text-white disabled:opacity-50"
            >
              + Add level
            </button>
          </div>

          <div className="mt-5 space-y-3">
            {levels.map((level, index) => {
              const entryNum = Number(level.entryFee);
              const validEntry = Number.isInteger(entryNum) && entryNum > 0;
              const effectiveFee = feeValid ? parsedFee : savedFee;
              return (
                <div
                  key={level.id}
                  className={`rounded-xl border p-4 transition ${
                    level.enabled
                      ? "border-[var(--color-line)] bg-[#f9fbfa]"
                      : "border-dashed border-[var(--color-line)] bg-[#f4f6f5] opacity-75"
                  }`}
                >
                  <div className="grid grid-cols-1 gap-3 md:grid-cols-12 md:items-end">
                    <label className="md:col-span-4">
                      <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                        Level name
                      </span>
                      <input
                        type="text"
                        maxLength={48}
                        value={level.name}
                        onChange={(e) => updateLevel(index, { name: e.target.value })}
                        placeholder="e.g. LEVEL 1"
                        className="mt-1 w-full rounded-lg border border-[var(--color-line)] bg-white px-3 py-2 text-sm font-semibold text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
                      />
                    </label>

                    <label className="md:col-span-3">
                      <span className="text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                        Entry price ({currency})
                      </span>
                      <input
                        type="number"
                        min={1}
                        max={1_000_000}
                        step={1}
                        value={level.entryFee}
                        onChange={(e) => updateLevel(index, { entryFee: e.target.value })}
                        className="mt-1 w-full rounded-lg border border-[var(--color-line)] bg-white px-3 py-2 text-sm font-semibold tabular-nums text-[var(--color-ink)] outline-none ring-[var(--accent)] focus:ring-2"
                      />
                    </label>

                    <div className="md:col-span-3">
                      <span className="block text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                        Game modes
                      </span>
                      <div className="mt-1.5 flex gap-2">
                        {[2, 4].map((mode) => {
                          const active = level.playerModes.includes(mode);
                          return (
                            <button
                              key={mode}
                              type="button"
                              onClick={() => toggleMode(index, mode)}
                              className={`rounded-lg border px-3 py-1.5 text-xs font-bold transition ${
                                active
                                  ? "border-[var(--accent)] bg-[var(--accent)] text-white"
                                  : "border-[var(--color-line)] bg-white text-[var(--color-muted)] hover:text-[var(--color-ink)]"
                              }`}
                            >
                              {mode}P
                            </button>
                          );
                        })}
                        <button
                          type="button"
                          onClick={() => updateLevel(index, { enabled: !level.enabled })}
                          className={`rounded-lg border px-2.5 py-1.5 text-xs font-bold transition ${
                            level.enabled
                              ? "border-emerald-200 bg-emerald-50 text-emerald-700"
                              : "border-slate-200 bg-slate-100 text-slate-500"
                          }`}
                        >
                          {level.enabled ? "Active" : "Off"}
                        </button>
                      </div>
                    </div>

                    <div className="flex items-center justify-end gap-1.5 md:col-span-2">
                      <button
                        type="button"
                        onClick={() => moveLevel(index, -1)}
                        disabled={index === 0}
                        title="Move up"
                        className="rounded-lg border border-[var(--color-line)] bg-white px-2 py-1.5 text-xs font-bold text-[var(--color-ink)] disabled:opacity-35"
                      >
                        ↑
                      </button>
                      <button
                        type="button"
                        onClick={() => moveLevel(index, 1)}
                        disabled={index === levels.length - 1}
                        title="Move down"
                        className="rounded-lg border border-[var(--color-line)] bg-white px-2 py-1.5 text-xs font-bold text-[var(--color-ink)] disabled:opacity-35"
                      >
                        ↓
                      </button>
                      <button
                        type="button"
                        onClick={() => removeLevel(index)}
                        disabled={levels.length <= 1}
                        title="Delete level"
                        className="rounded-lg border border-rose-200 bg-rose-50 px-2.5 py-1.5 text-xs font-bold text-rose-700 disabled:opacity-35"
                      >
                        Delete
                      </button>
                    </div>
                  </div>

                  {validEntry ? (
                    <div className="mt-2.5 flex flex-wrap gap-x-5 gap-y-1 text-xs text-[var(--color-muted)]">
                      {level.playerModes.includes(2) ? (
                        <span>
                          2P Prize:{" "}
                          <strong className="text-[var(--color-ink)]">
                            {formatAmount(prizePool(entryNum, 2, effectiveFee), currency)}
                          </strong>
                        </span>
                      ) : null}
                      {level.playerModes.includes(4) ? (
                        <span>
                          4P Prize:{" "}
                          <strong className="text-[var(--color-ink)]">
                            {formatAmount(prizePool(entryNum, 4, effectiveFee), currency)}
                          </strong>
                        </span>
                      ) : null}
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>

          {levelsValidationError ? (
            <p className="mt-3 text-sm font-medium text-rose-600">{levelsValidationError}</p>
          ) : null}
        </section>

        <div className="flex flex-wrap items-center gap-3 px-1">
          <button
            type="submit"
            disabled={!dirty || !feeValid || !levelsValid || saving}
            className="rounded-xl bg-[var(--accent)] px-5 py-2.5 text-sm font-bold text-white shadow-sm transition hover:opacity-95 disabled:cursor-not-allowed disabled:opacity-45"
          >
            {saving ? "Saving…" : "Save changes"}
          </button>

          {updatedAt ? (
            <span className="text-xs text-[var(--color-muted)]">
              Last updated {new Date(updatedAt).toLocaleString()}
            </span>
          ) : null}
        </div>

        {message ? (
          <p className="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-800">
            {message}
          </p>
        ) : null}

        {error ? (
          <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">
            {error}
          </p>
        ) : null}
      </form>
    </div>
  );
});
