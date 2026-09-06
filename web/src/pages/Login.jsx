import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth.jsx';
import { Alert, LoadingScreen } from '../components/ui.jsx';

export default function LoginPage() {
  const { user, loading, login } = useAuth();
  const navigate = useNavigate();

  const [identifier, setIdentifier] = useState('');
  const [secret, setSecret] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  if (loading) return <LoadingScreen label="Checking your session…" />;
  if (user) return <Navigate to={user.role === 'ADMIN' ? '/admin' : '/'} replace />;

  const submit = async (event) => {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      const signedIn = await login(identifier.trim(), secret);
      navigate(signedIn.role === 'ADMIN' ? '/admin' : '/', { replace: true });
    } catch (err) {
      setError(err.message);
      setSecret('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-logo">
          <div className="mark">🙏</div>
          <h1>Sewa Entry</h1>
          <p>Sign in to record your sewa</p>
        </div>

        <Alert kind="error" onDismiss={() => setError('')}>{error}</Alert>

        <form onSubmit={submit}>
          <div className="field">
            <label htmlFor="identifier">Sewadar ID, phone or email</label>
            <input
              id="identifier"
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              autoComplete="username"
              autoCapitalize="characters"
              autoFocus
              required
            />
          </div>

          <div className="field">
            <label htmlFor="secret">PIN or password</label>
            <input
              id="secret"
              type="password"
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              autoComplete="current-password"
              required
            />
            <div className="field-hint">
              Sewadars use their PIN. Administrators use their password.
              Three wrong attempts lock the account for five minutes.
            </div>
          </div>

          <button type="submit" className="btn-primary btn-block" disabled={busy || !identifier || !secret}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <div className="auth-footer">
          At the sewa counter instead? Open the <Link to="/kiosk">counter tablet</Link>.
          <br />
          Demo admin: <code>ADM001</code> / <code>sewa@admin1</code>
        </div>
      </div>
    </div>
  );
}
