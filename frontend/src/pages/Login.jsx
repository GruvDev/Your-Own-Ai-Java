import { useState } from 'react';
import { api, auth } from '../api.js';
import ThemeToggle from '../components/ThemeToggle.jsx';

export default function Login({ onSignedIn }) {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const payload = { email, password };
      const result = mode === 'login' ? await api.login(payload) : await api.register(payload);
      auth.save(result.token);
      onSignedIn(result.user);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const canSubmit = email.trim() && password.length >= 8 && !busy;

  return (
    <div className="auth-wrap">
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <ThemeToggle />
      </div>
      <div className="brand" style={{ justifyContent: 'center', marginBottom: 18 }}>
        <div className="brand-mark" aria-hidden="true"><span /><span /><span /></div>
        SemanticDocs
      </div>

      <div className="card">
        <h2 style={{ fontSize: 20, marginBottom: 4 }}>
          {mode === 'login' ? 'Sign in' : 'Create an account'}
        </h2>
        <p className="muted" style={{ marginTop: 0, fontSize: 14 }}>
          Search your own documents by meaning, not keywords.
        </p>

        {error && <div className="banner error">{error}</div>}

        <div className="field">
          <label htmlFor="email">Email</label>
          <input
            id="email"
            type="email"
            value={email}
            autoComplete="email"
            onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && canSubmit && submit()}
          />
        </div>

        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            onChange={(e) => setPassword(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && canSubmit && submit()}
          />
          {mode === 'register' && (
            <span className="mono muted">At least 8 characters</span>
          )}
        </div>

        <button className="btn" style={{ width: '100%' }} disabled={!canSubmit} onClick={submit}>
          {busy ? 'Working...' : mode === 'login' ? 'Sign in' : 'Create account'}
        </button>

        <div className="auth-switch">
          {mode === 'login' ? "No account yet? " : 'Already registered? '}
          <button
            className="linkish"
            onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); }}
          >
            {mode === 'login' ? 'Create one' : 'Sign in'}
          </button>
        </div>
      </div>
    </div>
  );
}
