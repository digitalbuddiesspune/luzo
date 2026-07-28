import { GamesTable } from "../components/GamesTable";
import { SummaryCards } from "../components/SummaryCards";
import { UsersTable } from "../components/UsersTable";
import {
  DATE_PRESETS,
  detectActiveDatePreset,
  resolveDatePresetRange,
} from "../utils/dates";

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
  dateFrom,
  dateTo,
  onDateFilterChange,
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
  const activeDatePreset = detectActiveDatePreset(dateFrom, dateTo);

  const handlePresetChange = (presetId) => {
    const range = resolveDatePresetRange(presetId);
    onDateFilterChange(range.from, range.to);
  };

  const handleDateFromChange = (event) => {
    const nextFrom = event.target.value;
    const nextTo = dateTo && nextFrom && nextFrom > dateTo ? nextFrom : dateTo;
    onDateFilterChange(nextFrom, nextTo);
  };

  const handleDateToChange = (event) => {
    const nextTo = event.target.value;
    const nextFrom = dateFrom && nextTo && dateFrom > nextTo ? nextTo : dateFrom;
    onDateFilterChange(nextFrom, nextTo);
  };

  const handleClearDates = () => {
    onDateFilterChange("", "");
  };

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h2 className="text-[1.75rem] font-extrabold tracking-tight text-[var(--color-ink)]">
          Profit & Loss
        </h2>
        <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-[var(--color-muted)]">
          Real user bets, platform fee, winner payout, and per-user profit/loss. Filter by partner
          platform or date range to see that slice.
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

        <div className="rounded-2xl bg-white p-3 shadow-[var(--shadow-card)] ring-1 ring-[var(--color-line)] sm:p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs font-bold uppercase tracking-[0.12em] text-[var(--color-muted)]">
                Date
              </span>
              {DATE_PRESETS.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => handlePresetChange(item.id)}
                  className={`rounded-xl px-3.5 py-2 text-sm font-semibold transition-all duration-200 ring-1 ${
                    activeDatePreset === item.id
                      ? "bg-[var(--color-ink)] text-white ring-[var(--color-ink)]"
                      : "bg-[#f8faf9] text-[var(--color-muted)] ring-[var(--color-line)] hover:text-[var(--color-ink)]"
                  }`}
                >
                  {item.label}
                </button>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-2 lg:justify-end">
              <label className="flex items-center gap-2 text-sm">
                <span className="font-semibold text-[var(--color-ink)]">From</span>
                <input
                  type="date"
                  value={dateFrom}
                  onChange={handleDateFromChange}
                  className="min-w-[9.5rem] rounded-xl border border-[var(--color-line)] bg-white px-3 py-2 text-sm font-medium text-[var(--color-ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/20"
                />
              </label>

              <label className="flex items-center gap-2 text-sm">
                <span className="font-semibold text-[var(--color-ink)]">To</span>
                <input
                  type="date"
                  value={dateTo}
                  min={dateFrom || undefined}
                  onChange={handleDateToChange}
                  className="min-w-[9.5rem] rounded-xl border border-[var(--color-line)] bg-white px-3 py-2 text-sm font-medium text-[var(--color-ink)] outline-none transition focus:border-[var(--accent)] focus:ring-2 focus:ring-[var(--accent)]/20"
                />
              </label>

              {(dateFrom || dateTo) ? (
                <button
                  type="button"
                  onClick={handleClearDates}
                  className="rounded-xl px-3 py-2 text-sm font-semibold text-[var(--color-muted)] ring-1 ring-[var(--color-line)] transition hover:bg-[#f4f7f5] hover:text-[var(--color-ink)]"
                >
                  Clear
                </button>
              ) : null}
            </div>
          </div>
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
