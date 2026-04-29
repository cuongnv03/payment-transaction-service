import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { CreateTransactionModal } from '@/components/CreateTransactionModal';
import { TransactionTable } from '@/components/TransactionTable';
import { useAccountStore } from '@/store/account';
import { useAuthStore } from '@/store/auth';
import { useTransactionsStore } from '@/store/transactions';

const POLL_INTERVAL_MS = 3000;

/**
 * Authenticated landing page. Polls the account and transaction list every 3
 * seconds with a per-tick in-flight guard so a slow request never overlaps
 * with the next interval.
 *
 * <p>Errors do not blank the table — the user sees the last-known data
 * alongside an error banner.
 */
export default function Dashboard() {
  const navigate = useNavigate();
  const username = useAuthStore((s) => s.username);
  const role = useAuthStore((s) => s.role);
  const clear = useAuthStore((s) => s.clear);

  const account = useAccountStore((s) => s.data);
  const accountError = useAccountStore((s) => s.error);
  const fetchAccount = useAccountStore((s) => s.fetch);

  const transactions = useTransactionsStore((s) => s.data);
  const transactionsLoading = useTransactionsStore((s) => s.loading);
  const transactionsError = useTransactionsStore((s) => s.error);
  const fetchTransactions = useTransactionsStore((s) => s.fetch);

  // Per-poll in-flight guard. Using a ref (not state) so a still-pending poll
  // doesn't trigger a re-render every interval.
  const inFlightRef = useRef(false);

  const [createOpen, setCreateOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;

    async function tick() {
      if (inFlightRef.current || cancelled) return;
      inFlightRef.current = true;
      try {
        await Promise.all([fetchAccount(), fetchTransactions()]);
      } finally {
        inFlightRef.current = false;
      }
    }

    tick();
    const id = window.setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [fetchAccount, fetchTransactions]);

  function handleLogout() {
    clear();
    navigate('/login', { replace: true });
  }

  return (
    <main className="page page--wide">
      <header className="page__header">
        <h1>Welcome, {username}</h1>
        <nav className="page__header-actions">
          {role === 'ADMIN' && <Link to="/admin">Admin panel</Link>}
          <button type="button" onClick={handleLogout}>Sign out</button>
        </nav>
      </header>

      <section className="page__section">
        <h2>Account</h2>
        {accountError && (
          <p className="banner-error" role="alert">
            Account: {accountError}
          </p>
        )}
        {account ? (
          <p>
            Balance:{' '}
            <strong>{formatBalance(account.balance, account.currency)}</strong>
          </p>
        ) : (
          <p>Loading account…</p>
        )}
      </section>

      <section className="page__section">
        <div className="section__header">
          <h2>Transactions</h2>
          <button type="button" onClick={() => setCreateOpen(true)}>
            New transaction
          </button>
        </div>
        {transactionsError && (
          <p className="banner-error" role="alert">
            Transactions: {transactionsError}
          </p>
        )}
        <TransactionTable transactions={transactions} loading={transactionsLoading} />
      </section>

      {createOpen && (
        <CreateTransactionModal
          onClose={() => setCreateOpen(false)}
          // Nudge the table early so the new row appears without waiting up to
          // 3s for the next poll. The in-flight guard in the polling effect
          // means this can't double up with an in-progress refresh.
          onCreated={() => {
            void fetchTransactions();
            void fetchAccount();
          }}
        />
      )}
    </main>
  );
}

function formatBalance(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount);
  } catch {
    return `${amount.toFixed(2)} ${currency}`;
  }
}
