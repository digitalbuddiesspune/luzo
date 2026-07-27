import { useState } from "react";

export function LoginPage({ onLogin }) {
  const [email, setEmail] = useState("admin@gmail.com");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSubmit = async (event) => {
    event.preventDefault();
    setLoading(true);
    setError("");

    try {
      await onLogin({ email, password });
    } catch (loginError) {
      setError(loginError.message || "Login failed.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen items-center justify-center px-4 py-10 text-[var(--color-ink)]">
      <div className="w-full max-w-md rounded-3xl border border-[var(--color-line)] bg-white p-8 shadow-[var(--shadow-card)] animate-fade-up">
        <div className="mb-8 text-center">
          <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-2xl bg-[var(--accent-soft)] text-[var(--accent)]">
            <svg className="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3z" strokeLinejoin="round" />
              <path d="M12 12l8-4.5M12 12v9M12 12L4 7.5" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-[11px] font-bold uppercase tracking-[0.18em] text-[var(--accent)]">
            PotLudo Admin
          </p>
          <h1 className="mt-2 text-2xl font-extrabold tracking-tight">Sign in</h1>
          <p className="mt-2 text-sm text-[var(--color-muted)]">
            Use your admin account to open the console.
          </p>
        </div>

        <form className="space-y-4" onSubmit={handleSubmit}>
          <label className="block">
            <span className="text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
              Email
            </span>
            <input
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              className="mt-2 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-4 py-3 text-sm font-medium outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          <label className="block">
            <span className="text-[11px] font-bold uppercase tracking-[0.1em] text-[var(--color-muted)]">
              Password
            </span>
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="mt-2 w-full rounded-xl border border-[var(--color-line)] bg-[#f8faf9] px-4 py-3 text-sm font-medium outline-none ring-[var(--accent)] focus:ring-2"
            />
          </label>

          {error ? (
            <p className="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
              {error}
            </p>
          ) : null}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-xl bg-[var(--accent)] px-5 py-3 text-sm font-bold text-white transition hover:brightness-105 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? "Signing in…" : "Sign in"}
          </button>
        </form>
      </div>
    </div>
  );
}
