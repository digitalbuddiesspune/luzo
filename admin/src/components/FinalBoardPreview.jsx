import { AdminLudoBoard, PlayerTokenLegend } from "./AdminLudoBoard";
import { winnerReasonLabel } from "../utils/ludoBoard";

export function FinalBoardPreview({ game }) {
  const reason = winnerReasonLabel(game.winnerReason);
  const players = game.players || [];
  const hasAnyTokens = players.some(
    (player) => Array.isArray(player.tokens) && player.tokens.length > 0,
  );

  return (
    <div className="border-t border-[var(--color-line)] px-6 py-5">
      <h3 className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
        How it ended
      </h3>

      <div className="mt-3 rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-4 py-3">
        <p className="text-sm font-semibold text-[var(--color-ink)]">
          {reason ||
            (game.winner
              ? "Result recorded (reason unavailable for older games)"
              : "No winner recorded")}
        </p>
        {game.winner ? (
          <p className="mt-1 text-sm text-[var(--color-muted)]">
            Winner: {game.winner.displayName}
            {game.winner.isHouse ? " (Platform)" : game.winner.isBot ? " (Bot)" : ""}
          </p>
        ) : null}
      </div>

      {!hasAnyTokens ? (
        <p className="mt-4 text-sm text-[var(--color-muted)]">
          Final token positions were not saved for this match (older games before this feature).
        </p>
      ) : (
        <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,22rem)_1fr] lg:items-start">
          <AdminLudoBoard players={players} />
          <PlayerTokenLegend players={players} />
        </div>
      )}
    </div>
  );
}
