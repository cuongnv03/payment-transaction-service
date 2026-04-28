import axios, {
  AxiosError,
  AxiosInstance,
  InternalAxiosRequestConfig,
} from 'axios';
import { useAuthStore } from '@/store/auth';
import type { ApiError } from './types';

/**
 * Single axios instance used by every page/hook. Two interceptors:
 *
 *   - Request: attaches the JWT from the Zustand auth store as
 *     `Authorization: Bearer <token>` if a token is present.
 *   - Response: on 401, clears the auth store so the app falls back to the
 *     login screen on the next navigation. The promise is still rejected so
 *     the calling component can decide how to react.
 *
 * Errors thrown by this client are typed as {@link ApiError} when the backend
 * returned a structured payload, otherwise the original AxiosError surfaces.
 */
export const apiClient: AxiosInstance = axios.create({
  // Empty baseURL by default → same-origin. In dev, Vite proxies `/api/*` and
  // `/actuator/*` to the backend (see vite.config.ts). In prod, nginx in the
  // Docker image does the same. Override with VITE_API_BASE_URL to bypass.
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  headers: {
    'Content-Type': 'application/json',
  },
  timeout: 10_000,
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiError>) => {
    if (error.response?.status === 401) {
      // Clear auth state so subsequent requests don't keep failing with the
      // same expired/invalid token. The component layer handles the redirect.
      useAuthStore.getState().clear();
    }
    return Promise.reject(error);
  },
);

/**
 * Extracts a backend ApiError from an AxiosError. Returns a fallback
 * `{ code: 'NETWORK_ERROR', message }` if the request never reached the
 * server (timeout, DNS, CORS).
 */
export function toApiError(error: unknown): ApiError {
  if (axios.isAxiosError<ApiError>(error)) {
    if (error.response?.data?.code) {
      return error.response.data;
    }
    return {
      code: 'NETWORK_ERROR',
      message: error.message || 'Network request failed',
    };
  }
  return { code: 'UNKNOWN_ERROR', message: String(error) };
}
