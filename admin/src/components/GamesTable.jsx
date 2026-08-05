import { formatAmount, formatDate, formatProfitLoss, profitLossClassName } from "../utils/format";

function Pagination({ pagination, onPageChange, itemLabel = "items" }) {
  if (!pagination || pagination.totalPages <= 1) {
    return null;
  }

  return (
    <div className="flex flex-col gap-3 border-t border-[var(--color-line)] bg-[#f8faf9] px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-sm text-[var(--color-muted)]">
        Page <span className="font-semibold text-[var(--color-ink)]">{pagination.page}</span> of{" "}
        {pagination.totalPages} · {pagination.totalItems} {itemLabel}
      </p>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={!pagination.hasPreviousPage}
          onClick={() => onPageChange(pagination.page - 1)}
          className="rounded-lg border border-[var(--color-line)] bg-white px-3.5 py-1.5 text-sm font-medium text-[var(--color-ink)] transition-colors hover:bg-[#f0f4f1] disabled:cursor-not-allowed disabled:opacity-40"
        >
          Previous
        </button>
        <button
          type="button"
          disabled={!pagination.hasNextPage}
          onClick={() => onPageChange(pagination.page + 1)}
          className="rounded-lg border border-[var(--color-line)] bg-white px-3.5 py-1.5 text-sm font-medium text-[var(--color-ink)] transition-colors hover:bg-[#f0f4f1] disabled:cursor-not-allowed disabled:opacity-40"
        >
          Next
        </button>
      </div>
    </div>
  );
}

export function GamesTable({ games, pagination, onPageChange, onSelectGame }) {
  return (
    <div className="overflow-hidden rounded-2xl border border-[var(--color-line)] bg-white shadow-[var(--shadow-card)]">
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-[var(--color-line)] bg-[#f4f7f5]">
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Game
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Platforms
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Players
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Entry Fee
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Real Income
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Winner Payout
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Platform Profit
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Completed
              </th>
            </tr>
          </thead>
          <tbody>
            {games.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-12 text-center text-[var(--color-muted)]">
                  No finished games found.
                </td>
              </tr>
            ) : (
              games.map((game) => (
                <tr
                  key={game.roundId}
                  className="cursor-pointer border-b border-[var(--color-line)]/60 last:border-0 transition-colors hover:bg-[#f7faf8]"
                  onClick={() => onSelectGame(game)}
                >
                  <td className="px-4 py-3.5">
                    <div className="font-semibold text-[var(--color-ink)]">
                      {game.roomCode || game.roundId.slice(-8)}
                    </div>
                    <div className="mt-0.5 text-xs text-[var(--color-muted)]">{game.mode || "—"}</div>
                  </td>
                  <td className="px-4 py-3.5">
                    <div className="flex max-w-[180px] flex-wrap gap-1">
                      {(game.operatorIds || []).length === 0 ? (
                        <span className="text-xs text-[var(--color-muted)]">—</span>
                      ) : (
                        (game.operatorIds || []).map((id) => (
                          <span
                            key={id}
                            className="rounded-md bg-[var(--accent-soft)] px-1.5 py-0.5 text-[11px] font-semibold text-[var(--accent)]"
                          >
                            {id === "guest" ? "Direct" : id}
                          </span>
                        ))
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3.5">
                    <span className="font-semibold text-[var(--color-ink)]">{game.playerCount}</span>
                    <span className="ml-1.5 inline-flex items-center gap-1 text-xs text-[var(--color-muted)]">
                      <span className="rounded-md bg-[#f0f4f1] px-1.5 py-0.5 font-medium text-[var(--color-ink)]">
                        {game.realPlayerCount} Real
                      </span>
                      <span className="rounded-md bg-[#f0f4f1] px-1.5 py-0.5 font-medium">
                        {game.botPlayerCount} Real
                      </span>
                    </span>
                  </td>
                  <td className="px-4 py-3.5 tabular-nums text-[var(--color-ink)]">
                    {formatAmount(game.entryFee, game.currency)}
                  </td>
                  <td className="px-4 py-3.5 tabular-nums text-[var(--color-ink)]">
                    {formatAmount(game.totalRealIncome, game.currency)}
                  </td>
                  <td className="px-4 py-3.5 tabular-nums text-[var(--color-ink)]">
                    {formatAmount(game.winnerPayout, game.currency)}
                  </td>
                  <td className={`px-4 py-3.5 font-semibold tabular-nums ${profitLossClassName(game.platformProfit)}`}>
                    {formatProfitLoss(game.platformProfit, game.currency)}
                  </td>
                  <td className="px-4 py-3.5 text-[var(--color-muted)]">{formatDate(game.completedAt)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <Pagination pagination={pagination} onPageChange={onPageChange} />
    </div>
  );
}
