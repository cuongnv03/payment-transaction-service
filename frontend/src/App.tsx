import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from '@/components/ProtectedRoute';
import Home from '@/pages/Home';
import Login from '@/pages/Login';
import Register from '@/pages/Register';
import { selectIsAuthenticated, useAuthStore } from '@/store/auth';

/**
 * Top-level routing.
 *
 * Public:
 *   - /login, /register — bounce to /home if a token is already present so
 *     a logged-in user doesn't see the auth pages.
 *
 * Protected (requires auth token):
 *   - /home — placeholder until Task 20 (dashboard).
 *
 * The authenticated state is hydrated synchronously from localStorage by the
 * Zustand persist middleware, so route guards are correct on first render.
 */
export default function App() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);

  return (
    <BrowserRouter>
      <Routes>
        <Route
          path="/login"
          element={isAuthenticated ? <Navigate to="/home" replace /> : <Login />}
        />
        <Route
          path="/register"
          element={isAuthenticated ? <Navigate to="/home" replace /> : <Register />}
        />
        <Route
          path="/home"
          element={
            <ProtectedRoute>
              <Home />
            </ProtectedRoute>
          }
        />
        <Route path="/" element={<Navigate to="/home" replace />} />
        <Route path="*" element={<Navigate to="/home" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
