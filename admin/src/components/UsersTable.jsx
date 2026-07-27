import { formatAmount, formatProfitLoss, profitLossClassName } from "../utils/format";

function Pagination({ pagination, onPageChange }) {
  if (!pagination || pagination.totalPages <= 1) {
    return null;
  }

  return (
    <div className="flex flex-col gap-3 border-t border-[var(--color-line)] bg-[#f8faf9] px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-sm text-[var(--color-muted)]">
        Page <span className="font-semibold text-[var(--color-ink)]">{pagination.page}</span> of{" "}
        {pagination.totalPages} · {pagination.totalItems} users
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

function initials(name) {
  if (!name) return "?";
  const parts = String(name).trim().split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
}

export function UsersTable({ users, pagination, onPageChange, currency = "INR" }) {
  return (
    <div className="overflow-hidden rounded-2xl border border-[var(--color-line)] bg-white shadow-[var(--shadow-card)]">
      <div className="overflow-x-auto">
        <table className="min-w-full text-sm">
          <thead>
            <tr className="border-b border-[var(--color-line)] bg-[#f4f7f5]">
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                User
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Platform
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Games
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                W / L
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Total Bet
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Total Win
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Platform Fee
              </th>
              <th className="px-4 py-3 text-left text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
                Profit / Loss
              </th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-12 text-center text-[var(--color-muted)]">
                  No real users found in finished games.
                </td>
              </tr>
            ) : (
              users.map((user) => (
                <tr
                  key={`${user.operatorId || "guest"}-${user.userId}`}
                  className="border-b border-[var(--color-line)]/60 last:border-0 transition-colors hover:bg-[#f7faf8]"
                >
                  <td className="px-4 py-3.5">
                    <div className="flex items-center gap-3">
                      <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-[var(--accent-soft)] text-[11px] font-bold text-[var(--accent)]">
                        {initials(user.displayName)}
                      </div>
                      <div className="min-w-0">
                        <div className="truncate font-semibold text-[var(--color-ink)]">{user.displayName}</div>
                        <div className="truncate text-xs text-[var(--color-muted)]">{user.userId}</div>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3.5">
                    <span className="rounded-md bg-[var(--accent-soft)] px-2 py-0.5 text-xs font-semibold text-[var(--accent)]">
                      {user.operatorLabel || user.operatorId || "Direct / Guest"}
                    </span>
                  </td>
                  <td className="px-4 py-3.5 tabular-nums">{user.gamesPlayed}</td>
                  <td className="px-4 py-3.5">
                    <span className="font-semibold text-[var(--accent)]">{user.wins}</span>
                    <span className="text-[var(--color-muted)]"> / </span>
                    <span className="font-medium text-rose-600">{user.losses}</span>
                  </td>
                  <td className="px-4 py-3.5 tabular-nums">{formatAmount(user.totalBet, currency)}</td>
                  <td className="px-4 py-3.5 tabular-nums">{formatAmount(user.totalWin, currency)}</td>
                  <td className="px-4 py-3.5 tabular-nums">{formatAmount(user.totalPlatformFee, currency)}</td>
                  <td className={`px-4 py-3.5 font-bold tabular-nums ${profitLossClassName(user.profitLoss)}`}>
                    {formatProfitLoss(user.profitLoss, currency)}
                  </td>
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
