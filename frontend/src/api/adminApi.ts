import { apiClient } from './client';
import type {
  CircuitBreakerStatus,
  DlqEvent,
  PagedResult,
  Transaction,
  TransactionStatus,
} from './types';

/**
 * Typed wrappers around the backend's {@code /api/admin/*} endpoints.
 * All endpoints require {@code ROLE_ADMIN} on the JWT — a 403 surfaces as a
 * regular AxiosError that the calling code normalises via {@code toApiError}.
 */
export const adminApi = {
  getDlqEvents(page = 0, size = 50): Promise<PagedResult<DlqEvent>> {
    return apiClient
      .get<PagedResult<DlqEvent>>('/api/admin/dlq', { params: { page, size } })
      .then((res) => res.data);
  },

  retryDlqEvent(dlqEventId: string): Promise<void> {
    return apiClient.post(`/api/admin/dlq/${dlqEventId}/retry`).then(() => undefined);
  },

  getAllTransactions(
    page = 0,
    size = 50,
    status?: TransactionStatus,
  ): Promise<PagedResult<Transaction>> {
    const params: Record<string, string | number> = { page, size };
    if (status) params.status = status;
    return apiClient
      .get<PagedResult<Transaction>>('/api/admin/transactions', { params })
      .then((res) => res.data);
  },

  getCircuitBreakerStatus(): Promise<CircuitBreakerStatus> {
    return apiClient
      .get<CircuitBreakerStatus>('/api/admin/circuit-breaker')
      .then((res) => res.data);
  },
};
