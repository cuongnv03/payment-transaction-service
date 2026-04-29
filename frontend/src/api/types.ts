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
  // Spring Boot's default Jackson config serializes BigDecimal as a JSON number.
  // Precision loss only applies above Number.MAX_SAFE_INTEGER (~9 quadrillion).
  balance: number;
  currency: string;
  createdAt: string;
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
  amount: number;
  description?: string;
}

export interface Transaction {
  id: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
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

// ── Admin ─────────────────────────────────────────────────────────────────────

/**
 * One row from {@code GET /api/admin/dlq}. {@code transactionId} and
 * {@code eventType} are pre-parsed by the backend adapter from the JSON
 * payload; either may be {@code null} when the payload was unreadable.
 */
export interface DlqEvent {
  id: string;
  topic: string;
  kafkaPartition: number | null;
  kafkaOffset: number | null;
  payload: string;
  transactionId: string | null;
  eventType: string | null;
  errorMessage: string;
  retryCount: number;
  createdAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
}

export type CircuitBreakerState = 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'DISABLED' | 'FORCED_OPEN' | 'METRICS_ONLY';

/**
 * Snapshot of {@code payment-gateway} circuit breaker. {@code failureRate}
 * and {@code slowCallRate} are {@code -1} when the sliding window has not
 * yet observed enough calls — the UI should render that as "—".
 */
export interface CircuitBreakerStatus {
  name: string;
  state: CircuitBreakerState;
  failureRate: number;
  slowCallRate: number;
  bufferedCalls: number;
  failedCalls: number;
}
