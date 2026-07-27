import { GamesTable } from "../components/GamesTable";
import { SummaryCards } from "../components/SummaryCards";
import { UsersTable } from "../components/UsersTable";

const SECTIONS = [
  { id: "overview", label: "Overview" },
  { id: "games", label: "Games" },
  { id: "users", label: "Users" },
];

const PLAYER_FILTERS = [
  { id: "all", label: "All Games" },
  { id: 2, label: "2 Player" },
  { id: 4, label: "4 Player" },
];

export function ProfitLossPage({
  section,
  onSectionChange,
  playerFilter,
  onPlayerFilterChange,
  operatorFilter,
  onOperatorFilterChange,
  operators,
  summary,
  games,
  gamesPagination,
  onGamesPageChange,
  users,
  usersPagination,
  onUsersPageChange,
  onSelectGame,
}) {
  const operatorOptions = [
    { id: "all", label: "All Platforms" },
    ...(Array.isArray(operators)
      ? operators.map((item) => ({ id: item.operatorId, label: item.label }))
      : []),
  ];

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h2 className="text-[1.75rem] font-extrabold tracking-tight text-[var(--color-ink)]">
          Profit & Loss
        </h2>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-[var(--color-muted)]">
          Real user bets, platform fee, winner payout, and per-user profit/loss. Filter by partner
          platform to see that domain&apos;s slice.
        </p>
      </div>

      <div className="flex flex-col gap-3">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="inline-flex flex-wrap gap-1 rounded-xl bg-white p-1 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)]">
            {SECTIONS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => onSectionChange(item.id)}
                className={`rounded-lg px-4 py-2 text-sm font-semibold transition-all duration-200 ${
                  section === item.id
                    ? "bg-[var(--accent)] text-white shadow-sm"
                    : "text-[var(--color-muted)] hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>

          <div className="inline-flex flex-wrap gap-1 rounded-xl bg-white p-1 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)]">
            {PLAYER_FILTERS.map((item) => (
              <button
                key={String(item.id)}
                type="button"
                onClick={() => onPlayerFilterChange(item.id)}
                className={`rounded-lg px-3.5 py-2 text-sm font-semibold transition-all duration-200 ${
                  playerFilter === item.id
                    ? "bg-[var(--color-ink)] text-white shadow-sm"
                    : "text-[var(--color-muted)] hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
        </div>

        <div className="flex flex-wrap gap-2">
          {operatorOptions.map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => onOperatorFilterChange(item.id)}
              className={`rounded-xl px-3.5 py-2 text-sm font-semibold transition-all duration-200 ring-1 ${
                operatorFilter === item.id
                  ? "bg-[var(--accent-soft)] text-[var(--accent)] ring-[var(--accent)]/30"
                  : "bg-white text-[var(--color-muted)] ring-[var(--color-line)] hover:text-[var(--color-ink)]"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
      </div>

      {section === "overview" ? (
        <>
          <SummaryCards summary={summary} />
          <section className="space-y-3">
            <div className="flex items-end justify-between gap-3">
              <div>
                <h3 className="text-lg font-bold tracking-tight text-[var(--color-ink)]">Recent Games</h3>
                <p className="mt-0.5 text-sm text-[var(--color-muted)]">
                  Click a game to see player-level profit and loss by platform.
                </p>
              </div>
            </div>
            <GamesTable
              games={games.slice(0, 10)}
              pagination={null}
              onPageChange={onGamesPageChange}
              onSelectGame={onSelectGame}
            />
          </section>
        </>
      ) : null}

      {section === "games" ? (
        <GamesTable
          games={games}
          pagination={gamesPagination}
          onPageChange={onGamesPageChange}
          onSelectGame={onSelectGame}
        />
      ) : null}

      {section === "users" ? (
        <UsersTable
          users={users}
          pagination={usersPagination}
          onPageChange={onUsersPageChange}
          currency={summary?.currency || "INR"}
        />
      ) : null}
    </div>
  );
}
