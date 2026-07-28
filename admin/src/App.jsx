import { useCallback, useEffect, useState } from "react";
import {
  fetchAdminSession,
  fetchGames,
  fetchSummary,
  fetchUsers,
  loginAdmin,
  logoutAdmin,
} from "./api/client";
import { GameDetailModal } from "./components/GameDetailModal";
import { Sidebar } from "./components/Sidebar";
import { DashboardPage } from "./pages/DashboardPage";
import { LoginPage } from "./pages/LoginPage";
import { PlatformsPage } from "./pages/PlatformsPage";
import { ProfitLossPage } from "./pages/ProfitLossPage";
import { SettingsPage } from "./pages/SettingsPage";

function App() {
  const [authChecked, setAuthChecked] = useState(false);
  const [admin, setAdmin] = useState(null);
  const [activePage, setActivePage] = useState("dashboard");
  const [profitLossSection, setProfitLossSection] = useState("overview");
  const [playerFilter, setPlayerFilter] = useState("all");
  const [operatorFilter, setOperatorFilter] = useState("all");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [summary, setSummary] = useState(null);
  const [dashboardSummary, setDashboardSummary] = useState(null);
  const [games, setGames] = useState([]);
  const [dashboardGames, setDashboardGames] = useState([]);
  const [gamesPagination, setGamesPagination] = useState(null);
  const [users, setUsers] = useState([]);
  const [usersPagination, setUsersPagination] = useState(null);
  const [selectedGame, setSelectedGame] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const loadDashboard = useCallback(async () => {
    const [summaryData, gamesData] = await Promise.all([
      fetchSummary("all", "all"),
      fetchGames(1, 20, "all", "all"),
    ]);
    setDashboardSummary(summaryData);
    setDashboardGames(gamesData.data);
  }, []);

  const loadProfitLossData = useCallback(async (
    page = 1,
    filter = "all",
    operatorId = "all",
    from = "",
    to = "",
  ) => {
    const [summaryData, gamesData, usersData] = await Promise.all([
      fetchSummary(filter, operatorId, from, to),
      fetchGames(page, 20, filter, operatorId, from, to),
      fetchUsers(1, 20, filter, operatorId, from, to),
    ]);
    setSummary(summaryData);
    setGames(gamesData.data);
    setGamesPagination(gamesData.pagination);
    setUsers(usersData.data);
    setUsersPagination(usersData.pagination);
  }, []);

  const loadGames = useCallback(async (page = 1) => {
    const result = await fetchGames(page, 20, playerFilter, operatorFilter, dateFrom, dateTo);
    setGames(result.data);
    setGamesPagination(result.pagination);
  }, [playerFilter, operatorFilter, dateFrom, dateTo]);

  const loadUsers = useCallback(async (page = 1) => {
    const result = await fetchUsers(page, 20, playerFilter, operatorFilter, dateFrom, dateTo);
    setUsers(result.data);
    setUsersPagination(result.pagination);
  }, [playerFilter, operatorFilter, dateFrom, dateTo]);

  useEffect(() => {
    let cancelled = false;

    async function bootstrapAuth() {
      try {
        const session = await fetchAdminSession();
        if (!cancelled) {
          setAdmin(session?.admin || null);
        }
      } catch {
        if (!cancelled) {
          setAdmin(null);
        }
      } finally {
        if (!cancelled) {
          setAuthChecked(true);
        }
      }
    }

    bootstrapAuth();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!admin) {
      return undefined;
    }

    let cancelled = false;

    async function loadInitialData() {
      setLoading(true);
      setError("");

      try {
        await Promise.all([loadDashboard(), loadProfitLossData(1, "all", "all")]);
      } catch (loadError) {
        if (!cancelled) {
          if (loadError.status === 401) {
            setAdmin(null);
            return;
          }
          setError(loadError.message || "Failed to load admin data.");
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadInitialData();

    return () => {
      cancelled = true;
    };
  }, [admin, loadDashboard, loadProfitLossData]);

  const reloadFilteredProfitLoss = async (
    nextPlayerFilter,
    nextOperatorFilter,
    nextDateFrom = dateFrom,
    nextDateTo = dateTo,
  ) => {
    setLoading(true);
    setError("");

    try {
      await loadProfitLossData(1, nextPlayerFilter, nextOperatorFilter, nextDateFrom, nextDateTo);
    } catch (loadError) {
      if (loadError.status === 401) {
        setAdmin(null);
        return;
      }
      setError(loadError.message || "Failed to filter profit & loss data.");
    } finally {
      setLoading(false);
    }
  };

  const handlePlayerFilterChange = async (nextFilter) => {
    setPlayerFilter(nextFilter);
    await reloadFilteredProfitLoss(nextFilter, operatorFilter, dateFrom, dateTo);
  };

  const handleOperatorFilterChange = async (nextOperator) => {
    setOperatorFilter(nextOperator);
    await reloadFilteredProfitLoss(playerFilter, nextOperator, dateFrom, dateTo);
  };

  const handleDateFilterChange = async (nextDateFrom, nextDateTo) => {
    setDateFrom(nextDateFrom);
    setDateTo(nextDateTo);
    await reloadFilteredProfitLoss(playerFilter, operatorFilter, nextDateFrom, nextDateTo);
  };

  const openProfitLossForOperator = async (operatorId = "all") => {
    setActivePage("profit-loss");
    setProfitLossSection("overview");
    setOperatorFilter(operatorId);
    await reloadFilteredProfitLoss(playerFilter, operatorId);
  };

  const handleLogin = async ({ email, password }) => {
    const result = await loginAdmin({ email, password });
    setAdmin(result.admin);
    setActivePage("dashboard");
    setError("");
  };

  const handleLogout = async () => {
    await logoutAdmin();
    setAdmin(null);
    setSummary(null);
    setDashboardSummary(null);
    setGames([]);
    setDashboardGames([]);
    setUsers([]);
    setSelectedGame(null);
    setError("");
  };

  const pageMeta = {
    dashboard: { title: "Dashboard", hint: "Live overview" },
    platforms: { title: "Platforms", hint: "Per-partner breakdown" },
    "profit-loss": { title: "Profit & Loss", hint: "Games, users & filters" },
    settings: { title: "Settings", hint: "Platform fee & monetization" },
  }[activePage] || { title: "Admin", hint: "" };

  const operators = summary?.byOperator || dashboardSummary?.byOperator || [];

  if (!authChecked) {
    return (
      <div className="flex min-h-screen items-center justify-center text-[var(--color-ink)]">
        <div className="flex flex-col items-center gap-3">
          <div className="h-9 w-9 animate-spin rounded-full border-2 border-[var(--color-line)] border-t-[var(--accent)]" />
          <p className="text-sm font-medium text-[var(--color-muted)]">Checking admin session…</p>
        </div>
      </div>
    );
  }

  if (!admin) {
    return <LoginPage onLogin={handleLogin} />;
  }

  return (
    <div className="flex min-h-screen text-[var(--color-ink)]">
      <Sidebar
        activePage={activePage}
        onNavigate={setActivePage}
        mobileOpen={mobileOpen}
        onClose={() => setMobileOpen(false)}
        admin={admin}
        onLogout={handleLogout}
      />

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex items-center gap-3 border-b border-[var(--color-line)]/80 bg-white/80 px-4 py-3.5 backdrop-blur-md sm:px-6">
          <button
            type="button"
            className="rounded-xl border border-[var(--color-line)] bg-white p-2 text-[var(--color-ink)] transition-colors hover:bg-[#f4f7f5] lg:hidden"
            onClick={() => setMobileOpen(true)}
            aria-label="Open menu"
          >
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M4 7h16M4 12h16M4 17h16" strokeLinecap="round" />
            </svg>
          </button>
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[var(--color-muted)]">
              Admin · {pageMeta.hint}
            </p>
            <h1 className="truncate text-base font-bold tracking-tight text-[var(--color-ink)]">
              {pageMeta.title}
            </h1>
          </div>
          <div className="hidden items-center gap-2 sm:flex">
            <span className="inline-flex items-center gap-1.5 rounded-full bg-[var(--accent-soft)] px-3 py-1 text-xs font-semibold text-[var(--accent)]">
              <span className="h-1.5 w-1.5 rounded-full bg-[var(--accent)]" aria-hidden />
              Live
            </span>
          </div>
        </header>

        <main className="flex-1 px-4 py-6 sm:px-6 lg:px-8">
          {loading && activePage !== "settings" ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-[var(--color-line)] bg-white px-6 py-16 shadow-[var(--shadow-card)] animate-fade-up">
              <div className="h-9 w-9 animate-spin rounded-full border-2 border-[var(--color-line)] border-t-[var(--accent)]" />
              <p className="text-sm font-medium text-[var(--color-muted)]">Loading admin data…</p>
            </div>
          ) : null}

          {error && activePage !== "settings" ? (
            <div className="rounded-2xl border border-rose-200 bg-rose-50 px-6 py-4 text-rose-800 shadow-sm animate-fade-up">
              <p className="font-semibold">Something went wrong</p>
              <p className="mt-1 text-sm text-rose-700">{error}</p>
            </div>
          ) : null}

          {!loading && !error && activePage === "dashboard" ? (
            <DashboardPage
              summary={dashboardSummary}
              recentGames={dashboardGames.slice(0, 6)}
              operators={(dashboardSummary?.byOperator || []).slice(0, 4)}
              onOpenProfitLoss={() => openProfitLossForOperator("all")}
              onOpenPlatforms={() => setActivePage("platforms")}
              onSelectOperator={(operatorId) => openProfitLossForOperator(operatorId)}
              onSelectGame={setSelectedGame}
            />
          ) : null}

          {!loading && !error && activePage === "platforms" ? (
            <PlatformsPage
              operators={operators}
              currency={summary?.currency || dashboardSummary?.currency || "INR"}
              selectedOperatorId={operatorFilter === "all" ? null : operatorFilter}
              onSelectOperator={(operatorId) => openProfitLossForOperator(operatorId)}
              onOpenProfitLoss={openProfitLossForOperator}
            />
          ) : null}

          {!loading && !error && activePage === "profit-loss" ? (
            <ProfitLossPage
              section={profitLossSection}
              onSectionChange={setProfitLossSection}
              playerFilter={playerFilter}
              onPlayerFilterChange={handlePlayerFilterChange}
              operatorFilter={operatorFilter}
              onOperatorFilterChange={handleOperatorFilterChange}
              dateFrom={dateFrom}
              dateTo={dateTo}
              onDateFilterChange={handleDateFilterChange}
              operators={operators}
              summary={summary}
              games={games}
              gamesPagination={gamesPagination}
              onGamesPageChange={loadGames}
              users={users}
              usersPagination={usersPagination}
              onUsersPageChange={loadUsers}
              onSelectGame={setSelectedGame}
            />
          ) : null}

          {activePage === "settings" ? (
            <SettingsPage currency={summary?.currency || dashboardSummary?.currency || "INR"} />
          ) : null}
        </main>
      </div>

      <GameDetailModal game={selectedGame} onClose={() => setSelectedGame(null)} />
    </div>
  );
}

export default App;
