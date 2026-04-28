import { apiClient } from './client';
import type { AuthResponse, LoginRequest, RegisterRequest } from './types';

/**
 * Typed wrappers around the `/api/auth/*` endpoints. Both endpoints return
 * the same `AuthResponse` shape (token + user identity), so registration can
 * auto-login by re-using the response.
 */
export const authApi = {
  register(payload: RegisterRequest): Promise<AuthResponse> {
    return apiClient
      .post<AuthResponse>('/api/auth/register', payload)
      .then((res) => res.data);
  },

  login(payload: LoginRequest): Promise<AuthResponse> {
    return apiClient
      .post<AuthResponse>('/api/auth/login', payload)
      .then((res) => res.data);
  },
};
