import { TokenPin } from "./TokenPin";
import {
  colorHex,
  getCellMeta,
  resolveTokenCell,
  tokenProgressLabel,
} from "../utils/ludoBoard";
import "../styles/admin-ludo-board.css";

const COLORS = ["red", "green", "yellow", "blue"];
const FINISHED_PROGRESS = 56;

const HOME_FINISH_ZONE = {
  red: { left: 44.5, top: 50 },
  green: { left: 50, top: 44.5 },
  yellow: { left: 55.5, top: 50 },
  blue: { left: 50, top: 55.5 },
};

const HOME_FINISH_LAYOUTS = {
  red: {
    1: [{ x: 0, y: 0 }],
    2: [
      { x: 0.3, y: -2.4 },
      { x: 0.3, y: 2.4 },
    ],
    3: [
      { x: -0.6, y: -2.5 },
      { x: -0.6, y: 2.5 },
      { x: 1.6, y: 0 },
    ],
    4: [
      { x: -0.8, y: -2.3 },
      { x: -0.8, y: 2.3 },
      { x: 1.7, y: -1.2 },
      { x: 1.7, y: 1.2 },
    ],
  },
  green: {
    1: [{ x: 0, y: 0 }],
    2: [
      { x: -2.4, y: 0.3 },
      { x: 2.4, y: 0.3 },
    ],
    3: [
      { x: -2.5, y: -0.6 },
      { x: 2.5, y: -0.6 },
      { x: 0, y: 1.6 },
    ],
    4: [
      { x: -2.3, y: -0.8 },
      { x: 2.3, y: -0.8 },
      { x: -1.2, y: 1.7 },
      { x: 1.2, y: 1.7 },
    ],
  },
  yellow: {
    1: [{ x: 0, y: 0 }],
    2: [
      { x: -0.3, y: -2.4 },
      { x: -0.3, y: 2.4 },
    ],
    3: [
      { x: 0.6, y: -2.5 },
      { x: 0.6, y: 2.5 },
      { x: -1.6, y: 0 },
    ],
    4: [
      { x: 0.8, y: -2.3 },
      { x: 0.8, y: 2.3 },
      { x: -1.7, y: -1.2 },
      { x: -1.7, y: 1.2 },
    ],
  },
  blue: {
    1: [{ x: 0, y: 0 }],
    2: [
      { x: -2.4, y: -0.3 },
      { x: 2.4, y: -0.3 },
    ],
    3: [
      { x: -2.5, y: 0.6 },
      { x: 2.5, y: 0.6 },
      { x: 0, y: -1.6 },
    ],
    4: [
      { x: -2.3, y: 0.8 },
      { x: 2.3, y: 0.8 },
      { x: -1.2, y: -1.7 },
      { x: 1.2, y: -1.7 },
    ],
  },
};

function getHomeFinishCenterPercent(color, finishSlot, finishCount) {
  const zone = HOME_FINISH_ZONE[color] ?? HOME_FINISH_ZONE.red;
  const count = Math.min(Math.max(finishCount || 1, 1), 4);
  const slot = Math.min(Math.max(finishSlot || 0, 0), count - 1);
  const layouts = HOME_FINISH_LAYOUTS[color] ?? HOME_FINISH_LAYOUTS.red;
  const fan = layouts[count][slot] ?? { x: 0, y: 0 };
  return {
    left: zone.left + fan.x,
    top: zone.top + fan.y,
  };
}

function buildBoardTokenState(players) {
  const yardTokenMap = {
    red: [null, null, null, null],
    green: [null, null, null, null],
    yellow: [null, null, null, null],
    blue: [null, null, null, null],
  };
  const boardEntries = new Map();
  const finishTokens = [];

  for (const player of players || []) {
    const color = String(player.color || "").toLowerCase();
    if (!COLORS.includes(color)) continue;
    const tokens = Array.isArray(player.tokens) ? player.tokens : [];

    tokens.forEach((progress, tokenIndex) => {
      const token = {
        id: `${player.userId}-${tokenIndex}`,
        color,
        tokenIndex,
        progress: Number(progress),
        playerName: player.displayName,
        isAbandoned: Boolean(player.isAbandoned),
      };

      if (token.progress < 0) {
        yardTokenMap[color][tokenIndex] = token;
        return;
      }

      if (token.progress >= FINISHED_PROGRESS) {
        finishTokens.push(token);
        return;
      }

      const cell = resolveTokenCell(color, token.progress, tokenIndex);
      if (!cell) return;
      const key = `${cell[0]}-${cell[1]}`;
      const list = boardEntries.get(key) || [];
      list.push(token);
      boardEntries.set(key, list);
    });
  }

  return { yardTokenMap, boardEntries, finishTokens };
}

function StarIcon() {
  return (
    <svg className="cell-star" viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="none"
        stroke="#424242"
        strokeWidth="1.2"
        strokeLinejoin="round"
        d="M12 3.2l2.05 5.15 5.55.4-4.25 3.7 1.3 5.35L12 15.2l-4.65 2.6 1.3-5.35-4.25-3.7 5.55-.4z"
      />
    </svg>
  );
}

function ArrowIcon({ color }) {
  return (
    <svg className={`cell-arrow arrow-${color}`} viewBox="0 0 24 24" aria-hidden="true">
      <path fill={colorHex(color)} d="M12.75 20h-1.5v-9.5H8L12 4l4.001 6.5H12.75z" />
    </svg>
  );
}

function CenterDiamond() {
  return (
    <svg className="board-center-diamond" viewBox="0 0 30 30" aria-hidden="true">
      <polygon points="15,15 0,0 30,0" fill="#43a047" />
      <polygon points="15,15 30,0 30,30" fill="#fbc02d" />
      <polygon points="15,15 30,30 0,30" fill="#1e88e5" />
      <polygon points="15,15 0,30 0,0" fill="#e53935" />
    </svg>
  );
}

function TokenMarker({ token, stackIndex = 0, stackCount = 1 }) {
  const stackClass =
    stackCount > 1 ? `stack-${Math.min(stackCount, 4)} stack-i-${stackIndex}` : "";

  return (
    <span
      className={`token-marker ${stackClass}`.trim()}
      style={{ "--token-opacity": token.isAbandoned ? 0.55 : 1 }}
      title={`${token.playerName} · T${token.tokenIndex + 1} · ${tokenProgressLabel(token.progress)}`}
    >
      <TokenPin color={token.color} className="token-pin" />
      <span className="token-index-badge">{token.tokenIndex + 1}</span>
    </span>
  );
}

export function AdminLudoBoard({ players }) {
  const { yardTokenMap, boardEntries, finishTokens } = buildBoardTokenState(players);
  const finishCountByColor = finishTokens.reduce((counts, token) => {
    counts[token.color] = (counts[token.color] || 0) + 1;
    return counts;
  }, {});
  const finishSlotByColor = { red: 0, green: 0, yellow: 0, blue: 0 };

  return (
    <div className="admin-ludo-preview">
      <div className="ludo-board-frame">
        <div className="ludo-board-surface" aria-label="Final Ludo board">
          {COLORS.map((color) => (
            <div key={color} className={`board-nest nest-${color}`}>
              <div className="board-nest-pad">
                {Array.from({ length: 4 }, (_, slotIndex) => {
                  const token = yardTokenMap[color][slotIndex];
                  return (
                    <div key={slotIndex} className={`house-slot house-slot-${slotIndex}`}>
                      {token ? <TokenMarker token={token} /> : null}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}

          <CenterDiamond />

          <div className="board-grid">
            {Array.from({ length: 15 * 15 }, (_, index) => {
              const row = Math.floor(index / 15);
              const col = index % 15;
              const meta = getCellMeta(row, col);
              const tokens = boardEntries.get(meta.key) || [];
              const classNames = ["board-cell"];

              if (meta.isCenter) classNames.push("is-center");
              if (meta.isPath && !meta.homeLane && !meta.startColor) classNames.push("is-path");
              if (meta.homeLane) classNames.push(`lane-${meta.homeLane}`);
              if (meta.startColor) classNames.push(`is-start-${meta.startColor}`);

              return (
                <div key={meta.key} className={classNames.join(" ")}>
                  {meta.isStar ? <StarIcon /> : null}
                  {meta.arrowColor ? <ArrowIcon color={meta.arrowColor} /> : null}
                  {tokens.map((token, stackIndex) => (
                    <TokenMarker
                      key={token.id}
                      token={token}
                      stackIndex={stackIndex}
                      stackCount={tokens.length}
                    />
                  ))}
                </div>
              );
            })}
          </div>

          {finishTokens.map((token) => {
            const finishCount = finishCountByColor[token.color] || 1;
            const finishSlot = finishSlotByColor[token.color] || 0;
            finishSlotByColor[token.color] = finishSlot + 1;
            const position = getHomeFinishCenterPercent(token.color, finishSlot, finishCount);
            return (
              <span
                key={`finish-${token.id}`}
                className="board-finish-token-slot"
                style={{ left: `${position.left}%`, top: `${position.top}%` }}
              >
                <TokenMarker token={token} />
              </span>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export function PlayerTokenLegend({ players }) {
  return (
    <div className="space-y-3">
      {(players || []).map((player) => (
        <div key={player.userId} className="rounded-xl border border-[var(--color-line)] px-3 py-2.5">
          <div className="flex flex-wrap items-center gap-2">
            <span
              className="inline-block h-2.5 w-2.5 rounded-full"
              style={{ backgroundColor: colorHex(player.color) }}
            />
            <span className="text-sm font-semibold text-[var(--color-ink)]">{player.displayName}</span>
            {player.isAbandoned || player.isWinner ? (
              <span className="text-xs text-[var(--color-muted)]">
                {[player.isAbandoned ? "Left" : null, player.isWinner ? "Winner" : null]
                  .filter(Boolean)
                  .join(" · ")}
              </span>
            ) : null}
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {(player.tokens || []).length === 0 ? (
              <span className="text-xs text-[var(--color-muted)]">No positions saved</span>
            ) : (
              (player.tokens || []).map((progress, index) => (
                <span
                  key={`${player.userId}-${index}`}
                  className="rounded-md bg-[#f4f7f5] px-2 py-0.5 text-xs font-medium text-[var(--color-ink)]"
                >
                  T{index + 1}: {tokenProgressLabel(progress)}
                </span>
              ))
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
