import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import type { Role } from '@/api/types';
import { selectIsAuthenticated, useAuthStore } from '@/store/auth';

/**
 * Wraps a route element. Redirects unauthenticated visitors to {@code /login}
 * with the originally-requested path stashed in {@code location.state.from}
 * so the login page can bounce back after success.
 *
 * <p>If {@code requiredRole} is set and the authenticated user's role doesn't
 * match, redirects to {@code /dashboard} (the regular landing page) — the
 * server is the authoritative authorization boundary; the route guard just
 * keeps the URL bar honest.
 *
 * <p>Authentication state is hydrated synchronously from localStorage by the
 * Zustand persist middleware, so this guard is correct on first render.
 */
interface ProtectedRouteProps {
  children: ReactNode;
  /** Optional role gate. When set, the user must have this exact role. */
  requiredRole?: Role;
}

export function ProtectedRoute({ children, requiredRole }: ProtectedRouteProps) {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const role = useAuthStore((s) => s.role);
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (requiredRole && role !== requiredRole) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}
