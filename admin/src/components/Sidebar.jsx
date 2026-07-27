const NAV_ITEMS = [
  { id: "dashboard", label: "Dashboard", icon: "dashboard" },
  { id: "platforms", label: "Platforms", icon: "platforms" },
  { id: "profit-loss", label: "Profit & Loss", icon: "profit" },
  { id: "settings", label: "Settings", icon: "settings" },
];

function NavIcon({ name }) {
  if (name === "dashboard") {
    return (
      <svg className="h-[18px] w-[18px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <rect x="3" y="3" width="8" height="8" rx="1.5" />
        <rect x="13" y="3" width="8" height="5" rx="1.5" />
        <rect x="13" y="10" width="8" height="11" rx="1.5" />
        <rect x="3" y="13" width="8" height="8" rx="1.5" />
      </svg>
    );
  }

  if (name === "platforms") {
    return (
      <svg className="h-[18px] w-[18px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <circle cx="7" cy="7" r="3" />
        <circle cx="17" cy="7" r="3" />
        <circle cx="7" cy="17" r="3" />
        <circle cx="17" cy="17" r="3" />
        <path d="M10 7h4M7 10v4M17 10v4M10 17h4" strokeLinecap="round" />
      </svg>
    );
  }

  if (name === "settings") {
    return (
      <svg className="h-[18px] w-[18px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
        <circle cx="12" cy="12" r="3" />
        <path
          d="M19.4 15a1.7 1.7 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.8-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.7 1.7 0 00-1.1-1.5 1.7 1.7 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.8 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1a1.7 1.7 0 001.5-1.1 1.7 1.7 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.8.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.8V9c.2.6.7 1 1.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z"
          strokeLinejoin="round"
        />
      </svg>
    );
  }

  return (
    <svg className="h-[18px] w-[18px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
      <path d="M4 19V5" strokeLinecap="round" />
      <path d="M4 19h16" strokeLinecap="round" />
      <path d="M8 15v-4" strokeLinecap="round" />
      <path d="M12 15V8" strokeLinecap="round" />
      <path d="M16 15v-6" strokeLinecap="round" />
    </svg>
  );
}

export function Sidebar({ activePage, onNavigate, mobileOpen, onClose, admin, onLogout }) {
  return (
    <>
      {mobileOpen ? (
        <button
          type="button"
          aria-label="Close menu"
          className="fixed inset-0 z-40 bg-[#0f2a20]/45 backdrop-blur-[2px] lg:hidden animate-fade-in"
          onClick={onClose}
        />
      ) : null}

      <aside
        className={`fixed inset-y-0 left-0 z-50 flex w-[260px] flex-col bg-[var(--bg-sidebar)] text-[var(--text-sidebar)] transition-transform duration-300 ease-out lg:static lg:translate-x-0 ${
          mobileOpen ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <div className="relative overflow-hidden border-b border-white/10 px-5 py-6">
          <div
            className="pointer-events-none absolute -right-8 -top-10 h-28 w-28 rounded-full opacity-30"
            style={{ background: "radial-gradient(circle, #3da876 0%, transparent 70%)" }}
            aria-hidden
          />
          <div className="relative flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-[#3da876]/20 text-[#7dcea0] ring-1 ring-[#3da876]/30">
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9">
                <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3z" strokeLinejoin="round" />
                <path d="M12 12l8-4.5M12 12v9M12 12L4 7.5" strokeLinecap="round" />
              </svg>
            </div>
            <div>
              <p className="text-[11px] font-bold uppercase tracking-[0.2em] text-[#7dcea0]">PotLudo</p>
              <h1 className="text-base font-bold tracking-tight text-white">Admin Panel</h1>
            </div>
          </div>
        </div>

        <nav className="flex-1 space-y-1 px-3 py-5">
          <p className="mb-2 px-3 text-[10px] font-semibold uppercase tracking-[0.16em] text-white/35">
            Menu
          </p>
          {NAV_ITEMS.map((item) => {
            const active = activePage === item.id;

            return (
              <button
                key={item.id}
                type="button"
                onClick={() => {
                  onNavigate(item.id);
                  onClose();
                }}
                className={`group flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-all duration-200 ${
                  active
                    ? "bg-[var(--bg-sidebar-active)] text-[var(--text-sidebar-active)] shadow-[inset_3px_0_0_0_#3da876]"
                    : "text-[var(--text-sidebar)] hover:bg-[var(--bg-sidebar-hover)] hover:text-white"
                }`}
              >
                <span
                  className={`flex h-8 w-8 items-center justify-center rounded-lg transition-colors ${
                    active
                      ? "bg-[#3da876]/25 text-[#9ee0b8]"
                      : "bg-white/5 text-white/55 group-hover:bg-white/10 group-hover:text-white/80"
                  }`}
                >
                  <NavIcon name={item.icon} />
                </span>
                {item.label}
              </button>
            );
          })}
        </nav>

        <div className="space-y-3 border-t border-white/10 px-5 py-4">
          {admin ? (
            <div>
              <p className="truncate text-sm font-semibold text-white">{admin.displayName || "Admin"}</p>
              <p className="truncate text-[11px] text-white/40">{admin.email}</p>
            </div>
          ) : null}
          <button
            type="button"
            onClick={onLogout}
            className="w-full rounded-xl border border-white/10 bg-white/5 px-3 py-2 text-left text-sm font-medium text-white/80 transition hover:bg-white/10 hover:text-white"
          >
            Log out
          </button>
        </div>
      </aside>
    </>
  );
}
