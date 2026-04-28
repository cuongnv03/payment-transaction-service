/**
 * TypeScript types for the backend API. These mirror the Java DTOs in
 * `dev.cuong.payment.presentation.*` — keep them in sync when the backend changes.
 *
 * Notes on intentional shape choices (see docs/IMPLEMENTATION_NOTES.md):
 *   - URLs use `/api/...` (Deviation 1), not `/api/v1/...`.
 *   - Auth uses `username` (Deviation 2), not `email` for login.
 *   - Responses are unwrapped (Deviation 6) — no `ApiResponse<T>` envelope.
 *   - Error responses are flat `{ code, message }` (Deviation 7).
 */

// ── Auth ──────────────────────────────────────────────────────────────────────

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export type Role = 'USER' | 'ADMIN';

export interface AuthResponse {
  token: string;
  userId: string;
  username: string;
  role: Role;
}

// ── User ──────────────────────────────────────────────────────────────────────

export interface UserProfile {
  id: string;
  username: string;
  email: string;
  role: Role;
  createdAt: string;
}

// ── Account ───────────────────────────────────────────────────────────────────

export interface Account {
  id: string;
  userId: string;
  balance: string; // BigDecimal arrives as a JSON string to preserve precision
  currency: string;
  createdAt: string;
  updatedAt: string;
}

// ── Transaction ───────────────────────────────────────────────────────────────

export type TransactionStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'SUCCESS'
  | 'FAILED'
  | 'TIMEOUT'
  | 'REFUNDED';

export interface CreateTransactionRequest {
  toAccountId: string;
  amount: string; // BigDecimal as string
  description?: string;
}

export interface Transaction {
  id: string;
  fromAccountId: string;
  toAccountId: string;
  amount: string;
  currency: string;
  status: TransactionStatus;
  description: string | null;
  gatewayReference: string | null;
  failureReason: string | null;
  retryCount: number;
  processedAt: string | null;
  refundedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

// ── Generic envelopes ─────────────────────────────────────────────────────────

export interface PagedResult<T> {
  data: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Flat error payload returned by GlobalExceptionHandler — see Deviation 7. */
export interface ApiError {
  code: string;
  message: string;
}
