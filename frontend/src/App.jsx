import { useCallback, useEffect, useState } from "react";
import { api, auth } from "./api.js";
import ThemeToggle from "./components/ThemeToggle.jsx";
import Login from "./pages/Login.jsx";
import Library from "./pages/Library.jsx";
import Search from "./pages/Search.jsx";
import Ask from "./pages/Ask.jsx";
import { applyTheme, readTheme } from "./theme.js";

const TABS = [
  { id: "library", label: "Library" },
  { id: "search", label: "Search" },
  { id: "ask", label: "Ask" },
];

export default function App() {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(true);
  const [tab, setTab] = useState("library");
  const [stats, setStats] = useState(null);
  const [theme, setTheme] = useState(readTheme);

  const toggleTheme = () => {
    const next = theme === "dark" ? "light" : "dark";
    setTheme(next);
    applyTheme(next);
  };

  // A stored token might be expired. Ask the server rather than trusting localStorage.
  useEffect(() => {
    if (!auth.token) {
      setChecking(false);
      return;
    }
    api
      .me()
      .then(setUser)
      .catch(() => auth.clear())
      .finally(() => setChecking(false));
  }, []);

  const refreshStats = useCallback(() => {
    if (!auth.token) return;
    api
      .indexStats()
      .then(setStats)
      .catch(() => setStats(null));
  }, []);

  useEffect(() => {
    if (user) refreshStats();
  }, [user, tab, refreshStats]);

  if (checking) return null;

  if (!user) {
    return <Login onSignedIn={setUser} />;
  }

  const signOut = () => {
    auth.clear();
    setUser(null);
  };

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">
            <span />
            <span />
            <span />
          </div>
          SemanticDocs
        </div>

        <nav className="tabs" role="tablist">
          {TABS.map((item) => (
            <button
              key={item.id}
              role="tab"
              aria-selected={tab === item.id}
              className="tab"
              onClick={() => setTab(item.id)}
            >
              {item.label}
            </button>
          ))}
        </nav>

        <div className="topbar-right">
          {stats && (
            <div className="index-strip" title="Live HNSW index statistics">
              <span>
                <b>{stats.nodeCount.toLocaleString()}</b> vectors
              </span>
              <span>
                <b>{stats.edgeCount.toLocaleString()}</b> edges
              </span>
              <span>
                layers <b>{stats.maxLevel + 1}</b>
              </span>
              <span>
                <b>{(stats.approximateBytes / 1048576).toFixed(1)}</b> MB
              </span>
            </div>
          )}

          <ThemeToggle />
          <button className="btn ghost" onClick={signOut}>
            Sign out
          </button>
        </div>
      </header>

      <main className="main">
        {tab === "library" && <Library onChange={refreshStats} />}
        {tab === "search" && <Search />}
        {tab === "ask" && <Ask />}
      </main>
    </div>
  );
}

function SunIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="4.2" />
      <path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.2 5.2l1.4 1.4M17.4 17.4l1.4 1.4M18.8 5.2l-1.4 1.4M6.6 17.4l-1.4 1.4" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M20 14.2A8.2 8.2 0 0 1 9.8 4a8.2 8.2 0 1 0 10.2 10.2z" />
    </svg>
  );
}
