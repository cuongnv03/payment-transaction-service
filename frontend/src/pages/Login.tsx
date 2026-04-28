import { FormEvent, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '@/api/authApi';
import { toApiError } from '@/api/client';
import { useAuthStore } from '@/store/auth';

const formStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '0.75rem',
  maxWidth: 320,
};

const errorStyle: React.CSSProperties = {
  color: 'crimson',
  marginTop: '0.5rem',
};

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();
  const setAuth = useAuthStore((s) => s.setAuth);

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Where the user was trying to go before being bounced to /login.
  const redirectTo = (location.state as { from?: string } | null)?.from ?? '/home';

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const auth = await authApi.login({ username, password });
      setAuth(auth);
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(toApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '2rem' }}>
      <h1>Sign in</h1>
      <form onSubmit={handleSubmit} style={formStyle} aria-label="login form">
        <label>
          Username
          <input
            name="username"
            type="text"
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            minLength={3}
            disabled={submitting}
          />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={8}
            disabled={submitting}
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>

      {error && (
        <p style={errorStyle} role="alert">
          {error}
        </p>
      )}

      <p style={{ marginTop: '1.5rem' }}>
        No account yet? <Link to="/register">Create one</Link>
      </p>
    </main>
  );
}
