import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import type { Role } from '@/api/types';

/**
 * Authentication state, persisted to localStorage so a refresh keeps the user
 * signed in until their JWT expires (server-side validation kicks them out
 * with 401 if the token has expired — see the response interceptor in
 * `api/client.ts`, which calls {@link AuthState.clear} on 401).
 */
export interface AuthState {
  token: string | null;
  userId: string | null;
  username: string | null;
  role: Role | null;

  setAuth: (auth: { token: string; userId: string; username: string; role: Role }) => void;
  clear: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      userId: null,
      username: null,
      role: null,

      setAuth: ({ token, userId, username, role }) =>
        set({ token, userId, username, role }),

      clear: () =>
        set({ token: null, userId: null, username: null, role: null }),
    }),
    {
      name: 'payment-auth',
      storage: createJSONStorage(() => localStorage),
    },
  ),
);

/** Convenience selector — true when a token is present. */
export const selectIsAuthenticated = (s: AuthState) => s.token !== null;
