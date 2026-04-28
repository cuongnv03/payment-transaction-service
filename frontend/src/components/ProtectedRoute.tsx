import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { selectIsAuthenticated, useAuthStore } from '@/store/auth';

/**
 * Wraps a route element. If the auth store has no token, redirects to
 * {@code /login} with the originally-requested path stashed in
 * {@code location.state.from} so the login page can bounce back after success.
 *
 * <p>Authentication state is hydrated synchronously from localStorage by the
 * Zustand persist middleware, so this guard is correct on first render — no
 * need for a "loading" flicker on full-page reload.
 */
interface ProtectedRouteProps {
  children: ReactNode;
}

export function ProtectedRoute({ children }: ProtectedRouteProps) {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <>{children}</>;
}
