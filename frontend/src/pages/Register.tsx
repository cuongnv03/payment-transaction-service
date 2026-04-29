import { FormEvent, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '@/api/authApi';
import { toApiError } from '@/api/client';
import { useAuthStore } from '@/store/auth';

export default function Register() {
  const navigate = useNavigate();
  const setAuth = useAuthStore((s) => s.setAuth);

  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      // Register returns the same AuthResponse shape as login — single round-trip,
      // straight into the authenticated state.
      const auth = await authApi.register({ username, email, password });
      setAuth(auth);
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(toApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page page--narrow">
      <h1>Create account</h1>
      <form onSubmit={handleSubmit} className="form" aria-label="register form">
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
          Email
          <input
            name="email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            disabled={submitting}
          />
        </label>
        <label>
          Password
          <input
            name="password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={8}
            disabled={submitting}
          />
        </label>
        <button type="submit" disabled={submitting}>
          {submitting ? 'Creating…' : 'Create account'}
        </button>
      </form>

      {error && (
        <p className="form__error" role="alert">
          {error}
        </p>
      )}

      <p className="form__footer">
        Already registered? <Link to="/login">Sign in</Link>
      </p>
    </main>
  );
}
