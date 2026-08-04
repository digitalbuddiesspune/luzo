import { formatAmount, formatDate, formatProfitLoss, profitLossClassName } from "../utils/format";
import { FinalBoardPreview } from "./FinalBoardPreview";

export function GameDetailModal({ game, onClose }) {
  if (!game) {
    return null;
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#0f2a20]/50 p-4 backdrop-blur-[3px] animate-fade-in"
      onClick={onClose}
      onKeyDown={(event) => {
        if (event.key === "Escape") onClose();
      }}
      role="presentation"
    >
      <div
        className="max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-2xl bg-white shadow-2xl animate-modal-in"
        onClick={(event) => event.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-labelledby="game-detail-title"
      >
        <div className="sticky top-0 z-10 flex items-start justify-between gap-4 border-b border-[var(--color-line)] bg-white/95 px-6 py-4 backdrop-blur">
          <div>
            <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--accent)]">Game detail</p>
            <h2 id="game-detail-title" className="mt-1 text-xl font-extrabold tracking-tight text-[var(--color-ink)]">
              Profit / Loss
            </h2>
            <p className="mt-1 text-sm text-[var(--color-muted)]">
              {game.roomCode || game.roundId} · {formatDate(game.completedAt)}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-[var(--color-line)] bg-white px-3 py-1.5 text-sm font-medium text-[var(--color-ink)] transition-colors hover:bg-[#f4f7f5]"
            >
              Close
            </button>
          </div>
        </div>

        <div className="grid gap-3 px-6 py-5 sm:grid-cols-2 xl:grid-cols-4">
          <Metric label="Players" value={`${game.realPlayerCount} real · ${game.botPlayerCount} bot`} />
          <Metric label="Entry Fee" value={formatAmount(game.entryFee, game.currency)} />
          <Metric label="Total Real Income" value={formatAmount(game.totalRealIncome, game.currency)} />
          <Metric
            label="Platform Profit"
            value={formatProfitLoss(game.platformProfit, game.currency)}
            valueClassName={profitLossClassName(game.platformProfit)}
            accent
          />
          <Metric label="Display Pot" value={formatAmount(game.displayPotAmount, game.currency)} />
          <Metric label="Winner Payout" value={formatAmount(game.winnerPayout, game.currency)} />
          <Metric
            label="Winner"
            value={
              game.winner
                ? `${game.winner.displayName}${
                    game.winner.isHouse
                      ? " (Platform)"
                      : game.winner.isReal
                        ? ""
                        : " (Bot)"
                  }`
                : "—"
            }
          />
          <Metric label="Mode" value={game.mode || "—"} />
        </div>

        <FinalBoardPreview game={game} />

        <div className="border-t border-[var(--color-line)] px-6 py-5">
          <h3 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
            Player Breakdown
          </h3>
          <div className="mt-4 overflow-hidden rounded-xl border border-[var(--color-line)]">
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead>
                  <tr className="border-b border-[var(--color-line)] bg-[#f4f7f5]">
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Player
                    </th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Platform
                    </th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Type
                    </th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Bet
                    </th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Platform Fee
                    </th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Win
                    </th>
                    <th className="px-3 py-2.5 text-left text-[11px] font-bold uppercase tracking-[0.08em] text-[var(--color-muted)]">
                      Profit / Loss
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {game.players.map((player) => (
                    <tr
                      key={player.userId}
                      className="border-b border-[var(--color-line)]/60 last:border-0"
                    >
                      <td className="px-3 py-2.5">
                        <div className="font-semibold text-[var(--color-ink)]">{player.displayName}</div>
                        <div className="text-xs text-[var(--color-muted)]">{player.userId}</div>
                      </td>
                      <td className="px-3 py-2.5">
                        {player.isBot ? (
                          <span className="text-xs text-[var(--color-muted)]">—</span>
                        ) : (
                          <span className="rounded-md bg-[var(--accent-soft)] px-2 py-0.5 text-xs font-semibold text-[var(--accent)]">
                            {player.operatorLabel || player.operatorId || "Direct / Guest"}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2.5">
                        <span
                          className={`inline-flex rounded-md px-2 py-0.5 text-xs font-semibold ${
                            player.isBot
                              ? "bg-[#f0f4f1] text-[var(--color-muted)]"
                              : player.isAbandoned
                                ? "bg-[#fff1f0] text-[#c0392b]"
                                : "bg-[var(--accent-soft)] text-[var(--accent)]"
                          }`}
                        >
                          {player.isBot ? "Bot" : player.isAbandoned ? "Left" : "Real"}
                        </span>
                      </td>
                      <td className="px-3 py-2.5 tabular-nums">
                        {formatAmount(player.betAmount, game.currency)}
                      </td>
                      <td className="px-3 py-2.5 tabular-nums">
                        {formatAmount(player.platformFee, game.currency)}
                      </td>
                      <td className="px-3 py-2.5 tabular-nums">
                        {formatAmount(player.winAmount, game.currency)}
                      </td>
                      <td className={`px-3 py-2.5 font-bold tabular-nums ${profitLossClassName(player.profitLoss)}`}>
                        {formatProfitLoss(player.profitLoss, game.currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="mt-5 rounded-xl border border-[var(--color-line)] bg-[#f4f7f5] p-4 text-sm leading-relaxed text-[var(--color-muted)]">
            <p>
              Example: 2 real players × {formatAmount(game.entryFee, game.currency)} bet ={" "}
              {formatAmount(game.entryFee * 2, game.currency)} total income. Platform fee is deducted per
              player before payout. Winner receives total real income minus platform profit.
            </p>
            <p className="mt-2">
              Platform profit = total real income ({formatAmount(game.totalRealIncome, game.currency)}) −
              winner payout ({formatAmount(game.winnerPayout, game.currency)}) ={" "}
              <span className="font-semibold text-[var(--color-ink)]">
                {formatAmount(game.platformProfit, game.currency)}
              </span>
              {game.winner?.isHouse
                ? ". When only bots remain after all real players leave, the platform keeps the full real pot."
                : "."}
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}

function Metric({ label, value, valueClassName = "text-[var(--color-ink)]", accent = false }) {
  return (
    <div
      className={`rounded-xl border p-4 ${
        accent
          ? "border-[var(--accent)]/25 bg-[var(--accent-soft)]"
          : "border-[var(--color-line)] bg-[#f8faf9]"
      }`}
    >
      <p className="text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">{label}</p>
      <p className={`mt-2 text-base font-bold tracking-tight ${valueClassName}`}>{value}</p>
    </div>
  );
}
