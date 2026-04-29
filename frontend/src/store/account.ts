import { create } from 'zustand';
import { transactionsApi } from '@/api/transactionsApi';
import { toApiError } from '@/api/client';
import type { Account } from '@/api/types';

/** Holds the authenticated user's account (balance + currency). */
export interface AccountState {
  data: Account | null;
  error: string | null;
  hasFetchedOnce: boolean;
  fetch: () => Promise<void>;
}

export const useAccountStore = create<AccountState>((set) => ({
  data: null,
  error: null,
  hasFetchedOnce: false,

  fetch: async () => {
    try {
      const account = await transactionsApi.getMyAccount();
      set({ data: account, error: null, hasFetchedOnce: true });
    } catch (err) {
      // Like the transactions store, keep the previous data on error so the
      // balance doesn't disappear on a momentary network blip.
      set({ error: toApiError(err).message, hasFetchedOnce: true });
    }
  },
}));
