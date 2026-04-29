import { apiClient } from './client';
import type {
  Account,
  CreateTransactionRequest,
  PagedResult,
  Transaction,
  TransactionStatus,
} from './types';

/**
 * Typed wrappers around `/api/accounts/me` and `/api/transactions`.
 *
 * Returns the deserialized response body directly — callers don't have to
 * touch axios. Errors propagate as AxiosError; use {@code toApiError} to
 * normalize them at the UI boundary.
 */
export const transactionsApi = {
  getMyAccount(): Promise<Account> {
    return apiClient.get<Account>('/api/accounts/me').then((res) => res.data);
  },

  /**
   * Returns the authenticated user's transactions, newest first.
   *
   * @param page zero-based page index (default 0)
   * @param size page size (default 20)
   * @param status optional status filter
   */
  getMyTransactions(
    page = 0,
    size = 20,
    status?: TransactionStatus,
  ): Promise<PagedResult<Transaction>> {
    const params: Record<string, string | number> = { page, size };
    if (status) params.status = status;
    return apiClient
      .get<PagedResult<Transaction>>('/api/transactions', { params })
      .then((res) => res.data);
  },

  /**
   * Creates a new P2P transaction.
   *
   * <p>The {@code idempotencyKey} MUST be a stable client-generated identifier
   * for the user's logical intent — typically a UUID v4 generated once when
   * the create form opens and re-sent on every retry of the same form. The
   * backend deduplicates by this key, so resubmits of the same form return
   * the original transaction without double-charging.
   */
  createTransaction(
    payload: CreateTransactionRequest,
    idempotencyKey: string,
  ): Promise<Transaction> {
    return apiClient
      .post<Transaction>('/api/transactions', payload, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .then((res) => res.data);
  },
};
