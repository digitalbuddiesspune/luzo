import { formatAmount, profitLossClassName } from "../utils/format";

function StatIcon({ type }) {
  const className = "h-4 w-4";
  if (type === "games") {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <rect x="3" y="3" width="7" height="7" rx="1.5" />
        <rect x="14" y="3" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
      </svg>
    );
  }
  if (type === "income") {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M12 3v18M7 8h7.5a2.5 2.5 0 010 5H9a2.5 2.5 0 000 5h8" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }
  if (type === "profit") {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M4 19V5M4 19h16" strokeLinecap="round" />
        <path d="M8 15l3.5-5 3 3.5L19 7" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    );
  }
  if (type === "bot") {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <rect x="5" y="8" width="14" height="10" rx="2.5" />
        <circle cx="9.5" cy="13" r="1.2" fill="currentColor" stroke="none" />
        <circle cx="14.5" cy="13" r="1.2" fill="currentColor" stroke="none" />
        <path d="M12 4v4M9 18v2M15 18v2" strokeLinecap="round" />
      </svg>
    );
  }
  if (type === "win") {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M8 14l2.5 2.5L16 9" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="12" cy="12" r="8.25" />
      </svg>
    );
  }
  if (type === "loss") {
    return (
      <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <path d="M8 8l8 8M16 8l-8 8" strokeLinecap="round" />
        <circle cx="12" cy="12" r="8.25" />
      </svg>
    );
  }
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <path d="M12 3v12M8 11l4 4 4-4" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5 19h14" strokeLinecap="round" />
    </svg>
  );
}

function StatCard({ label, value, hint, icon, accent = false, valueClassName, delayClass = "" }) {
  return (
    <div
      className={`group relative overflow-hidden rounded-2xl border border-[var(--color-line)] bg-white p-5 shadow-[var(--shadow-card)] transition-all duration-200 hover:-translate-y-0.5 hover:shadow-[var(--shadow-card-hover)] animate-fade-up ${delayClass}`}
    >
      <div
        className={`absolute inset-y-0 left-0 w-[3px] ${accent ? "bg-[var(--accent)]" : "bg-[#9bb5a6]/60"}`}
        aria-hidden
      />
      <div className="flex items-start justify-between gap-3">
        <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">{label}</p>
        <span
          className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${
            accent
              ? "bg-[var(--accent-soft)] text-[var(--accent)]"
              : "bg-[#f0f4f1] text-[var(--color-muted)]"
          }`}
        >
          <StatIcon type={icon} />
        </span>
      </div>
      <p
        className={`mt-3 text-[1.65rem] font-extrabold tracking-tight tabular-nums leading-none ${
          valueClassName || (accent ? "text-[var(--accent)]" : "text-[var(--color-ink)]")
        }`}
      >
        {value}
      </p>
      {hint ? <p className="mt-2.5 text-[13px] leading-snug text-[var(--color-muted)]">{hint}</p> : null}
    </div>
  );
}

export function SummaryCards({ summary }) {
  if (!summary) {
    return null;
  }

  const feeHint = summary.platformFeePerPlayer != null
    ? `${formatAmount(summary.platformFeePerPlayer, summary.currency)} / seat`
    : "Configured platform fee";

  const botMatches = summary.botMatches ?? 0;
  const botMatchWins = summary.botMatchWins ?? 0;
  const botMatchLosses = summary.botMatchLosses ?? 0;

  return (
    <div className="space-y-4">
      <section className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Total Games"
          value={summary.totalGames.toLocaleString("en-IN")}
          hint={`${summary.totalRealPlayers} real players · ${summary.totalBotPlayers} bot seats`}
          icon="games"
          delayClass="stagger-1"
        />
        <StatCard
          label="Total Real Income"
          value={formatAmount(summary.totalRealIncome, summary.currency)}
          hint="Sum of all real player entry fees"
          icon="income"
          delayClass="stagger-2"
        />
        <StatCard
          label="Platform Profit"
          value={formatAmount(summary.totalPlatformProfit, summary.currency)}
          hint={`Income − payouts · Fee ${feeHint}`}
          icon="profit"
          accent
          valueClassName={profitLossClassName(summary.totalPlatformProfit)}
          delayClass="stagger-3"
        />
        <StatCard
          label="Winner Payouts"
          value={formatAmount(summary.totalWinnerPayout, summary.currency)}
          hint="Paid only to real winners"
          icon="payout"
          delayClass="stagger-4"
        />
      </section>

      <section className="grid gap-4 sm:grid-cols-3">
        <StatCard
          label="Bot Matches"
          value={botMatches.toLocaleString("en-IN")}
          hint="Finished games that included at least one bot"
          icon="bot"
          delayClass="stagger-1"
        />
        <StatCard
          label="Real Player Win"
          value={botMatchWins.toLocaleString("en-IN")}
          hint="Real player won against bots"
          icon="win"
          valueClassName="text-emerald-600"
          delayClass="stagger-2"
        />
        <StatCard
          label="Bot Win"
          value={botMatchLosses.toLocaleString("en-IN")}
          hint="Bot or platform kept the pot"
          icon="loss"
          valueClassName="text-rose-600"
          delayClass="stagger-3"
        />
      </section>
    </div>
  );
}
