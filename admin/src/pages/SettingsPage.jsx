import { forwardRef, useCallback, useEffect, useImperativeHandle, useState } from "react";
import { fetchPlatformSettings, updatePlatformSettings } from "../api/client";
import { formatAmount } from "../utils/format";

export const SettingsPage = forwardRef(function SettingsPage({ currency = "INR" }, ref) {
  const [fee, setFee] = useState("10");
  const [savedFee, setSavedFee] = useState(10);
  const [updatedAt, setUpdatedAt] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  const applySettings = useCallback((settings) => {
    setFee(String(settings.platformFeePerPlayer ?? 10));
    setSavedFee(settings.platformFeePerPlayer ?? 10);
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
  const dirty = feeValid && parsedFee !== savedFee;

  const handleSave = async (event) => {
    event.preventDefault();
    if (!feeValid || saving) return;

    setSaving(true);
    setMessage("");
    setError("");

    try {
      const settings = await updatePlatformSettings({
        platformFeePerPlayer: parsedFee,
      });
      applySettings(settings);
      setMessage("Settings saved.");
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
    <div className="mx-auto max-w-2xl space-y-5 animate-fade-up">
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
            Example with entry {formatAmount(100, currency)} and fee {formatAmount(savedFee, currency)}:
            2 seats → winner gets {formatAmount(200 - savedFee * 2, currency)}; 4 seats → winner gets{" "}
            {formatAmount(400 - savedFee * 4, currency)}.
          </p>

          <label className="mt-6 block">
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

        <div className="flex flex-wrap items-center gap-3 px-1">
          <button
            type="submit"
            disabled={!dirty || !feeValid || saving}
            className="rounded-xl bg-[var(--accent)] px-5 py-2.5 text-sm font-bold text-white transition disabled:cursor-not-allowed disabled:opacity-40"
          >
            {saving ? "Saving…" : "Save settings"}
          </button>
          {updatedAt ? (
            <p className="text-xs text-[var(--color-muted)]">
              Last updated {new Date(updatedAt).toLocaleString("en-IN")}
            </p>
          ) : null}
        </div>
      </form>

      {message ? (
        <p className="rounded-xl border border-[var(--accent)]/20 bg-[var(--accent-soft)] px-4 py-3 text-sm text-[var(--accent)]">
          {message}
        </p>
      ) : null}
      {error ? (
        <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
          {error}
        </p>
      ) : null}
    </div>
  );
});
