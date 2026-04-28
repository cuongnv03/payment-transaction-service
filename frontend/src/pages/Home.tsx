import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/store/auth';

/**
 * Placeholder home page shown after successful authentication.
 * Task 20 replaces this with the real transaction dashboard.
 */
export default function Home() {
  const navigate = useNavigate();
  const username = useAuthStore((s) => s.username);
  const role = useAuthStore((s) => s.role);
  const clear = useAuthStore((s) => s.clear);

  function handleLogout() {
    clear();
    navigate('/login', { replace: true });
  }

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '2rem' }}>
      <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>Welcome, {username}</h1>
        <button onClick={handleLogout}>Sign out</button>
      </header>
      <p>
        Role: <strong>{role}</strong>
      </p>
      <p>The transaction dashboard lands in Task 20.</p>
    </main>
  );
}
