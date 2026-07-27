import { formatAmount, formatDate } from "../utils/format";
import { SummaryCards } from "../components/SummaryCards";

function QuickStat({ label, value }) {
  return (
    <div className="rounded-xl bg-[#f4f7f5] px-3.5 py-3 ring-1 ring-[var(--color-line)]/80">
      <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--color-muted)]">{label}</p>
      <p className="mt-1.5 text-base font-bold tabular-nums text-[var(--color-ink)]">{value}</p>
    </div>
  );
}

function roomHue(code) {
  let hash = 0;
  const source = code || "game";
  for (let i = 0; i < source.length; i += 1) {
    hash = source.charCodeAt(i) + ((hash << 5) - hash);
  }
  return Math.abs(hash) % 360;
}

export function DashboardPage({
  summary,
  recentGames,
  operators = [],
  onOpenProfitLoss,
  onOpenPlatforms,
  onSelectOperator,
  onSelectGame,
}) {
  return (
    <div className="space-y-6 animate-fade-up">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 className="text-[1.75rem] font-extrabold tracking-tight text-[var(--color-ink)]">Dashboard</h2>
          <p className="mt-1.5 max-w-xl text-sm leading-relaxed text-[var(--color-muted)]">
            Overview of finished games, platform income, and payouts across partner domains.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={onOpenPlatforms}
            className="inline-flex items-center justify-center gap-2 rounded-xl border border-[var(--color-line)] bg-white px-4 py-2.5 text-sm font-semibold text-[var(--color-ink)] shadow-sm transition-all hover:bg-[#f4f7f5]"
          >
            Platforms
          </button>
          <button
            type="button"
            onClick={onOpenProfitLoss}
            className="inline-flex items-center justify-center gap-2 rounded-xl bg-[var(--accent)] px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition-all duration-200 hover:bg-[var(--color-brand-700)] hover:shadow-md active:scale-[0.98]"
          >
            Open Profit & Loss
            <svg className="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M5 12h14M13 6l6 6-6 6" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
      </div>

      <SummaryCards summary={summary} />

      {operators.length > 0 ? (
        <section className="rounded-2xl border border-[var(--color-line)] bg-white p-5 shadow-[var(--shadow-card)]">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h3 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
                By Platform
              </h3>
              <p className="mt-1 text-sm text-[var(--color-muted)]">
                Partner domains with users, games, and attributed profit
              </p>
            </div>
            <button
              type="button"
              onClick={onOpenPlatforms}
              className="rounded-lg px-2.5 py-1.5 text-sm font-semibold text-[var(--accent)] transition-colors hover:bg-[var(--accent-soft)]"
            >
              View all
            </button>
          </div>
          <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {operators.map((item) => (
              <button
                key={item.operatorId}
                type="button"
                onClick={() => onSelectOperator(item.operatorId)}
                className="rounded-xl bg-[#f4f7f5] px-3.5 py-3 text-left ring-1 ring-[var(--color-line)]/80 transition-colors hover:bg-[var(--accent-soft)]"
              >
                <p className="truncate text-sm font-bold text-[var(--color-ink)]">{item.label}</p>
                <p className="mt-1 text-xs text-[var(--color-muted)]">
                  {item.uniqueUsers} users · {item.totalGames} games
                </p>
                <p className="mt-2 text-sm font-bold tabular-nums text-[var(--accent)]">
                  {formatAmount(item.totalPlatformProfit, summary?.currency || "INR")}
                </p>
              </button>
            ))}
          </div>
        </section>
      ) : null}

      <section className="grid gap-4 lg:grid-cols-12">
        <div className="rounded-2xl border border-[var(--color-line)] bg-white p-5 shadow-[var(--shadow-card)] lg:col-span-4">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
              Quick Stats
            </h3>
          </div>
          <div className="mt-4 grid grid-cols-2 gap-3">
            <QuickStat
              label="Fee / seat"
              value={
                summary?.platformFeePerPlayer != null
                  ? formatAmount(summary.platformFeePerPlayer, summary.currency)
                  : "—"
              }
            />
            <QuickStat label="Currency" value={summary?.currency || "INR"} />
            <QuickStat
              label="Avg income"
              value={
                summary && summary.totalGames > 0
                  ? formatAmount(Math.round(summary.totalRealIncome / summary.totalGames), summary.currency)
                  : "—"
              }
            />
            <QuickStat
              label="Avg profit"
              value={
                summary && summary.totalGames > 0
                  ? formatAmount(
                      Math.round(summary.totalPlatformProfit / summary.totalGames),
                      summary.currency,
                    )
                  : "—"
              }
            />
          </div>
          <div className="mt-4 rounded-xl border border-dashed border-[var(--color-line)] bg-[var(--accent-soft)]/50 px-3.5 py-3">
            <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[var(--accent)]">
              Margin
            </p>
            <p className="mt-1 text-sm font-semibold text-[var(--color-ink)]">
              {summary && summary.totalRealIncome > 0
                ? `${((summary.totalPlatformProfit / summary.totalRealIncome) * 100).toFixed(1)}% of income kept`
                : "No income yet"}
            </p>
          </div>
        </div>

        <div className="rounded-2xl border border-[var(--color-line)] bg-white p-5 shadow-[var(--shadow-card)] lg:col-span-8">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h3 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
                Recent Games
              </h3>
              <p className="mt-1 text-sm text-[var(--color-muted)]">Latest finished matches</p>
            </div>
            <button
              type="button"
              onClick={onOpenProfitLoss}
              className="rounded-lg px-2.5 py-1.5 text-sm font-semibold text-[var(--accent)] transition-colors hover:bg-[var(--accent-soft)]"
            >
              View all
            </button>
          </div>

          <div className="mt-4 divide-y divide-[var(--color-line)]/70">
            {recentGames.length === 0 ? (
              <p className="py-10 text-center text-sm text-[var(--color-muted)]">No finished games yet.</p>
            ) : (
              recentGames.map((game) => {
                const code = game.roomCode || game.roundId.slice(-8);
                const hue = roomHue(code);

                return (
                  <button
                    key={game.roundId}
                    type="button"
                    onClick={() => onSelectGame(game)}
                    className="flex w-full items-center gap-3.5 py-3.5 text-left transition-colors first:pt-2 last:pb-1 hover:bg-[#f7faf8] -mx-2 px-2 rounded-xl"
                  >
                    <div
                      className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl text-xs font-bold text-white"
                      style={{
                        background: `linear-gradient(145deg, hsl(${hue} 42% 42%), hsl(${hue} 38% 28%))`,
                      }}
                    >
                      {code.slice(0, 2).toUpperCase()}
                    </div>
                    <div className="min-w-0 flex-1">
                      <p className="truncate font-semibold text-[var(--color-ink)]">{code}</p>
                      <p className="mt-0.5 truncate text-xs text-[var(--color-muted)]">
                        {game.realPlayerCount} real · {game.botPlayerCount} bot · {formatDate(game.completedAt)}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="text-sm font-bold tabular-nums text-[var(--accent)]">
                        {formatAmount(game.platformProfit, game.currency)}
                      </p>
                      <p className="text-[11px] text-[var(--color-muted)]">platform</p>
                    </div>
                  </button>
                );
              })
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
