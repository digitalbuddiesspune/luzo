import { formatAmount } from "../utils/format";

export function PlatformsPage({
  operators,
  currency = "INR",
  selectedOperatorId,
  onSelectOperator,
  onOpenProfitLoss,
}) {
  const list = Array.isArray(operators) ? operators : [];

  return (
    <div className="space-y-6 animate-fade-up">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 className="text-[1.75rem] font-extrabold tracking-tight text-[var(--color-ink)]">
            Platforms
          </h2>
          <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-[var(--color-muted)]">
            Each partner / domain (operator) is tracked separately — users, games played, income, and
            attributed profit. Players from different platforms can still share the same match.
          </p>
        </div>
        <button
          type="button"
          onClick={() => onOpenProfitLoss("all")}
          className="inline-flex items-center justify-center gap-2 rounded-xl bg-[var(--accent)] px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all hover:bg-[var(--color-brand-700)]"
        >
          Open full P&amp;L
        </button>
      </div>

      {list.length === 0 ? (
        <div className="rounded-2xl border border-[var(--color-line)] bg-white px-6 py-14 text-center shadow-[var(--shadow-card)]">
          <p className="font-semibold text-[var(--color-ink)]">No platform traffic yet</p>
          <p className="mt-1 text-sm text-[var(--color-muted)]">
            Finished games with operator sessions will appear here as separate platforms.
          </p>
        </div>
      ) : (
        <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {list.map((item) => {
            const active = selectedOperatorId === item.operatorId;

            return (
              <button
                key={item.operatorId}
                type="button"
                onClick={() => onSelectOperator(item.operatorId)}
                className={`rounded-2xl border bg-white p-5 text-left shadow-[var(--shadow-card)] transition-all hover:-translate-y-0.5 hover:shadow-[var(--shadow-card-hover)] ${
                  active
                    ? "border-[var(--accent)] ring-2 ring-[var(--accent)]/20"
                    : "border-[var(--color-line)]"
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-[11px] font-bold uppercase tracking-[0.12em] text-[var(--color-muted)]">
                      Platform
                    </p>
                    <h3 className="mt-1 truncate text-lg font-extrabold tracking-tight text-[var(--color-ink)]">
                      {item.label}
                    </h3>
                    <p className="mt-0.5 truncate text-xs text-[var(--color-muted)]">
                      ID: {item.operatorId}
                    </p>
                  </div>
                  <span className="rounded-lg bg-[var(--accent-soft)] px-2 py-1 text-xs font-bold text-[var(--accent)]">
                    {item.uniqueUsers} users
                  </span>
                </div>

                <dl className="mt-4 grid grid-cols-2 gap-3">
                  <div className="rounded-xl bg-[#f4f7f5] px-3 py-2.5">
                    <dt className="text-[11px] font-semibold uppercase tracking-wide text-[var(--color-muted)]">
                      Games
                    </dt>
                    <dd className="mt-1 text-base font-bold tabular-nums text-[var(--color-ink)]">
                      {item.totalGames.toLocaleString("en-IN")}
                    </dd>
                  </div>
                  <div className="rounded-xl bg-[#f4f7f5] px-3 py-2.5">
                    <dt className="text-[11px] font-semibold uppercase tracking-wide text-[var(--color-muted)]">
                      Seats
                    </dt>
                    <dd className="mt-1 text-base font-bold tabular-nums text-[var(--color-ink)]">
                      {item.totalSeats.toLocaleString("en-IN")}
                    </dd>
                  </div>
                  <div className="rounded-xl bg-[#f4f7f5] px-3 py-2.5">
                    <dt className="text-[11px] font-semibold uppercase tracking-wide text-[var(--color-muted)]">
                      Income
                    </dt>
                    <dd className="mt-1 text-sm font-bold tabular-nums text-[var(--color-ink)]">
                      {formatAmount(item.totalRealIncome, currency)}
                    </dd>
                  </div>
                  <div className="rounded-xl bg-[var(--accent-soft)] px-3 py-2.5">
                    <dt className="text-[11px] font-semibold uppercase tracking-wide text-[var(--accent)]">
                      Profit
                    </dt>
                    <dd className="mt-1 text-sm font-bold tabular-nums text-[var(--accent)]">
                      {formatAmount(item.totalPlatformProfit, currency)}
                    </dd>
                  </div>
                </dl>

                <p className="mt-4 text-xs font-semibold text-[var(--accent)]">
                  View P&amp;L for this platform →
                </p>
              </button>
            );
          })}
        </section>
      )}
    </div>
  );
}
