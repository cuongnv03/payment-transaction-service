import { useEffect, useState } from 'react';
import { apiClient, toApiError } from '@/api/client';
import { selectIsAuthenticated, useAuthStore } from '@/store/auth';

/**
 * Placeholder app shell. Task 19 replaces this with the real auth pages and
 * router. For Task 18 the goal is just to verify the wiring end-to-end:
 * - Vite serves the bundle
 * - axios reaches the backend via the dev proxy
 * - the Zustand auth store hydrates from localStorage
 */
export default function App() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const [healthStatus, setHealthStatus] = useState<string>('unknown');
  const [healthError, setHealthError] = useState<string | null>(null);

  useEffect(() => {
    apiClient
      .get<{ status: string }>('/actuator/health')
      .then((res) => setHealthStatus(res.data.status))
      .catch((err) => setHealthError(toApiError(err).message));
  }, []);

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '2rem' }}>
      <h1>Payment Transaction Service</h1>
      <p>Frontend scaffold — Task 18.</p>

      <section>
        <h2>Backend connectivity</h2>
        {healthError ? (
          <p style={{ color: 'crimson' }}>error: {healthError}</p>
        ) : (
          <p>
            <code>/actuator/health</code> → <strong>{healthStatus}</strong>
          </p>
        )}
      </section>

      <section>
        <h2>Auth state</h2>
        <p>
          {isAuthenticated
            ? 'A token is present in the auth store.'
            : 'Not authenticated. The login page lands in Task 19.'}
        </p>
      </section>
    </main>
  );
}
