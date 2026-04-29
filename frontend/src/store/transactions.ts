import { create } from 'zustand';
import { transactionsApi } from '@/api/transactionsApi';
import { toApiError } from '@/api/client';
import type { Transaction } from '@/api/types';

/**
 * Holds the latest fetched transactions plus async metadata.
 *
 * <p>{@code loading} is set to {@code true} only on the first fetch — subsequent
 * polls leave it {@code false} so the UI doesn't flicker through a loading
 * placeholder every 3 seconds.
 *
 * <p>{@code error} is cleared on the next successful fetch. The last-known
 * data is kept on transient errors so the user keeps seeing stale rows
 * instead of a blank screen.
 */
export interface TransactionsState {
  data: Transaction[];
  loading: boolean;
  error: string | null;
  hasFetchedOnce: boolean;
  fetch: () => Promise<void>;
}

export const useTransactionsStore = create<TransactionsState>((set, get) => ({
  data: [],
  loading: false,
  error: null,
  hasFetchedOnce: false,

  fetch: async () => {
    if (!get().hasFetchedOnce) {
      set({ loading: true });
    }
    try {
      const page = await transactionsApi.getMyTransactions();
      set({
        data: page.data,
        loading: false,
        error: null,
        hasFetchedOnce: true,
      });
    } catch (err) {
      // Keep the previous {@code data} so the table doesn't blank out on a
      // transient error — only the error banner reflects the failure.
      set({
        loading: false,
        error: toApiError(err).message,
        hasFetchedOnce: true,
      });
    }
  },
}));
